// SPDX-License-Identifier: MIT
// Local test fixtures only. The CLI never imports this module.
import http from 'node:http';
import { randomBytes } from 'node:crypto';
import { performance } from 'node:perf_hooks';
import { setTimeout as delay } from 'node:timers/promises';
import { once } from 'node:events';
import { WebSocket } from 'ws';
import { createGateway } from './gateway.mjs';

export const NATIVE_TOKEN = '00112233445566778899aabbccddeeff';
export const state = (sequence, buttons = 0, lx = 128) => `${sequence},${buttons},${lx},128,128,128,0,0`;
export const nativeHeaders = { 'X-PBS-Input': '1', 'X-PBS-Controller': NATIVE_TOKEN, 'Content-Type': 'text/plain' };
export const validConfig = { platform: 'PS5', width: 1920, height: 1080, fps: 30, codec: 'h264' };

export function deferred() {
  let resolve;
  const promise = new Promise(done => { resolve = done; });
  return { promise, resolve };
}

export async function until(predicate, timeoutMs = 3000) {
  const deadline = performance.now() + timeoutMs;
  while (!predicate()) {
    if (performance.now() >= deadline) throw new Error('Test condition timed out');
    await delay(5);
  }
}

export function listen(server, port = 0) {
  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, '127.0.0.1', () => { server.removeListener('error', reject); resolve(server.address().port); });
  });
}

export function closeServer(server) {
  server.closeAllConnections?.();
  return new Promise(resolve => server.close(() => resolve()));
}

export function makeNative(handler) {
  const records = [];
  let inFlight = 0;
  let maximumInFlight = 0;
  const server = http.createServer(async (request, response) => {
    const chunks = [];
    for await (const chunk of request) chunks.push(chunk);
    const record = { path: request.url, method: request.method, headers: request.headers,
      body: Buffer.concat(chunks), arrived: performance.now() };
    records.push(record);
    maximumInFlight = Math.max(maximumInFlight, ++inFlight);
    try {
      if (handler) await handler(request, response, record);
      else {
        response.setHeader('Content-Type', 'application/json');
        response.end(JSON.stringify(request.url === '/config.json' ? validConfig : { message: 'OK' }));
      }
    } catch { response.destroy(); }
    finally { inFlight--; }
  });
  return { server, records, get maximumInFlight() { return maximumInFlight; } };
}

export async function fixture(t, handler, extra = {}, gatewayOptions = {}) {
  const upstream = makeNative(handler);
  const nativePort = await listen(upstream.server);
  const config = { ps5Host: '127.0.0.1', ps5Port: nativePort, bearerToken: randomBytes(32).toString('hex'),
    timeouts: { requestMs: 1000, probeMs: 4000, uploadMs: 1000, readyMs: 200 }, ...extra };
  const gateway = createGateway(config, gatewayOptions);
  const address = await gateway.listen(0);
  t.after(async () => { await gateway.close(); await closeServer(upstream.server); });
  return { gateway, port: address.port, config, upstream,
    call: (path, options) => call(address.port, path, { token: config.bearerToken, ...options }),
    ws: options => connectWs(address.port, config.bearerToken, options) };
}

export function call(port, path, { token, method = 'GET', body = '', headers = {}, rawHeaders, timeoutMs = 30000 } = {}) {
  return new Promise((resolve, reject) => {
    const allHeaders = { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...headers };
    const bytes = Buffer.isBuffer(body) ? body : Buffer.from(body);
    if (bytes.length) allHeaders['Content-Length'] = bytes.length;
    const request = http.request({ host: '127.0.0.1', port, method, path,
      headers: rawHeaders ?? allHeaders, agent: false }, response => {
      const chunks = [];
      response.on('data', chunk => chunks.push(chunk));
      response.once('end', () => resolve({ status: response.statusCode, headers: response.headers,
        body: Buffer.concat(chunks), text: Buffer.concat(chunks).toString('utf8') }));
      response.once('error', reject);
    });
    request.setTimeout(timeoutMs, () => request.destroy(new Error('Test HTTP request timed out')));
    request.once('error', reject);
    request.end(bytes);
  });
}

export async function connectWs(port, bearer, { token = NATIVE_TOKEN, path = '/relay/input' } = {}) {
  const ws = new WebSocket(`ws://127.0.0.1:${port}${path}`, {
    headers: { Authorization: `Bearer ${bearer}`, 'X-PBS-Controller': token }, perMessageDeflate: false,
  });
  ws.on('error', () => {});
  const messages = [];
  ws.on('message', data => messages.push(data.toString()));
  await once(ws, 'open');
  return { ws, messages };
}

export function rejectedWs(port, headers, path = '/relay/input') {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`ws://127.0.0.1:${port}${path}`, { headers });
    ws.on('error', () => {});
    ws.once('open', () => { ws.terminate(); reject(new Error('Unexpected WebSocket upgrade')); });
    ws.once('unexpected-response', (_request, response) => {
      response.resume();
      response.once('end', () => { ws.terminate(); resolve(response.statusCode); });
    });
  });
}
