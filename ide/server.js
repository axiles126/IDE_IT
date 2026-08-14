#!/usr/bin/env node
/**
 * IDE_IT — локальний сервер середовища розробки.
 *
 *   node server.js [шлях-до-робочої-теки] [--port 4321]
 *
 * Без зовнішніх залежностей. Слухає лише 127.0.0.1.
 * Кожен запит до API вимагає токен, який друкується при старті:
 * без нього сторонній процес на машині не зможе запускати команди.
 */
'use strict';

const http = require('http');
const fs = require('fs');
const fsp = require('fs/promises');
const path = require('path');
const os = require('os');
const crypto = require('crypto');
const { spawn, spawnSync } = require('child_process');

/* ------------------------------------------------------------------ */
/* Конфігурація                                                        */
/* ------------------------------------------------------------------ */
const argv = process.argv.slice(2);
let rootArg = null, port = Number(process.env.IDE_PORT || 4321);
for (let i = 0; i < argv.length; i++) {
  if (argv[i] === '--port' || argv[i] === '-p') port = Number(argv[++i]);
  else if (!argv[i].startsWith('-')) rootArg = argv[i];
}
const ROOT = path.resolve(rootArg || path.join(__dirname, 'workspace'));
const PUBLIC = path.join(__dirname, 'public');
const TOKEN = process.env.IDE_TOKEN || crypto.randomBytes(16).toString('hex');
const MAX_FILE = 2 * 1024 * 1024;          // 2 МБ на файл у редакторі
const MAX_OUT = 200 * 1024;                // ліміт виводу одного запуску

/* ------------------------------------------------------------------ */
/* Робоча тека та приклади                                             */
/* ------------------------------------------------------------------ */
const SAMPLES = {
  'index.html':
`<!DOCTYPE html>
<html lang="uk">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <link rel="stylesheet" href="styles.css">
  <title>Проєкт</title>
</head>
<body>
  <h1>Привіт!</h1>
  <button id="go">Натисни</button>
  <p id="out"></p>
  <script src="app.js"></script>
</body>
</html>
`,
  'styles.css':
`body{font-family:system-ui,sans-serif;margin:40px;background:#0f1720;color:#dbe6ee}
button{min-height:44px;padding:10px 18px;border-radius:6px;border:1px solid #2c4a5e;
  background:#16232f;color:#dbe6ee;cursor:pointer}
button:hover{border-color:#5fd38a}
`,
  'app.js':
`// натисни «Переглянути», щоб побачити сторінку
let clicks = 0;
document.getElementById('go').addEventListener('click', () => {
  clicks += 1;
  document.getElementById('out').textContent = 'Натискань: ' + clicks;
});
`,
  'hello.js':
`// Node.js — запусти кнопкою «Запустити» (Ctrl+Enter)
const os = require('os');

function greet(name) {
  return \`Привіт, \${name}!\`;
}

console.log(greet('Node'));
console.log('версія node:', process.version);
console.log('платформа  :', os.platform(), os.arch());
`,
  'hello.py':
`# Python — запусти кнопкою «Запустити» (Ctrl+Enter)
import sys
import platform


def greet(name: str) -> str:
    return f"Привіт, {name}!"


print(greet("Python"))
print("версія python:", sys.version.split()[0])
print("платформа    :", platform.system(), platform.machine())
`,
  'main.go':
`// Go — потрібен встановлений Go (https://go.dev/dl/)
package main

import (
	"fmt"
	"runtime"
)

func greet(name string) string {
	return fmt.Sprintf("Привіт, %s!", name)
}

func main() {
	fmt.Println(greet("Go"))
	fmt.Println("версія go  :", runtime.Version())
	fmt.Println("платформа  :", runtime.GOOS, runtime.GOARCH)
}
`,
  'README.md':
`# Робоча тека

Файли тут — справжні файли на диску. Редактор пише прямо в них.

- \`hello.js\`  — Node.js
- \`hello.py\`  — Python
- \`main.go\`   — Go
- \`index.html\` — відкрий «Переглянути»

Термінал унизу — звичайна командна оболонка в цій теці:
\`npm init -y\`, \`pip install requests\`, \`go mod init\`, \`git status\`.
`
};

