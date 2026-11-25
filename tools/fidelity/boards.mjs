#!/usr/bin/env node
// Renders redesign canvas boards in headless Chromium and records, for every element with a
// data-el name, its box in CSS px (= dp), its text and its colours, plus a screenshot of the board.
//
// The boards are Design Component files (.dc.html) that load ./support.js, the Design type's
// runtime. This script serves the canvas folder over a local HTTP server and answers support.js
// with that runtime (artifact-type/dc-runtime.js of the canvas artifact), so the boards render
// exactly as on the canvas, with no network except Google Fonts. Fonts: with --fonts local (the
// default when the folder exists) the Roboto requests are answered with the Roboto files
// Robolectric draws the app with, so both sides use the same faces; --fonts web loads Google Fonts.
//
// No npm dependencies: Chromium is driven over the DevTools protocol with Node's own WebSocket.
//
// A board named Board@<ms> is a frame of a board that animates (the splash's preview, Main): the board with its
// animations paused at that time since they started. Board@reduced is the board with prefers-reduced-motion.
//
// Usage: node boards.mjs --canvas DIR --runtime FILE --out DIR [--boards A,B,Main@240] [--chrome PATH]
//                        [--dsf 2.625] [--fonts local|web] [--roboto DIR]

import { spawn } from 'node:child_process';
import { createServer } from 'node:http';
import { existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync, mkdtempSync, rmSync } from 'node:fs';
import { join, extname, resolve } from 'node:path';
import { tmpdir } from 'node:os';

const DEVICES = { bar: [393, 894], closed: [475, 751], open: [821, 1088] };

function args() {
  const a = {
    canvas: null, runtime: null, out: null, boards: null, dsf: 2.625, fonts: null, roboto: null,
    chrome: process.env.FIDELITY_CHROME
      || `${process.env.HOME}/.cache/ms-playwright/chromium-1217/chrome-linux64/chrome`,
  };
  const v = process.argv.slice(2);
  for (let i = 0; i < v.length; i += 2) a[v[i].replace(/^--/, '')] = v[i + 1];
  if (!a.canvas || !a.out) throw new Error('--canvas and --out are required');
  a.runtime ??= [join(a.canvas, 'support.js'), join(a.canvas, '..', 'artifact-type', 'dc-runtime.js')]
    .find(existsSync);
  if (!a.runtime || !existsSync(a.runtime)) throw new Error('No Design runtime: pass --runtime (dc-runtime.js)');
  a.dsf = Number(a.dsf);
  a.roboto ??= findRobolectricRoboto();
  a.fonts ??= a.roboto ? 'local' : 'web';
  if (a.fonts === 'local' && !a.roboto) throw new Error('--fonts local needs --roboto DIR');
  return a;
}

/** Robolectric unpacks the fonts it draws with into the temp folder on its first native-graphics run. */
function findRobolectricRoboto() {
  const base = join(tmpdir(), 'robolectric-nativeruntime-assets');
  if (!existsSync(base)) return null;
  for (const d of readdirSync(base)) {
    const f = join(base, d, 'fonts');
    if (d.startsWith('fonts-') && existsSync(join(f, 'Roboto-Regular.ttf'))) return f;
  }
  return null;
}

/**
 * The device shape a board is drawn for, from its width in canvas.json; null for block boards.
 * A board may be taller than its device: a scrolling page is drawn at its full length.
 */
export function deviceOf(w, h) {
  for (const [name, [dw, dh]] of Object.entries(DEVICES)) if (dw === w && h >= dh) return name;
  return null;
}

