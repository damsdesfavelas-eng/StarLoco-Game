// Atrion Launcher - launcher graphique du serveur prive Atrion.
// Imite le launcher Ankama (Zaap) pour connecter le client Dofus Retro au serveur Atrion.
// Tourne avec le Node integre au client (Dofus Retro.exe + ELECTRON_RUN_AS_NODE=1), sans dependance.
// L'interface (atrion-launcher.html) s'ouvre dans une fenetre Edge en mode application.
'use strict';
const net = require('net');
const http = require('http');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');

const DIR = __dirname;
const CONFIG_FILE = path.join(DIR, 'atrion.json');
const LOG_FILE = path.join(DIR, 'atrion-launcher.log');
const UI_FILE = path.join(DIR, 'atrion-launcher.html');
const UI_PORT = 26118;
const DEFAULT_CONFIG = {
  zaapApi: 'http://192.168.1.12:8000',
  zaapPort: 26117,
  gamePort: 450,
  account: '',
  password: '',
  savedPassword: '',
  remember: true,
  rememberPassword: false,
  gameExe: 'Dofus Retro.exe'
};

const logLines = [];
function log(...a) {
  const line = '[' + new Date().toTimeString().substr(0, 8) + '] ' + a.map(x => typeof x === 'string' ? x : JSON.stringify(x)).join(' ');
  console.log(line);
  logLines.push(line);
  if (logLines.length > 200) logLines.shift();
  try { fs.appendFileSync(LOG_FILE, line + '\n'); } catch (e) {}
}

function loadConfig() {
  let cfg = Object.assign({}, DEFAULT_CONFIG);
  try { cfg = Object.assign(cfg, JSON.parse(fs.readFileSync(CONFIG_FILE, 'utf8').replace(/^﻿/, ''))); } catch (e) {}
  return cfg;
}

function saveConfig(cfg) {
  const out = {
    zaapApi: cfg.zaapApi, zaapPort: cfg.zaapPort, gamePort: cfg.gamePort, gameExe: cfg.gameExe,
    remember: !!cfg.remember, rememberPassword: !!cfg.rememberPassword,
    account: cfg.remember ? cfg.account : '',
    // Simple masquage pour ne pas laisser le mot de passe lisible en clair (ce n'est pas un chiffrement).
    savedPassword: cfg.rememberPassword && cfg.password ? obfuscate(cfg.password) : ''
  };
  try { fs.writeFileSync(CONFIG_FILE, JSON.stringify(out, null, 2)); } catch (e) { log('Impossible d\'enregistrer atrion.json :', e.message); }
}

const MASK = 'atrion-launcher';
function obfuscate(s) {
  const b = Buffer.from(s, 'utf8');
  for (let i = 0; i < b.length; i++) b[i] ^= MASK.charCodeAt(i % MASK.length);
  return b.toString('base64');
}
function deobfuscate(s) {
  try {
    const b = Buffer.from(s, 'base64');
    for (let i = 0; i < b.length; i++) b[i] ^= MASK.charCodeAt(i % MASK.length);
    return b.toString('utf8');
  } catch (e) { return ''; }
}

// ---------- Recuperation du jeton aupres du service Zaap du serveur ----------
function request(url, method, body, timeout) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const data = body ? JSON.stringify(body) : null;
    const headers = data ? { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(data) } : {};
    const req = http.request({ hostname: u.hostname, port: u.port || 80, path: u.pathname, method, headers, timeout: timeout || 8000 }, res => {
      let out = '';
      res.on('data', c => out += c);
      res.on('end', () => resolve({ status: res.statusCode, body: out }));
    });
    req.on('timeout', () => req.destroy(new Error('timeout')));
    req.on('error', reject);
    req.end(data || undefined);
  });
}

