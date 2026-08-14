package com.example.ide_it

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import org.json.JSONArray
import org.json.JSONObject
import kotlin.experimental.xor

/**
 * Прошивка плати напряму з телефона через USB-OTG.
 *
 * Дві родини:
 *  • ESP32 — завантажувач у ПЗП (SLIP + команди SYNC / FLASH_BEGIN / FLASH_DATA / FLASH_END);
 *  • AVR (Uno, Nano, Mega) — протокол STK500v1, той самий, що використовує avrdude.
 *
 * Кабель має бути OTG, а плата — живитися від телефона. Скомпільовані двійкові файли
 * приходять із комп'ютера (arduino-cli), сюди — вже готовими до запису.
 */
class UsbFlasher(private val ctx: Context, private val log: (String, String) -> Unit) {

    companion object {
        private const val ACTION_PERMISSION = "com.example.ide_it.USB_PERMISSION"

        /* команди завантажувача ESP32 */
        private const val CMD_FLASH_BEGIN = 0x02
        private const val CMD_FLASH_DATA = 0x03
        private const val CMD_FLASH_END = 0x04
        private const val CMD_SYNC = 0x08
        private const val CMD_READ_REG = 0x0A
        private const val CMD_CHANGE_BAUD = 0x0F

        private const val ESP_BLOCK = 0x400          // розмір блоку для ПЗП-завантажувача
        private const val CHIP_MAGIC_REG = 0x40001000L

        /** Відомі значення регістра з ідентифікатором кристала. */
        private val CHIP_MAGIC = mapOf(
            0x00f01d83L to "ESP32",
            0x000007c6L to "ESP32-S2",
            0x09L to "ESP32-S3",
            0x6921506fL to "ESP32-C3",
            0x1b31506fL to "ESP32-C3",
            0x4881606fL to "ESP32-C3",
            0x4361606fL to "ESP32-C3",
            0xfff0c101L to "ESP8266"
        )
    }

    private val manager get() = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
    @Volatile private var cancelled = false

    fun cancel() { cancelled = true }

    /* ------------------------------------------------------------------ */
    /* Пошук пристроїв                                                     */
    /* ------------------------------------------------------------------ */
    private fun prober(): UsbSerialProber {
        // до стандартної таблиці додаємо CDC — так бачимо ESP32-S3/C3 із вбудованим USB
        val table: ProbeTable = UsbSerialProber.getDefaultProbeTable()
        table.addProduct(0x303a, 0x1001, CdcAcmSerialDriver::class.java)   // Espressif native USB
        table.addProduct(0x303a, 0x0002, CdcAcmSerialDriver::class.java)
        return UsbSerialProber(table)
    }

    fun listDevices(): String {
        val arr = JSONArray()
        val drivers: List<UsbSerialDriver> = prober().findAllDrivers(manager)
        for (d in drivers) {
            val dev = d.device
            arr.put(JSONObject().apply {
                put("id", dev.deviceId)
                put("name", dev.productName ?: dev.deviceName)
                put("manufacturer", dev.manufacturerName ?: "")
                put("vid", String.format("%04x", dev.vendorId))
                put("pid", String.format("%04x", dev.productId))
                put("driver", d.javaClass.simpleName.removeSuffix("SerialDriver"))
                put("permission", manager.hasPermission(dev))
            })
        }
        // пристрої без відомого драйвера теж показуємо — щоб було видно, що телефон їх бачить
        for (dev in manager.deviceList.values) {
            if (drivers.any { it.device.deviceId == dev.deviceId }) continue
            arr.put(JSONObject().apply {
                put("id", dev.deviceId)
                put("name", dev.productName ?: dev.deviceName)
                put("manufacturer", dev.manufacturerName ?: "")
                put("vid", String.format("%04x", dev.vendorId))
                put("pid", String.format("%04x", dev.productId))
                put("driver", "")
                put("permission", manager.hasPermission(dev))
            })
        }
        return JSONObject().put("ok", true).put("devices", arr).toString()
    }

    private fun findDevice(id: Int): UsbDevice? =
        manager.deviceList.values.firstOrNull { it.deviceId == id }