function ensureWorkspace() {
  fs.mkdirSync(ROOT, { recursive: true });
  if (fs.readdirSync(ROOT).length === 0) {
    for (const [name, body] of Object.entries(SAMPLES)) {
      fs.writeFileSync(path.join(ROOT, name), body, 'utf8');
    }
    return true;
  }
  return false;
}

/* ------------------------------------------------------------------ */
/* Безпечні шляхи: нічого поза робочою текою                           */
/* ------------------------------------------------------------------ */
function safe(rel) {
  const clean = String(rel || '').replace(/^[/\\]+/, '');
  const abs = path.resolve(ROOT, clean);
  const base = ROOT.endsWith(path.sep) ? ROOT : ROOT + path.sep;
  if (abs !== ROOT && !abs.startsWith(base)) {
    const e = new Error('шлях поза робочою текою');
    e.code = 'EOUT';
    throw e;
  }
  return abs;
}

const IGNORE = new Set(['node_modules', '.git', '__pycache__', '.venv', 'venv', 'dist', 'build', '.idea', '.gradle']);

async function tree(dir = ROOT, rel = '', depth = 0) {
  if (depth > 6) return [];
  let entries;
  try { entries = await fsp.readdir(dir, { withFileTypes: true }); }
  catch { return []; }
  entries.sort((a, b) =>
    (a.isDirectory() === b.isDirectory()) ? a.name.localeCompare(b.name) : (a.isDirectory() ? -1 : 1));
  const out = [];
  for (const e of entries) {
    if (e.name.startsWith('.') && e.name !== '.gitignore') continue;
    if (IGNORE.has(e.name)) { out.push({ name: e.name, path: rel ? rel + '/' + e.name : e.name, dir: true, skipped: true, children: [] }); continue; }
    const p = rel ? rel + '/' + e.name : e.name;
    if (e.isDirectory()) out.push({ name: e.name, path: p, dir: true, children: await tree(path.join(dir, e.name), p, depth + 1) });
    else {
      let size = 0;
      try { size = (await fsp.stat(path.join(dir, e.name))).size; } catch {}
      out.push({ name: e.name, path: p, dir: false, size });
    }
  }
  return out;
}

/* ------------------------------------------------------------------ */
/* Наявні інструменти                                                  */
/* ------------------------------------------------------------------ */
function probe(cmd, args) {
  try {
    const r = spawnSync(cmd, args, { encoding: 'utf8', timeout: 6000, shell: process.platform === 'win32' });
    if (r.status !== 0) return null;                       // не знайдено або помилка
    const v = String(r.stdout || r.stderr).trim().split('\n')[0].trim();
    // відсікаємо повідомлення оболонки на кшталт "'go' is not recognized…"
    if (!v || /is not recognized|not found|command not found/i.test(v)) return null;
    return v;
  } catch {}
  return null;
}
let TOOLS = null;
function detectTools() {
  const py = probe('python', ['--version']) || probe('py', ['--version']) || probe('python3', ['--version']);
  TOOLS = {
    node: probe('node', ['--version']),
    npm: probe('npm', ['--version']),
    python: py,
    pythonCmd: probe('python', ['--version']) ? 'python' : (probe('py', ['--version']) ? 'py' : 'python3'),
    go: probe('go', ['version']),
    git: probe('git', ['--version'])
  };
  return TOOLS;
}

/* ------------------------------------------------------------------ */
/* Запуск процесів                                                     */
/* ------------------------------------------------------------------ */
const clients = new Set();          // SSE-підписники
const procs = new Map();            // id -> child