let cachedToken = null;
let lastTokenError = '';
async function getGameToken(cfg) {
  lastTokenError = '';
  const md5hex = crypto.createHash('md5').update(cfg.password).digest('hex');
  const hash = crypto.createHash('sha512').update(md5hex).digest('hex');
  try {
    const r = await request(cfg.zaapApi.replace(/\/$/, '') + '/generateAuthToken', 'POST', { account_id: cfg.account, password_hash: hash });
    log('Serveur Atrion ->', r.status);
    if (r.status === 200) {
      const j = JSON.parse(r.body);
      if (j.zaap_token) return j.zaap_token;
    }
    if (r.status === 429) lastTokenError = 'Trop de tentatives, attends une minute.';
    else if (r.status === 401 || r.status === 403 || r.status === 404) lastTokenError = 'Compte ou mot de passe incorrect.';
    else lastTokenError = 'Le serveur a refusé la connexion (code ' + r.status + ').';
  } catch (e) {
    log('Serveur injoignable :', e.message);
    lastTokenError = 'Serveur injoignable. Vérifie qu\'il est allumé.';
  }
  return null;
}

// ---------- Mini serveur Thrift (TBinaryProtocol + TBufferedTransport) ----------
const T = { STOP: 0, BOOL: 2, BYTE: 3, DOUBLE: 4, I16: 6, I32: 8, I64: 10, STRING: 11, STRUCT: 12, MAP: 13, SET: 14, LIST: 15 };
class Underrun extends Error {}

class Reader {
  constructor(buf) { this.b = buf; this.p = 0; }
  need(n) { if (this.p + n > this.b.length) throw new Underrun(); }
  i8() { this.need(1); return this.b.readInt8(this.p++); }
  i16() { this.need(2); const v = this.b.readInt16BE(this.p); this.p += 2; return v; }
  i32() { this.need(4); const v = this.b.readInt32BE(this.p); this.p += 4; return v; }
  i64() { this.need(8); const hi = this.b.readInt32BE(this.p), lo = this.b.readUInt32BE(this.p + 4); this.p += 8; return hi * 4294967296 + lo; }
  dbl() { this.need(8); const v = this.b.readDoubleBE(this.p); this.p += 8; return v; }
  str() { const n = this.i32(); this.need(n); const v = this.b.toString('utf8', this.p, this.p + n); this.p += n; return v; }
  val(t) {
    switch (t) {
      case T.BOOL: return this.i8() !== 0;
      case T.BYTE: return this.i8();
      case T.DOUBLE: return this.dbl();
      case T.I16: return this.i16();
      case T.I32: return this.i32();
      case T.I64: return this.i64();
      case T.STRING: return this.str();
      case T.STRUCT: return this.struct();
      case T.MAP: { const kt = this.i8(), vt = this.i8(), n = this.i32(); const m = {}; for (let i = 0; i < n; i++) m[this.val(kt)] = this.val(vt); return m; }
      case T.SET: case T.LIST: { const et = this.i8(), n = this.i32(); const a = []; for (let i = 0; i < n; i++) a.push(this.val(et)); return a; }
      default: throw new Error('type thrift inconnu ' + t);
    }
  }
  struct() {
    const o = {};
    for (;;) { const t = this.i8(); if (t === T.STOP) return o; const id = this.i16(); o[id] = this.val(t); }
  }
  message() {
    const v = this.i32();
    let name, type, seq;
    if (v < 0) { type = v & 0xff; name = this.str(); seq = this.i32(); }
    else { this.p -= 4; name = this.str(); type = this.i8(); seq = this.i32(); }
    return { name, type, seq, args: this.struct() };
  }
}

function writeReply(name, seq, type, value) {
  const parts = [];
  const i32 = v => { const b = Buffer.alloc(4); b.writeInt32BE(v); parts.push(b); };
  const str = s => { const b = Buffer.from(s, 'utf8'); i32(b.length); parts.push(b); };
  i32((0x80010000 | 2) | 0);
  str(name);
  i32(seq);
  if (type !== null) {
    parts.push(Buffer.from([type, 0, 0])); // champ "success" id 0
    if (type === T.STRING) str(String(value));
    else if (type === T.BOOL) parts.push(Buffer.from([value ? 1 : 0]));
  }
  parts.push(Buffer.from([T.STOP]));
  return Buffer.concat(parts);
}

const SESSION = 'atrion-session-' + crypto.randomBytes(8).toString('hex');
const settings = { language: '"fr"' };