const TYPES = { '.html': 'text/html', '.js': 'text/javascript', '.json': 'application/json', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ttf': 'font/ttf', '.woff2': 'font/woff2' };

function serve(canvas, runtime) {
  const root = resolve(canvas);
  const server = createServer((req, res) => {
    const path = decodeURIComponent(new URL(req.url, 'http://x').pathname);
    const file = path === '/support.js' ? runtime : resolve(root, '.' + path);
    if (!file.startsWith(root) && file !== runtime || !existsSync(file)) { res.writeHead(404); res.end(); return; }
    res.writeHead(200, { 'content-type': TYPES[extname(file)] ?? 'application/octet-stream', 'cache-control': 'no-store' });
    res.end(readFileSync(file));
  });
  return new Promise((ok) => server.listen(0, '127.0.0.1', () => ok(server)));
}

/**
 * @font-face rules for the weights the boards ask for, from Robolectric's static Roboto files. Weight 600 gets the face
 * Robolectric draws it with: its sans-serif family lists Medium before Bold, and Android's closest match takes the
 * first of the two (both 100 away). Without the rule Chromium would take Bold, the heavier one.
 */
function localRobotoCss() {
  const faces = [[300, 'Light'], [400, 'Regular'], [500, 'Medium'], [600, 'Medium'], [700, 'Bold'], [900, 'Black']];
  return faces.map(([w, n]) =>
    `@font-face{font-family:'Roboto';font-style:normal;font-weight:${w};src:url(https://fonts.gstatic.com/local/Roboto-${n}.ttf) format('truetype')}`,
  ).join('\n');
}

class Cdp {
  constructor(ws) { this.ws = ws; this.id = 0; this.wait = new Map(); this.handlers = [];
    ws.onmessage = (m) => {
      const msg = JSON.parse(m.data);
      if (msg.id && this.wait.has(msg.id)) {
        const { ok, fail } = this.wait.get(msg.id); this.wait.delete(msg.id);
        msg.error ? fail(new Error(JSON.stringify(msg.error))) : ok(msg.result);
      } else if (msg.method) for (const h of this.handlers) h(msg);
    };
  }
  send(method, params = {}, sessionId) {
    const id = ++this.id;
    this.ws.send(JSON.stringify({ id, method, params, sessionId }));
    return new Promise((ok, fail) => this.wait.set(id, { ok, fail }));
  }
  on(h) { this.handlers.push(h); }
}

async function launch(chrome) {
  const profile = mkdtempSync(join(tmpdir(), 'fidelity-chrome-'));
  // The renders are compared by DOM boxes and pixels only; software rendering is fine here.
  const proc = spawn(chrome, ['--headless=new', '--remote-debugging-port=0', `--user-data-dir=${profile}`,
    '--no-first-run', '--no-default-browser-check', '--hide-scrollbars', '--font-render-hinting=none',
    '--force-color-profile=srgb', 'about:blank'], { stdio: ['ignore', 'ignore', 'pipe'] });
  const url = await new Promise((ok, fail) => {
    let buf = '';
    proc.stderr.on('data', (d) => { buf += d; const m = /DevTools listening on (ws:\S+)/.exec(buf); if (m) ok(m[1]); });
    proc.on('exit', (c) => fail(new Error(`chrome exited ${c}: ${buf.slice(-500)}`)));
  });
  const ws = new WebSocket(url);
  await new Promise((ok, fail) => { ws.onopen = ok; ws.onerror = fail; });
  return { cdp: new Cdp(ws), close: () => { ws.close(); proc.kill(); rmSync(profile, { recursive: true, force: true }); } };
}

// Runs in the page: every data-el element's box, text and colours, in document order.
const EXTRACT = `(() => {
  const out = [];
  for (const el of document.querySelectorAll('[data-el]')) {
    const r = el.getBoundingClientRect();
    const cs = getComputedStyle(el);
    if (cs.display === 'none' || cs.visibility === 'hidden') continue;
    const text = (el.innerText ?? el.textContent ?? '').replace(/\\s+/g, ' ').trim();
    out.push({
      name: el.getAttribute('data-el'),
      x: +r.left.toFixed(2), y: +r.top.toFixed(2), w: +r.width.toFixed(2), h: +r.height.toFixed(2),
      text: text.slice(0, 200),
      label: el.getAttribute('aria-label'),
      background: cs.backgroundColor, color: cs.color,
    });
  }
  return { elements: out, fonts: document.fonts.status,
    families: [...document.fonts].filter(f => f.status === 'loaded').map(f => f.family + ' ' + f.weight) };
})()`;

// Settled when the data-el count and the font state stay the same for three polls in a row.
const PROBE = `JSON.stringify([document.querySelectorAll('[data-el]').length, document.fonts.status,
  document.querySelectorAll('dc-import:empty').length])`;

/** The board file and the frame of a board name: "Main@240" is Main at 240 ms, "Main@reduced" Main with reduced motion. */
export function frameOf(name) {
  const [file, at] = name.split('@');
  return { file, at: at ?? null };
}

// Runs in the page: every animation paused at AT ms since it started, then two frames drawn.
const PAUSE = (at) => `(async () => {
  for (const a of document.getAnimations()) { a.pause(); a.currentTime = ${Number(at)}; }
  await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)));
  return document.getAnimations().length;
})()`;

async function renderBoard(cdp, session, base, board, [w, h], dsf, out) {
  const { file, at } = frameOf(board);
  await cdp.send('Emulation.setDeviceMetricsOverride', { width: w, height: h, deviceScaleFactor: dsf, mobile: false }, session);
  await cdp.send('Emulation.setEmulatedMedia', {
    features: [{ name: 'prefers-reduced-motion', value: at === 'reduced' ? 'reduce' : 'no-preference' }] }, session);
  await cdp.send('Page.navigate', { url: `${base}/${file}.dc.html` }, session);
  let last = '', same = 0;
  const deadline = Date.now() + 20000;
  while (Date.now() < deadline && same < 3) {
    await new Promise((r) => setTimeout(r, 250));
    const { result } = await cdp.send('Runtime.evaluate', { expression: PROBE, returnByValue: true }, session);
    const v = result.value;
    const [count, fonts] = JSON.parse(v);
    same = v === last && count > 0 && fonts === 'loaded' ? same + 1 : 0;
    last = v;
  }
  if (at !== null && at !== 'reduced') {
    await cdp.send('Runtime.evaluate', { expression: PAUSE(at), awaitPromise: true, returnByValue: true }, session);
  }
  const { result } = await cdp.send('Runtime.evaluate', { expression: EXTRACT, returnByValue: true }, session);
  const shot = await cdp.send('Page.captureScreenshot', {
    format: 'png', clip: { x: 0, y: 0, width: w, height: h, scale: 1 } }, session);
  writeFileSync(join(out, `${board}.png`), Buffer.from(shot.data, 'base64'));
  return { board, size: [w, h], dsf, settled: same >= 3, ...result.value };
}

async function main() {
  const a = args();
  const canvas = JSON.parse(readFileSync(join(a.canvas, 'canvas.json'), 'utf8'));
  const wanted = a.boards ? new Set(a.boards.split(',').map((b) => b.replace(/\.dc\.html$/, ''))) : null;
  const boards = Object.entries(canvas.boards)
    .map(([file, b]) => ({ name: file.replace(/\.dc\.html$/, ''), device: deviceOf(b.w, b.h), size: [b.w, b.h], title: b.title }))
    .filter((b) => b.device && (!wanted || wanted.has(b.name)));
  // Frames of an animating board are asked for by name only; they keep the board's own size.
  for (const name of wanted ?? []) {
    const b = name.includes('@') && canvas.boards[`${frameOf(name).file}.dc.html`];
    if (b) boards.push({ name, device: null, size: [b.w, b.h], title: b.title });
  }
  mkdirSync(a.out, { recursive: true });

  const server = await serve(a.canvas, resolve(a.runtime));
  const base = `http://127.0.0.1:${server.address().port}`;
  const { cdp, close } = await launch(a.chrome);
  try {
    const { targetId } = await cdp.send('Target.createTarget', { url: 'about:blank' });
    const { sessionId } = await cdp.send('Target.attachToTarget', { targetId, flatten: true });
    await cdp.send('Page.enable', {}, sessionId);
    await cdp.send('Runtime.enable', {}, sessionId);
    if (a.fonts === 'local') {
      await cdp.send('Fetch.enable', { patterns: [{ urlPattern: 'https://fonts.googleapis.com/*' },
        { urlPattern: 'https://fonts.gstatic.com/*' }] }, sessionId);
      cdp.on(async (m) => {
        if (m.method !== 'Fetch.requestPaused' || m.sessionId !== sessionId) return;
        const { requestId, request } = m.params;
        const font = /\/local\/(Roboto-\w+\.ttf)$/.exec(request.url);
        const body = font ? readFileSync(join(a.roboto, font[1])) : Buffer.from(localRobotoCss());
        await cdp.send('Fetch.fulfillRequest', { requestId, responseCode: 200, body: body.toString('base64'),
          responseHeaders: [{ name: 'content-type', value: font ? 'font/ttf' : 'text/css' },
            { name: 'access-control-allow-origin', value: '*' }] }, sessionId);
      });
    }
    const index = [];
    for (const b of boards) {
      const r = await renderBoard(cdp, sessionId, base, b.name, b.size, a.dsf, a.out);
      const rec = { ...r, device: b.device, title: b.title, fontSource: a.fonts };
      writeFileSync(join(a.out, `${b.name}.json`), JSON.stringify(rec, null, 1));
      index.push({ board: b.name, device: b.device, elements: r.elements.length, settled: r.settled });
      process.stderr.write(`${b.name} (${b.device}): ${r.elements.length} elements${r.settled ? '' : ' [not settled]'}\n`);
    }
    writeFileSync(join(a.out, 'index.json'), JSON.stringify({ fonts: a.fonts, roboto: a.roboto, dsf: a.dsf, boards: index }, null, 1));
  } finally {
    close();
    server.close();
  }
}

if (import.meta.url === `file://${process.argv[1]}`) {
  main().catch((e) => { console.error(e); process.exit(1); });
}