function broadcast(obj) {
  const line = 'data: ' + JSON.stringify(obj) + '\n\n';
  for (const res of clients) { try { res.write(line); } catch {} }
}

function launch({ cmd, args, cwd, label, shell = false }) {
  const id = crypto.randomBytes(6).toString('hex');
  let sent = 0;
  broadcast({ t: 'start', id, label });
  let child;
  try {
    child = spawn(cmd, args, { cwd, shell, env: { ...process.env, PYTHONIOENCODING: 'utf-8', PYTHONUNBUFFERED: '1', FORCE_COLOR: '0' } });
  } catch (e) {
    broadcast({ t: 'err', id, data: 'не вдалося запустити: ' + e.message });
    broadcast({ t: 'exit', id, code: -1 });
    return id;
  }
  procs.set(id, child);
  const pipe = (stream, t) => {
    stream.setEncoding('utf8');
    stream.on('data', d => {
      if (sent > MAX_OUT) return;
      sent += d.length;
      broadcast({ t, id, data: sent > MAX_OUT ? d + '\n[вивід обрізано]' : d });
    });
  };
  pipe(child.stdout, 'out');
  pipe(child.stderr, 'err');
  child.on('error', e => broadcast({ t: 'err', id, data: e.message }));
  child.on('close', code => { procs.delete(id); broadcast({ t: 'exit', id, code }); });
  return id;
}

function runFile(rel) {
  const abs = safe(rel);
  const ext = path.extname(abs).toLowerCase();
  const cwd = path.dirname(abs);
  const file = path.basename(abs);
  const t = TOOLS || detectTools();
  const win = process.platform === 'win32';

  switch (ext) {
    case '.js': case '.mjs': case '.cjs':
      if (!t.node) return { error: 'Node.js не знайдено' };
      return { id: launch({ cmd: 'node', args: [file], cwd, label: 'node ' + file, shell: win }) };
    case '.ts':
      if (!t.node) return { error: 'Node.js не знайдено' };
      return { id: launch({ cmd: 'node', args: ['--experimental-strip-types', file], cwd, label: 'node --experimental-strip-types ' + file, shell: win }) };
    case '.py':
      if (!t.python) return { error: 'Python не знайдено' };
      return { id: launch({ cmd: t.pythonCmd, args: ['-u', file], cwd, label: t.pythonCmd + ' ' + file, shell: win }) };
    case '.go':
      if (!t.go) return { error: 'Go не встановлено. Завантажити: https://go.dev/dl/ — після встановлення натисни «оновити» біля списку інструментів.' };
      return { id: launch({ cmd: 'go', args: ['run', file], cwd, label: 'go run ' + file, shell: win }) };
    case '.html': case '.htm':
      return { preview: rel };
    case '.css':
      return { error: 'CSS не запускається окремо — відкрий HTML-файл і натисни «Переглянути».' };
    default:
      return { error: 'Не знаю, чим запускати «' + ext + '». Скористайся терміналом.' };
  }
}

/* ------------------------------------------------------------------ */
/* HTTP                                                                */
/* ------------------------------------------------------------------ */
const MIME = {
  '.html': 'text/html; charset=utf-8', '.htm': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg',
  '.gif': 'image/gif', '.webp': 'image/webp', '.ico': 'image/x-icon',
  '.txt': 'text/plain; charset=utf-8', '.md': 'text/plain; charset=utf-8',
  '.wasm': 'application/wasm', '.woff2': 'font/woff2'
};

function send(res, code, body, type = 'application/json; charset=utf-8', extra = {}) {
  res.writeHead(code, Object.assign({
    'Content-Type': type,
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff'
  }, extra));
  res.end(body);
}
const json = (res, code, obj) => send(res, code, JSON.stringify(obj));