function makeHandlers(cfg) {
  return {
    connect: async a => { log('connect', { game: a[1], release: a[2], instance: a[3] }); return [T.STRING, SESSION]; },
    auth_getGameToken: async () => {
      const tok = cachedToken || await getGameToken(cfg);
      if (tok) { cachedToken = null; log('Jeton de connexion envoye au jeu.'); } else log('!! Impossible d\'obtenir un jeton (voir messages ci-dessus).');
      return [T.STRING, tok || ''];
    },
    auth_getGameTokenWithWindowId: async a => makeHandlers(cfg).auth_getGameToken(a),
    settings_get: async a => { const v = settings[a[2]] !== undefined ? settings[a[2]] : 'null'; log('settings_get', a[2], '->', v); return [T.STRING, v]; },
    settings_set: async a => { log('settings_set', a[2], a[3]); settings[a[2]] = a[3]; return [null]; },
    userInfo_get: async () => [T.STRING, JSON.stringify({ id: 1, login: cfg.account, nickname: cfg.account, isGuest: false })],
    zaapVersion_get: async () => [T.STRING, '3.12.0'],
    zaapMustUpdate_get: async () => [T.BOOL, false],
    updater_isUpdateAvailable: async () => [T.BOOL, false],
    hasGameAccess: async () => [T.BOOL, true],
    hasPremiumAccess: async () => [T.BOOL, true],
    release_restartOnExit: async () => [null],
    release_exitAndRepair: async () => [null],
  };
}

function startZaapServer(cfg) {
  const handlers = makeHandlers(cfg);
  const server = net.createServer(sock => {
    log('Le jeu s\'est connecte au launcher.');
    let buf = Buffer.alloc(0);
    let busy = Promise.resolve();
    sock.on('data', chunk => {
      buf = Buffer.concat([buf, chunk]);
      for (;;) {
        let msg, r = new Reader(buf);
        try { msg = r.message(); } catch (e) { if (e instanceof Underrun) break; log('Message illisible :', e.message); buf = Buffer.alloc(0); break; }
        buf = buf.slice(r.p);
        busy = busy.then(async () => {
          const h = handlers[msg.name];
          if (!h) { log('Appel non gere :', msg.name, msg.args); if (msg.type !== 4) sock.write(writeReply(msg.name, msg.seq, null)); return; }
          try {
            const [type, value] = await h(msg.args);
            if (msg.type !== 4) sock.write(writeReply(msg.name, msg.seq, type, value));
          } catch (e) { log('Erreur dans', msg.name, e.message); }
        });
      }
    });
    sock.on('error', e => log('Socket :', e.message));
    sock.on('close', () => log('Le jeu s\'est deconnecte du launcher.'));
  });
  return new Promise((res, rej) => {
    server.on('error', rej);
    server.listen(cfg.zaapPort, '127.0.0.1', () => { log('Faux launcher a l\'ecoute sur 127.0.0.1:' + cfg.zaapPort); res(server); });
  });
}


// ---------- Lancement du jeu ----------
let game = null;
function launchGame(cfg) {
  const exe = path.join(DIR, cfg.gameExe);
  const hash = crypto.randomBytes(16).toString('hex');
  const env = Object.assign({}, process.env, {
    ZAAP_CAN_AUTH: 'true', ZAAP_GAME: 'retro', ZAAP_RELEASE: 'main', ZAAP_INSTANCE_ID: '1',
    ZAAP_HASH: hash, ZAAP_PORT: String(cfg.zaapPort), ZAAP_LOGS_PATH: path.join(DIR, 'logs')
  });
  delete env.ELECTRON_RUN_AS_NODE; delete env.ATRION_PASSWORD;
  const args = ['--port=' + cfg.zaapPort, '--gameName=retro', '--gameRelease=main', '--instanceId=1', '--gameInstanceKey=' + hash];
  log('Lancement du jeu.');
  const child = spawn(exe, args, { cwd: DIR, env, detached: false, stdio: 'ignore' });
  game = child;
  child.on('exit', code => {
    log('Jeu ferme (code ' + code + ').');
    if (game === child) game = null;
    state.phase = 'ready';
    state.message = 'Jeu fermé. Prêt à relancer.';
    if (!uiAlive) shutdown();
  });
  child.on('error', e => { log('Impossible de lancer le jeu :', e.message); game = null; state.phase = 'error'; state.message = 'Impossible de lancer le jeu : ' + e.message; });
}