    /** Запит дозволу в користувача. Відповідь приходить у onResult. */
    fun requestPermission(id: Int, onResult: (Boolean) -> Unit) {
        val dev = findDevice(id) ?: return onResult(false)
        if (manager.hasPermission(dev)) return onResult(true)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action != ACTION_PERMISSION) return
                try { ctx.unregisterReceiver(this) } catch (_: Exception) {}
                onResult(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
            }
        }
        ContextCompat.registerReceiver(
            ctx, receiver, IntentFilter(ACTION_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        val pi = PendingIntent.getBroadcast(
            ctx, 0, Intent(ACTION_PERMISSION).setPackage(ctx.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        manager.requestPermission(dev, pi)
    }

    /* ------------------------------------------------------------------ */
    /* Відкриття порту                                                     */
    /* ------------------------------------------------------------------ */
    private fun openPort(id: Int, baud: Int): UsbSerialPort {
        val driver = prober().findAllDrivers(manager).firstOrNull { it.device.deviceId == id }
            ?: throw IllegalStateException("для цього пристрою немає драйвера USB-serial")
        val dev = driver.device
        if (!manager.hasPermission(dev)) throw IllegalStateException("немає дозволу на пристрій")
        val conn = manager.openDevice(dev) ?: throw IllegalStateException("не вдалося відкрити пристрій")
        val port = driver.ports[0]
        port.open(conn)
        port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        return port
    }

    /* ------------------------------------------------------------------ */
    /* SLIP та пакети ESP32                                                */
    /* ------------------------------------------------------------------ */
    private fun slipEncode(payload: ByteArray): ByteArray {
        val out = ArrayList<Byte>(payload.size + 16)
        out.add(0xC0.toByte())
        for (b in payload) when (b) {
            0xC0.toByte() -> { out.add(0xDB.toByte()); out.add(0xDC.toByte()) }
            0xDB.toByte() -> { out.add(0xDB.toByte()); out.add(0xDD.toByte()) }
            else -> out.add(b)
        }
        out.add(0xC0.toByte())
        return out.toByteArray()
    }

    private fun le32(v: Long): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte()
    )

    private fun packet(cmd: Int, data: ByteArray, checksum: Long = 0): ByteArray {
        val head = ByteArray(8)
        head[0] = 0
        head[1] = cmd.toByte()
        head[2] = (data.size and 0xFF).toByte()
        head[3] = ((data.size shr 8) and 0xFF).toByte()
        le32(checksum).copyInto(head, 4)
        return slipEncode(head + data)
    }

    private fun checksum(data: ByteArray): Long {
        var st: Byte = 0xEF.toByte()
        for (b in data) st = st xor b
        return (st.toInt() and 0xFF).toLong()
    }

    /** Читає один SLIP-кадр; повертає null, якщо час вичерпано. */
    private fun readFrame(port: UsbSerialPort, timeoutMs: Int): ByteArray? {
        val deadline = System.currentTimeMillis() + timeoutMs
        val buf = ByteArray(1024)
        val frame = ArrayList<Byte>(256)
        var started = false
        var escaped = false
        while (System.currentTimeMillis() < deadline) {
            val n = try { port.read(buf, 120) } catch (e: Exception) { 0 }
            if (n <= 0) continue
            for (i in 0 until n) {
                val b = buf[i]
                if (!started) { if (b == 0xC0.toByte()) started = true; continue }
                when {
                    escaped -> {
                        frame.add(if (b == 0xDC.toByte()) 0xC0.toByte() else 0xDB.toByte()); escaped = false
                    }
                    b == 0xDB.toByte() -> escaped = true
                    b == 0xC0.toByte() -> {
                        if (frame.isEmpty()) { started = true }        // подвійний роздільник
                        else return frame.toByteArray()
                    }
                    else -> frame.add(b)
                }
            }
        }
        return null
    }

    /** Надсилає команду й чекає відповідь; повертає value + дані. */
    private fun command(port: UsbSerialPort, cmd: Int, data: ByteArray,
                        checksum: Long = 0, timeoutMs: Int = 3000): Pair<Long, ByteArray> {
        port.write(packet(cmd, data, checksum), 2000)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val f = readFrame(port, (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(100))
                ?: continue
            if (f.size < 8) continue
            if (f[0] != 1.toByte()) continue                       // не відповідь
            if ((f[1].toInt() and 0xFF) != cmd) continue           // відповідь на іншу команду
            val value = (f[4].toLong() and 0xFF) or ((f[5].toLong() and 0xFF) shl 8) or
                    ((f[6].toLong() and 0xFF) shl 16) or ((f[7].toLong() and 0xFF) shl 24)
            val body = f.copyOfRange(8, f.size)
            if (body.size >= 2) {
                val statusIdx = if (body.size >= 4) body.size - 4 else body.size - 2
                if (body[statusIdx] != 0.toByte()) {
                    val err = body[statusIdx + 1].toInt() and 0xFF
                    throw IllegalStateException("плата відповіла помилкою 0x${err.toString(16)}")
                }
            }
            return Pair(value, body)
        }
        throw IllegalStateException("плата не відповіла на команду 0x${cmd.toString(16)}")
    }

    /** Класичне скидання в завантажувач лініями DTR/RTS. */
    private fun resetToBootloader(port: UsbSerialPort) {
        try {
            port.dtr = false; port.rts = true      // EN у нуль — скидання
            Thread.sleep(120)
            port.dtr = true; port.rts = false      // IO0 у нуль, EN відпускаємо
            Thread.sleep(60)
            port.dtr = false                       // відпускаємо IO0
            Thread.sleep(300)
        } catch (e: Exception) {
            log("warn", "плата не підтримує автоскидання: " + (e.message ?: ""))
        }
    }

    private fun sync(port: UsbSerialPort): Boolean {
        val payload = byteArrayOf(0x07, 0x07, 0x12, 0x20) + ByteArray(32) { 0x55 }
        repeat(10) {
            if (cancelled) return false
            try {
                command(port, CMD_SYNC, payload, timeoutMs = 500)
                Thread.sleep(50)
                repeat(6) { readFrame(port, 60) }        // ПЗП шле кілька однакових відповідей
                return true
            } catch (_: Exception) { Thread.sleep(100) }
        }
        return false
    }

    private fun detectChip(port: UsbSerialPort): String {
        return try {
            val (value, _) = command(port, CMD_READ_REG, le32(CHIP_MAGIC_REG))
            CHIP_MAGIC[value] ?: "невідомий ESP (0x${value.toString(16)})"
        } catch (e: Exception) { "ESP (ідентифікатор не прочитано)" }
    }

    /* ------------------------------------------------------------------ */
    /* Публічні дії                                                        */
    /* ------------------------------------------------------------------ */

    /** Безпечна перевірка: скидання, синхронізація, читання ідентифікатора. Нічого не пише. */
    fun probeBoard(deviceId: Int): String {
        var port: UsbSerialPort? = null
        return try {
            port = openPort(deviceId, 115200)
            log("sys", "порт відкрито, скидаю плату в завантажувач…")
            resetToBootloader(port)
            if (!sync(port)) throw IllegalStateException(
                "плата не відповідає. Перевір кабель (має бути OTG з передачею даних), " +
                "живлення плати та спробуй утримати кнопку BOOT під час підключення")
            val chip = detectChip(port)
            log("ok", "плата на зв'язку: $chip")
            JSONObject().put("ok", true).put("chip", chip).toString()
        } catch (e: Exception) {
            log("err", e.message ?: "не вдалося опитати плату")
            JSONObject().put("ok", false).put("error", e.message ?: "помилка").toString()
        } finally {
            try { port?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Прошивка ESP32.
     * @param parts список пар «зміщення → вміст», уже у двійковому вигляді.
     */
    fun flashEsp32(deviceId: Int, parts: List<Pair<Long, ByteArray>>, baud: Int): String {
        cancelled = false
        var port: UsbSerialPort? = null
        return try {
            port = openPort(deviceId, 115200)
            resetToBootloader(port)
            if (!sync(port)) throw IllegalStateException(
                "плата не відповідає на синхронізацію — утримай BOOT і повтори")
            val chip = detectChip(port)
            log("ok", "плата: $chip")

            if (baud > 115200) {
                try {
                    command(port, CMD_CHANGE_BAUD, le32(baud.toLong()) + le32(0))
                    Thread.sleep(50)
                    port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                    Thread.sleep(50)
                    log("sys", "швидкість підвищено до $baud")
                } catch (e: Exception) {
                    log("warn", "швидкість лишається 115200: " + (e.message ?: ""))
                }
            }

            val extraWord = !chip.startsWith("ESP32") || chip != "ESP32"   // не класичний ESP32
            var totalWritten = 0
            val totalBytes = parts.sumOf { it.second.size }

            for ((offset, raw) in parts) {
                if (cancelled) throw IllegalStateException("зупинено користувачем")
                // вирівнюємо до розміру блоку
                val padded = if (raw.size % ESP_BLOCK == 0) raw
                             else raw + ByteArray(ESP_BLOCK - raw.size % ESP_BLOCK) { 0xFF.toByte() }
                val blocks = padded.size / ESP_BLOCK

                log("sys", "запис 0x${offset.toString(16)} — ${raw.size} Б")
                var begin = le32(padded.size.toLong()) + le32(blocks.toLong()) +
                        le32(ESP_BLOCK.toLong()) + le32(offset)
                if (extraWord) begin += le32(0)
                command(port, CMD_FLASH_BEGIN, begin, timeoutMs = 20000)

                for (i in 0 until blocks) {
                    if (cancelled) throw IllegalStateException("зупинено користувачем")
                    val chunk = padded.copyOfRange(i * ESP_BLOCK, (i + 1) * ESP_BLOCK)
                    val header = le32(chunk.size.toLong()) + le32(i.toLong()) + le32(0) + le32(0)
                    command(port, CMD_FLASH_DATA, header + chunk, checksum(chunk), timeoutMs = 5000)
                    totalWritten += chunk.size
                    if (i % 8 == 0 || i == blocks - 1) {
                        val pct = (totalWritten * 100L / totalBytes.coerceAtLeast(1)).toInt()
                        log("progress", "$pct")
                    }
                }
            }

            log("sys", "завершую та перезапускаю плату…")
            try { command(port, CMD_FLASH_END, le32(0), timeoutMs = 3000) } catch (_: Exception) {}
            log("ok", "прошито успішно")
            JSONObject().put("ok", true).toString()
        } catch (e: Exception) {
            log("err", e.message ?: "помилка прошивки")
            JSONObject().put("ok", false).put("error", e.message ?: "помилка").toString()
        } finally {
            try { port?.close() } catch (_: Exception) {}
        }
    }

    /* ------------------------------------------------------------------ */
    /* AVR: STK500v1 (Uno, Nano, Mega)                                     */
    /* ------------------------------------------------------------------ */
    private fun stkExpect(port: UsbSerialPort, timeoutMs: Int = 2000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        val buf = ByteArray(64)
        val got = ArrayList<Byte>()
        while (System.currentTimeMillis() < deadline) {
            val n = try { port.read(buf, 150) } catch (e: Exception) { 0 }
            for (i in 0 until n) got.add(buf[i])
            // очікуємо 0x14 … 0x10
            val idx = got.indexOf(0x14.toByte())
            if (idx >= 0 && got.size > idx + 1 && got.contains(0x10.toByte())) return
        }
        throw IllegalStateException("плата не підтвердила команду (STK500). Натисни RESET і повтори")
    }

    private fun stkCmd(port: UsbSerialPort, body: ByteArray, timeoutMs: Int = 2000) {
        port.write(body + byteArrayOf(0x20), 1500)      // 0x20 = Sync_CRC_EOP
        stkExpect(port, timeoutMs)
    }

    fun flashAvr(deviceId: Int, image: ByteArray, pageSize: Int = 128): String {
        cancelled = false
        var port: UsbSerialPort? = null
        return try {
            port = openPort(deviceId, 115200)
            // скидання імпульсом DTR — так само робить avrdude
            port.dtr = false; port.rts = false
            Thread.sleep(120)
            port.dtr = true; port.rts = true
            Thread.sleep(300)

            var synced = false
            repeat(6) {
                try { stkCmd(port, byteArrayOf(0x30), 500); synced = true; return@repeat }
                catch (_: Exception) { Thread.sleep(150) }
            }
            if (!synced) throw IllegalStateException(
                "плата не відповідає. Перевір OTG-кабель і те, що на платі завантажувач Arduino")

            log("ok", "плата на зв'язку (STK500)")
            stkCmd(port, byteArrayOf(0x50))                      // enter programming mode

            var addr = 0                                          // адреса у словах
            var written = 0
            while (written < image.size) {
                if (cancelled) throw IllegalStateException("зупинено користувачем")
                val n = minOf(pageSize, image.size - written)
                val page = image.copyOfRange(written, written + n)
                stkCmd(port, byteArrayOf(0x55, (addr and 0xFF).toByte(), ((addr shr 8) and 0xFF).toByte()))
                stkCmd(port, byteArrayOf(0x64, ((n shr 8) and 0xFF).toByte(), (n and 0xFF).toByte(), 0x46) + page, 4000)
                written += n
                addr += n / 2
                log("progress", "${written * 100 / image.size}")
            }
            stkCmd(port, byteArrayOf(0x51))                      // leave programming mode
            log("ok", "прошито успішно ($written Б)")
            JSONObject().put("ok", true).toString()
        } catch (e: Exception) {
            log("err", e.message ?: "помилка прошивки")
            JSONObject().put("ok", false).put("error", e.message ?: "помилка").toString()
        } finally {
            try { port?.close() } catch (_: Exception) {}
        }
    }

    /* ------------------------------------------------------------------ */
    /* Монітор порту                                                       */
    /* ------------------------------------------------------------------ */
    @Volatile private var monitorRunning = false

    fun monitor(deviceId: Int, baud: Int) {
        monitorRunning = true
        var port: UsbSerialPort? = null
        try {
            port = openPort(deviceId, baud)
            port.dtr = true; port.rts = true
            log("sys", "монітор порту @$baud — «стоп» зупиняє")
            val buf = ByteArray(1024)
            while (monitorRunning && !cancelled) {
                val n = try { port.read(buf, 200) } catch (e: Exception) { break }
                if (n > 0) log("out", String(buf, 0, n, Charsets.UTF_8))
            }
        } catch (e: Exception) {
            log("err", e.message ?: "монітор зупинився")
        } finally {
            monitorRunning = false
            try { port?.close() } catch (_: Exception) {}
            log("sys", "монітор закрито")
        }
    }

    fun stopMonitor() { monitorRunning = false }
}