function readBody(req, limit = MAX_FILE + 4096) {
  return new Promise((resolve, reject) => {
    let n = 0; const chunks = [];
    req.on('data', c => {
      n += c.length;
      if (n > limit) { reject(new Error('забагато даних')); req.destroy(); return; }
      chunks.push(c);
    });
    req.on('end', () => {
      const raw = Buffer.concat(chunks).toString('utf8');
      if (!raw) return resolve({});
      try { resolve(JSON.parse(raw)); } catch (e) { reject(new Error('погана структура запиту')); }
    });
    req.on('error', reject);
  });
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://127.0.0.1');
  const p = decodeURIComponent(url.pathname);

  /* сторінка IDE */
  if (p === '/' || p === '/index.html') {
    let html;
    try { html = await fsp.readFile(path.join(PUBLIC, 'index.html'), 'utf8'); }
    catch { return send(res, 500, 'Не знайдено public/index.html поруч із server.js', 'text/plain; charset=utf-8'); }
    html = html.replace('__IDE_TOKEN__', TOKEN);
    return send(res, 200, html, 'text/html; charset=utf-8');
  }

  /* попередній перегляд: справжні файли робочої теки */
  if (p.startsWith('/preview/')) {
    let abs;
    try { abs = safe(p.slice('/preview/'.length)); }
    catch { return send(res, 403, 'заборонено', 'text/plain; charset=utf-8'); }
    try {
      const st = await fsp.stat(abs);
      if (st.isDirectory()) abs = path.join(abs, 'index.html');
      const data = await fsp.readFile(abs);
      return send(res, 200, data, MIME[path.extname(abs).toLowerCase()] || 'application/octet-stream');
    } catch { return send(res, 404, 'файл не знайдено', 'text/plain; charset=utf-8'); }
  }

  /* усе інше — API, лише з токеном */
  if (!p.startsWith('/api/')) return send(res, 404, 'not found', 'text/plain');
  const token = req.headers['x-ide-token'] || url.searchParams.get('t');
  if (token !== TOKEN) return json(res, 401, { error: 'невірний токен' });

  try {
    /* потік подій */
    if (p === '/api/events') {
      res.writeHead(200, {
        'Content-Type': 'text/event-stream; charset=utf-8',
        'Cache-Control': 'no-cache',
        'Connection': 'keep-alive'
      });
      res.write(': ok\n\n');
      clients.add(res);
      const ping = setInterval(() => { try { res.write(': ping\n\n'); } catch {} }, 25000);
      req.on('close', () => { clearInterval(ping); clients.delete(res); });
      return;
    }

    if (p === '/api/state' && req.method === 'GET') {
      return json(res, 200, {
        root: ROOT, platform: process.platform, home: os.homedir(),
        tools: TOOLS || detectTools(), tree: await tree()
      });
    }
    if (p === '/api/tools' && req.method === 'POST') return json(res, 200, { tools: detectTools() });
    if (p === '/api/tree' && req.method === 'GET') return json(res, 200, { tree: await tree() });

    if (p === '/api/file' && req.method === 'GET') {
      const abs = safe(url.searchParams.get('p'));
      const st = await fsp.stat(abs);
      if (st.size > MAX_FILE) return json(res, 413, { error: 'файл завеликий для редактора (>2 МБ)' });
      const buf = await fsp.readFile(abs);
      if (buf.includes(0)) return json(res, 415, { error: 'двійковий файл' });
      return json(res, 200, { content: buf.toString('utf8') });
    }
    if (p === '/api/file' && req.method === 'POST') {
      const b = await readBody(req);
      const abs = safe(b.path);
      await fsp.mkdir(path.dirname(abs), { recursive: true });
      await fsp.writeFile(abs, String(b.content ?? ''), 'utf8');
      return json(res, 200, { ok: true });
    }
    if (p === '/api/create' && req.method === 'POST') {
      const b = await readBody(req);
      const abs = safe(b.path);
      if (b.dir) await fsp.mkdir(abs, { recursive: true });
      else {
        await fsp.mkdir(path.dirname(abs), { recursive: true });
        try { await fsp.access(abs); return json(res, 409, { error: 'уже існує' }); }
        catch { await fsp.writeFile(abs, '', 'utf8'); }
      }
      return json(res, 200, { ok: true, tree: await tree() });
    }
    if (p === '/api/delete' && req.method === 'POST') {
      const b = await readBody(req);
      const abs = safe(b.path);
      if (abs === ROOT) return json(res, 400, { error: 'не можна видалити робочу теку' });
      await fsp.rm(abs, { recursive: true, force: true });
      return json(res, 200, { ok: true, tree: await tree() });
    }
    if (p === '/api/rename' && req.method === 'POST') {
      const b = await readBody(req);
      await fsp.rename(safe(b.from), safe(b.to));
      return json(res, 200, { ok: true, tree: await tree() });
    }

    if (p === '/api/run' && req.method === 'POST') {
      const b = await readBody(req);
      const r = runFile(b.path);
      return json(res, r.error ? 400 : 200, r);
    }
    if (p === '/api/exec' && req.method === 'POST') {
      const b = await readBody(req);
      const cmd = String(b.cmd || '').trim();
      if (!cmd) return json(res, 400, { error: 'порожня команда' });
      let cwd = ROOT;
      if (b.cwd) { try { cwd = safe(b.cwd); } catch {} }
      const id = launch({ cmd, args: [], cwd, label: cmd, shell: true });
      return json(res, 200, { id });
    }
    if (p === '/api/stdin' && req.method === 'POST') {
      const b = await readBody(req);
      const child = procs.get(b.id);
      if (!child || !child.stdin.writable) return json(res, 404, { error: 'процес не запущено' });
      child.stdin.write(String(b.data ?? '') + '\n');
      return json(res, 200, { ok: true });
    }
    if (p === '/api/stop' && req.method === 'POST') {
      const b = await readBody(req);
      let n = 0;
      for (const [id, child] of procs) {
        if (b.id && b.id !== id) continue;
        try {
          if (process.platform === 'win32') spawnSync('taskkill', ['/pid', String(child.pid), '/f', '/t']);
          else child.kill('SIGTERM');
          n++;
        } catch {}
      }
      return json(res, 200, { stopped: n });
    }

    return json(res, 404, { error: 'невідомий метод API' });
  } catch (e) {
    const code = e.code === 'EOUT' ? 403 : (e.code === 'ENOENT' ? 404 : 400);
    return json(res, code, { error: e.message });
  }
});