// ---------- Etat du serveur ----------
function tcpCheck(host, port, timeout) {
  return new Promise(res => {
    const s = net.connect({ host, port });
    const done = ok => { s.destroy(); res(ok); };
    s.setTimeout(timeout || 2000, () => done(false));
    s.on('connect', () => done(true));
    s.on('error', () => done(false));
  });
}
const server = { online: null, ping: null, checkedAt: 0 };
async function checkServer(cfg) {
  const host = new URL(cfg.zaapApi).hostname;
  const t = Date.now();
  const ok = await tcpCheck(host, cfg.gamePort, 2500);
  server.online = ok;
  server.ping = ok ? Date.now() - t : null;
  server.checkedAt = Date.now();
}

// ---------- Atrion en direct (page atrion-status.php du serveur) ----------
const live = { data: null, at: 0, busy: false };
async function refreshLive(cfg) {
  if (live.busy) return;
  live.busy = true;
  try {
    const host = new URL(cfg.zaapApi).hostname;
    const r = await request('http://' + host + '/atrion-status.php', 'GET', null, 3000);
    if (r.status === 200) { const j = JSON.parse(r.body); if (j && j.ok) live.data = j; }
  } catch (e) { /* serveur web injoignable : on garde les dernieres donnees */ }
  live.at = Date.now();
  live.busy = false;
}

// ---------- Interface ----------
const state = { phase: 'ready', message: '' };
let uiAlive = false, lastPing = Date.now(), uiProcess = null;

