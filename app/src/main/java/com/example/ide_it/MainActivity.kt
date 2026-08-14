package com.example.ide_it

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * IDE_IT — середовище розробки на телефоні.
 *
 * Інтерфейс — сторінка з assets, що працює у WebView.
 * HTML/CSS/JS виконуються тут же, по-справжньому.
 * Node.js, Python і Go запускаються на комп'ютері через сервер `ide/server.js`
 * (вкладка «Зв'язок»), бо на Android цих середовищ немає.
 *
 * Файли — справжні файли у теці застосунку: filesDir/workspace.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var workspace: File
    private val pyExecutor = Executors.newSingleThreadExecutor()
    private val usbExecutor = Executors.newSingleThreadExecutor()
    private val flasher by lazy { UsbFlasher(this) { kind, text -> usbEvent(kind, text) } }

    /** Події прошивки → сторінка. */
    private fun usbEvent(kind: String, text: String) {
        val payload = JSONObject().put("t", kind).put("m", text).toString()
        runOnUiThread { web.evaluateJavascript("window.__usb && window.__usb($payload)", null) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        workspace = File(filesDir, "workspace").apply { mkdirs() }
        seedSamples()

        // CPython усередині застосунку — Python працює без комп'ютера
        if (!Python.isStarted()) Python.start(AndroidPlatform(this))

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }

        web = findViewById(R.id.web)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            builtInZoomControls = false
            displayZoomControls = false
            textZoom = 100
            mediaPlaybackRequiresUserGesture = false
        }
        web.setBackgroundColor(0xFF0A0E12.toInt())
        web.webChromeClient = WebChromeClient()
        web.addJavascriptInterface(Bridge(), "Android")
        web.loadUrl("file:///android_asset/ide/index.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("window.__onBack && window.__onBack()") { result ->
                    if (result != "true") {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    /* ---------------------------------------------------------------- */
    /* Приклади при першому запуску                                       */
    /* ---------------------------------------------------------------- */
    private fun seedSamples() {
        if (workspace.list()?.isNotEmpty() == true) return
        val samples = mapOf(
            "index.html" to """
                <!DOCTYPE html>
                <html lang="uk">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <link rel="stylesheet" href="styles.css">
                  <title>Мій застосунок</title>
                </head>
                <body>
                  <h1>Привіт з телефона!</h1>
                  <button id="go">Натисни</button>
                  <p id="out"></p>
                  <script src="app.js"></script>
                </body>
                </html>
            """.trimIndent(),
            "styles.css" to """
                body{font-family:system-ui,sans-serif;margin:24px;background:#0f1720;color:#dbe6ee}
                h1{font-size:22px}
                button{min-height:48px;padding:12px 20px;border-radius:8px;border:1px solid #2c4a5e;
                  background:#16232f;color:#dbe6ee;font-size:16px}
            """.trimIndent(),
            "app.js" to """
                // натисни «Запустити» — сторінка відкриється у перегляді
                let clicks = 0;
                document.getElementById('go').addEventListener('click', () => {
                  clicks += 1;
                  document.getElementById('out').textContent = 'Натискань: ' + clicks;
                });
            """.trimIndent(),
            "script.js" to """
                // Чистий JavaScript — виконується прямо на телефоні.
                // Натисни «Запустити», вивід зʼявиться в консолі.
                function factorial(n) {
                  return n <= 1 ? 1 : n * factorial(n - 1);
                }

                for (let i = 1; i <= 5; i++) {
                  console.log(i + '! =', factorial(i));
                }
                console.log('пристрій:', navigator.userAgent.split(')')[0] + ')');
            """.trimIndent(),
            "hello.py" to """
                # Python виконується на комп'ютері — увімкни «Зв'язок»
                import sys

                print("Привіт з Python!")
                print("версія:", sys.version.split()[0])
            """.trimIndent(),
            "hello.js" to """
                // Node.js виконується на комп'ютері — увімкни «Зв'язок»
                const os = require('os');
                console.log('Привіт з Node.js!');
                console.log('версія:', process.version, os.platform());
            """.trimIndent()
        )
        samples.forEach { (name, body) -> File(workspace, name).writeText(body) }
    }

    /* ---------------------------------------------------------------- */
    /* Міст JS ↔ Kotlin                                                   */
    /* ---------------------------------------------------------------- */
    private fun safe(rel: String): File {
        val f = File(workspace, rel.trimStart('/', '\\')).canonicalFile
        val base = workspace.canonicalFile
        require(f == base || f.path.startsWith(base.path + File.separator)) { "шлях поза робочою текою" }
        return f
    }

    private fun ok(extra: JSONObject.() -> Unit = {}): String =
        JSONObject().apply { put("ok", true); extra() }.toString()

    private fun fail(msg: String): String =
        JSONObject().apply { put("ok", false); put("error", msg) }.toString()

    inner class Bridge {

        @JavascriptInterface
        fun list(): String = try {
            val arr = JSONArray()
            workspace.listFiles()
                ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                ?.forEach { f ->
                    arr.put(JSONObject().apply {
                        put("name", f.name)
                        put("path", f.name)
                        put("dir", f.isDirectory)
                        put("size", if (f.isFile) f.length() else 0L)
                    })
                }
            JSONObject().apply { put("ok", true); put("files", arr) }.toString()
        } catch (e: Exception) { fail(e.message ?: "не вдалося прочитати теку") }

        @JavascriptInterface
        fun read(path: String): String = try {
            val f = safe(path)
            if (!f.exists()) fail("файл не знайдено")
            else if (f.length() > 2_000_000) fail("файл завеликий для редактора")
            else JSONObject().apply { put("ok", true); put("content", f.readText()) }.toString()
        } catch (e: Exception) { fail(e.message ?: "помилка читання") }

        @JavascriptInterface
        fun write(path: String, content: String): String = try {
            val f = safe(path)
            f.parentFile?.mkdirs()
            f.writeText(content)
            ok()
        } catch (e: Exception) { fail(e.message ?: "помилка запису") }

        @JavascriptInterface
        fun create(path: String, isDir: Boolean): String = try {
            val f = safe(path)
            when {
                f.exists() -> fail("уже існує")
                isDir -> { f.mkdirs(); ok() }
                else -> { f.parentFile?.mkdirs(); f.createNewFile(); ok() }
            }
        } catch (e: Exception) { fail(e.message ?: "не вдалося створити") }

        @JavascriptInterface
        fun delete(path: String): String = try {
            safe(path).deleteRecursively()
            ok()
        } catch (e: Exception) { fail(e.message ?: "не вдалося видалити") }

        @JavascriptInterface
        fun rename(from: String, to: String): String = try {
            val ok = safe(from).renameTo(safe(to))
            if (ok) ok() else fail("не вдалося перейменувати")
        } catch (e: Exception) { fail(e.message ?: "помилка") }

        /** Шлях до робочої теки — показуємо користувачу. */
        @JavascriptInterface
        fun workspacePath(): String = workspace.absolutePath

        /* ---------------- Плата через OTG ---------------- */

        @JavascriptInterface
        fun usbList(): String = try { flasher.listDevices() }
        catch (e: Throwable) { fail(e.message ?: "не вдалося перелічити пристрої") }

        @JavascriptInterface
        fun usbPermission(deviceId: Int) {
            flasher.requestPermission(deviceId) { granted ->
                usbEvent(if (granted) "ok" else "err",
                    if (granted) "дозвіл на пристрій отримано" else "дозвіл не надано")
                usbEvent("devices", "")
            }
        }

        /** Безпечна перевірка зв'язку з платою — нічого не пише у пам'ять. */
        @JavascriptInterface
        fun usbProbe(deviceId: Int) {
            usbExecutor.execute { flasher.probeBoard(deviceId); usbEvent("done", "") }
        }

        /**
         * Прошивка. plan — JSON із комп'ютера:
         * {"family":"esp32"|"avr","parts":[{"offset":4096,"b64":"…"}]}
         */
        @JavascriptInterface
        fun usbFlash(deviceId: Int, plan: String, baud: Int) {
            usbExecutor.execute {
                try {
                    val j = JSONObject(plan)
                    val arr = j.getJSONArray("parts")
                    val parts = ArrayList<Pair<Long, ByteArray>>()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        parts.add(Pair(o.getLong("offset"),
                            Base64.decode(o.getString("b64"), Base64.DEFAULT)))
                    }
                    if (j.optString("family") == "avr") {
                        flasher.flashAvr(deviceId, parts.firstOrNull()?.second ?: ByteArray(0))
                    } else {
                        flasher.flashEsp32(deviceId, parts, baud)
                    }
                } catch (e: Throwable) {
                    usbEvent("err", e.message ?: "не вдалося розібрати прошивку")
                }
                usbEvent("done", "")
            }
        }

        @JavascriptInterface
        fun usbMonitor(deviceId: Int, baud: Int) {
            usbExecutor.execute { flasher.monitor(deviceId, baud); usbEvent("done", "") }
        }

        @JavascriptInterface
        fun usbStop() { flasher.stopMonitor(); flasher.cancel() }

        /* ---------------- Python усередині телефона ---------------- */

        @JavascriptInterface
        fun pythonVersion(): String = try {
            Python.getInstance().getModule("runner").callAttr("version").toString()
        } catch (e: Throwable) { "" }

        /**
         * Виконує код у фоновому потоці, щоб інтерфейс не завмирав,
         * і повертає вивід сторінці через window.__pyResult(...).
         */
        @JavascriptInterface
        fun runPython(code: String) {
            pyExecutor.execute {
                val payload = JSONObject()
                try {
                    val text = Python.getInstance().getModule("runner")
                        .callAttr("run", code).toString()
                    payload.put("ok", true).put("output", text)
                } catch (e: Throwable) {
                    payload.put("ok", false).put("error", e.message ?: e.toString())
                }
                val js = "window.__pyResult && window.__pyResult($payload)"
                runOnUiThread { web.evaluateJavascript(js, null) }
            }
        }

        @JavascriptInterface
        fun toast(msg: String) {
            runOnUiThread { Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show() }
        }
    }
}