/* ------------------------------------------------------------------ */
/* Старт                                                               */
/* ------------------------------------------------------------------ */
const seeded = ensureWorkspace();
detectTools();

server.on('error', e => {
  if (e.code === 'EADDRINUSE') {
    console.error(`\n  Порт ${port} зайнятий. Запусти з іншим: node server.js --port ${port + 1}\n`);
    process.exit(1);
  }
  throw e;
});

server.listen(port, '127.0.0.1', () => {
  const line = '─'.repeat(58);
  console.log('\n' + line);
  console.log('  IDE_IT — середовище розробки');
  console.log(line);
  console.log('  Робоча тека : ' + ROOT + (seeded ? '  (створено приклади)' : ''));
  console.log('  Node.js     : ' + (TOOLS.node || 'не знайдено'));
  console.log('  Python      : ' + (TOOLS.python || 'не знайдено'));
  console.log('  Go          : ' + (TOOLS.go || 'не встановлено — https://go.dev/dl/'));
  console.log(line);
  console.log('  Відкрий у браузері:\n');
  console.log('  http://127.0.0.1:' + port + '/?t=' + TOKEN + '\n');
  console.log('  (посилання з токеном — без нього API не відповідає)');
  console.log(line + '\n');
});

function shutdown() {
  for (const [, child] of procs) { try { child.kill(); } catch {} }
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(0), 1500).unref();
}
process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