function readBody(req) {
  return new Promise(res => { let d = ''; req.on('data', c => d += c); req.on('end', () => { try { res(JSON.parse(d || '{}')); } catch (e) { res({}); } }); });
}
function sendJson(res, obj) { res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' }); res.end(JSON.stringify(obj)); }

async function play(cfg, body) {
  if (state.phase === 'connecting' || game) return;
  cfg.account = String(body.account || '').trim();
  cfg.password = String(body.password || '');
  cfg.remember = !!body.remember;
  cfg.rememberPassword = !!body.rememberPassword;
  if (!cfg.account || !cfg.password) { state.phase = 'error'; state.message = 'Entre ton nom de compte et ton mot de passe.'; return; }
  state.phase = 'connecting'; state.message = 'Connexion au serveur Atrion…';
  log('Compte : ' + cfg.account);
  const token = await getGameToken(cfg);
  if (!token) { state.phase = 'error'; state.message = lastTokenError || 'Connexion impossible.'; return; }
  saveConfig(cfg);
  cachedToken = token;
  state.phase = 'playing'; state.message = 'Bon jeu sur Atrion !';
  launchGame(cfg);
}

function startUiServer(cfg) {
  const srv = http.createServer(async (req, res) => {
    const url = req.url.split('?')[0];
    try {
      if (url === '/' || url === '/index.html') {
        res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
        res.end(fs.readFileSync(UI_FILE));
      } else if (url === '/api/state') {
        lastPing = Date.now(); uiAlive = true;
        if (Date.now() - server.checkedAt > 10000) checkServer(cfg);
        sendJson(res, {
          phase: game ? 'playing' : state.phase, message: state.message,
          account: cfg.account, remember: cfg.remember, rememberPassword: cfg.rememberPassword,
          hasSavedPassword: !!cfg.savedPassword,
          server: server, log: logLines.slice(-60)
        });
      } else if (url === '/api/live') {
        if (Date.now() - live.at > 20000) refreshLive(cfg);
        sendJson(res, live.data || { ok: false });
      } else if (url === '/api/saved-password') {
        sendJson(res, { password: cfg.savedPassword ? deobfuscate(cfg.savedPassword) : '' });
      } else if (url === '/api/play' && req.method === 'POST') {
        const body = await readBody(req);
        play(cfg, body);
        sendJson(res, { ok: true });
      } else if (url === '/api/forget' && req.method === 'POST') {
        cfg.savedPassword = ''; cfg.rememberPassword = false; cfg.account = ''; cfg.remember = false; saveConfig(cfg);
        sendJson(res, { ok: true });
      } else if (url === '/api/quit' && req.method === 'POST') {
        sendJson(res, { ok: true });
        setTimeout(() => { uiAlive = false; if (uiProcess) try { uiProcess.kill(); } catch (e) {} if (!game) shutdown(); }, 200);
      } else {
        res.writeHead(404); res.end();
      }
    } catch (e) { res.writeHead(500); res.end(String(e.message)); }
  });
  return new Promise((resolve, reject) => { srv.on('error', reject); srv.listen(UI_PORT, '127.0.0.1', () => resolve(srv)); });
}

function findBrowser() {
  const pf = [process.env['ProgramFiles(x86)'], process.env.ProgramFiles, process.env.LOCALAPPDATA].filter(Boolean);
  const rel = ['Microsoft\\Edge\\Application\\msedge.exe', 'Google\\Chrome\\Application\\chrome.exe'];
  for (const r of rel) for (const p of pf) { const f = path.join(p, r); if (fs.existsSync(f)) return f; }
  return null;
}

function openUi() {
  const url = 'http://127.0.0.1:' + UI_PORT + '/';
  const browser = findBrowser();
  if (browser) {
    uiProcess = spawn(browser, ['--app=' + url, '--window-size=1000,640', '--user-data-dir=' + path.join(DIR, 'launcher-ui'),
      '--no-first-run', '--no-default-browser-check', '--disable-features=Translate,msEdgeSidebarV2'], { detached: false, stdio: 'ignore' });
    uiProcess.on('exit', () => { uiProcess = null; });
    uiProcess.on('error', e => { log('Fenetre impossible a ouvrir :', e.message); uiProcess = null; });
  } else {
    const c = spawn('cmd', ['/c', 'start', '', url], { detached: true, stdio: 'ignore' });
    c.on('error', e => log('Navigateur introuvable :', e.message));
  }
}

function shutdown() { log('Fermeture du launcher.'); setTimeout(() => process.exit(0), 100); }

// Ferme le launcher quand la fenetre est fermee (plus de nouvelles depuis 8 s) et qu'aucun jeu ne tourne.
setInterval(() => {
  if (uiAlive && Date.now() - lastPing > 8000) { uiAlive = false; if (!game) shutdown(); }
}, 2000);

(async () => {
  try { fs.writeFileSync(LOG_FILE, ''); } catch (e) {}
  const cfg = loadConfig();
  if (cfg.password && !cfg.savedPassword) { cfg.savedPassword = obfuscate(cfg.password); cfg.rememberPassword = true; }
  cfg.password = '';
  log('Atrion Launcher demarre.');
  // Liste des cartes presentes dans le client (sert a eviter les cartes absentes dans le deplacement automatique)
  try {
    const mapsDir = path.join(DIR, 'resources', 'app', 'retroclient', 'data', 'maps');
    fs.writeFileSync(path.join(DIR, 'atrion-maps.txt'), fs.readdirSync(mapsDir).join('\n'));
  } catch (e) { log('Liste des cartes impossible :', e.message); }
  try { await startUiServer(cfg); }
  catch (e) {
    // Un launcher tourne deja : on rouvre juste sa fenetre.
    openUi(); setTimeout(() => process.exit(0), 1500); return;
  }
  try { await startZaapServer(cfg); }
  catch (e) { log('!! Le port ' + cfg.zaapPort + ' est deja pris. Ferme le launcher Ankama ou l\'ancien launcher puis relance.'); state.phase = 'error'; state.message = 'Le port ' + cfg.zaapPort + ' est déjà utilisé : ferme le launcher Ankama ou une ancienne fenêtre du launcher.'; }
  checkServer(cfg);
  if (!process.env.ATRION_NO_UI) openUi();
})();
