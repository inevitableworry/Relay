// SPDX-License-Identifier: MIT
import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { EventEmitter, once } from 'node:events';
import { performance } from 'node:perf_hooks';
import { setTimeout as delay } from 'node:timers/promises';
import { ControllerLease, OWNER_ATTACH_MS } from '../controller-lease.mjs';
import { InputSession } from '../input-session.mjs';
import { createDiagnostics } from '../diagnostics.mjs';
import { fixture, deferred, until, state, nativeHeaders, NATIVE_TOKEN } from '../support.mjs';

test('lost native-connect HTTP reply releases only the returned owner without another acquisition', { timeout: 8000 }, async t => {
  const connected = deferred(), release = deferred();
  t.after(() => release.resolve());
  const f = await fixture(t, async (request, response) => {
    if (request.url === '/input/connect') {
      connected.resolve(); await release.promise;
      response.end(JSON.stringify({ token: NATIVE_TOKEN }));
    } else response.end('{"message":"OK"}');
  });
  const request = http.request({ host: '127.0.0.1', port: f.port, method: 'POST', path: '/input/connect',
    headers: { Authorization: `Bearer ${f.config.bearerToken}`, 'X-PBS-Input': '1', 'Content-Length': 0 } });
  request.on('error', () => {}); request.end();
  await connected.promise;
  request.destroy(); await delay(20); release.resolve();
  await until(() => f.upstream.records.some(record => record.path === '/input/disconnect'));
  assert.deepEqual(f.upstream.records.map(record => record.path), ['/input/connect', '/input/disconnect']);
  assert.equal(f.upstream.records[1].headers['x-pbs-controller'], NATIVE_TOKEN);
});

test('slow WSS attachment keeps a neutral exact owner alive beyond native two-second expiry', { timeout: 8000 }, async t => {
  let lastNativeAt = 0, lastSequence = -1;
  const f = await fixture(t, (request, response, record) => {
    if (request.url === '/input/connect') {
      lastNativeAt = performance.now(); response.end(JSON.stringify({ token: NATIVE_TOKEN })); return;
    }
    if (request.url === '/input/state') {
      const fields = record.body.toString().split(',').map(Number);
      assert.ok(performance.now() - lastNativeAt < 2000, 'Native ownership must not expire while the mobile client upgrades');
      assert.ok(fields[0] > lastSequence, 'Neutral preparation and client input share increasing native sequence');
      lastNativeAt = performance.now(); lastSequence = fields[0];
    }
    response.end('{"message":"OK"}');
  });
  assert.equal((await f.call('/input/connect', { method: 'POST', headers: { 'X-PBS-Input': '1' } })).status, 200);
  await delay(2250);
  const beforeAttach = f.upstream.records.filter(record => record.path === '/input/state');
  assert.ok(beforeAttach.length >= 3);
  assert.ok(beforeAttach.every(record => record.body.toString().split(',').slice(1).join(',') === '0,128,128,128,128,0,0'));
  const { ws, messages } = await f.ws();
  ws.send(state(1, 1)); ws.send(state(2));
  await until(() => messages.length === 2);
  assert.deepEqual(messages, ['ok,1', 'ok,2'], 'Acknowledgements retain the client sequence, after actual native acceptance');
  const closed = once(ws, 'close'); ws.close(); await closed;
  await until(() => f.upstream.records.some(record => record.path === '/input/disconnect'));
  assert.equal(f.upstream.records.filter(record => record.path === '/input/connect').length, 1);
  assert.ok(f.upstream.records.filter(record => record.path !== '/input/connect').every(record => record.headers['x-pbs-controller'] === NATIVE_TOKEN));
  assert.equal(f.upstream.maximumInFlight, 1);
});

test('pending-owner expiry is bounded, idempotent and cannot claim another token', { timeout: 5000 }, async t => {
  const f = await fixture(t);
  const ended = [];
  const lease = new ControllerLease(f.config, NATIVE_TOKEN, (owner, result) => ended.push([owner, result]));
  lease.expire(lease.expiresAt - 1);
  assert.equal(lease.closing, false);
  lease.expire(lease.expiresAt + 1);
  await lease.done;
  assert.equal(OWNER_ATTACH_MS, 15000);
  assert.equal((await lease.close()).status, 200);
  assert.throws(() => lease.attach(), /closing/);
  assert.equal(ended.length, 1);
  assert.deepEqual(f.upstream.records.map(record => record.path), ['/input/disconnect']);
  assert.equal(f.upstream.records[0].headers['x-pbs-controller'], NATIVE_TOKEN);
});

test('shutdown waits for an in-flight native acquisition and its exact-owner cleanup', { timeout: 8000 }, async t => {
  const acquired = deferred(), release = deferred();
  t.after(() => release.resolve());
  const f = await fixture(t, async (request, response) => {
    if (request.url === '/input/connect') {
      acquired.resolve(); await release.promise; response.end(JSON.stringify({ token: NATIVE_TOKEN }));
    } else response.end('{"message":"OK"}');
  });
  const request = f.call('/input/connect', { method: 'POST', headers: { 'X-PBS-Input': '1' } }).catch(() => null);
  await acquired.promise;
  let stopped = false;
  const stopping = f.gateway.close().then(() => { stopped = true; });
  await delay(30); assert.equal(stopped, false);
  release.resolve(); await stopping; await request;
  assert.deepEqual(f.upstream.records.map(record => record.path), ['/input/connect', '/input/disconnect']);
  assert.equal(f.upstream.records[1].headers['x-pbs-controller'], NATIVE_TOKEN);
});

test('failed WebSocket validation releases a prepared exact lease without waiting fifteen seconds', { timeout: 8000 }, async t => {
  const f = await fixture(t, (request, response) => {
    response.end(request.url === '/input/connect' ? JSON.stringify({ token: NATIVE_TOKEN }) : '{"message":"OK"}');
  });
  await f.call('/input/connect', { method: 'POST', headers: { 'X-PBS-Input': '1' } });
  const status = await new Promise((resolve, reject) => {
    const request = http.request({ host: '127.0.0.1', port: f.port, path: '/relay/input', agent: false, headers: {
      Authorization: `Bearer ${f.config.bearerToken}`, 'X-PBS-Controller': NATIVE_TOKEN,
      Connection: 'Upgrade', Upgrade: 'websocket', 'Sec-WebSocket-Key': 'invalid', 'Sec-WebSocket-Version': '13',
    } }, response => { response.resume(); response.once('end', () => resolve(response.statusCode)); });
    request.on('error', reject); request.end();
  });
  assert.equal(status, 400);
  await until(() => f.upstream.records.some(record => record.path === '/input/disconnect'), 1000);
  assert.equal(f.upstream.records.filter(record => record.path === '/input/connect').length, 1);
  assert.equal(f.upstream.records.filter(record => record.path === '/input/disconnect').length, 1);
  assert.ok(f.upstream.records.filter(record => record.path !== '/input/connect').every(record => record.headers['x-pbs-controller'] === NATIVE_TOKEN));
});

test('an uppercase upgrade header uses the actual acquired native token spelling', { timeout: 8000 }, async t => {
  const f = await fixture(t, (request, response) => {
    response.end(request.url === '/input/connect' ? JSON.stringify({ token: NATIVE_TOKEN }) : '{"message":"OK"}');
  });
  await f.call('/input/connect', { method: 'POST', headers: { 'X-PBS-Input': '1' } });
  const { ws, messages } = await f.ws({ token: NATIVE_TOKEN.toUpperCase() });
  ws.send(state(1, 1)); await until(() => messages.length === 1);
  assert.equal(messages[0], 'ok,1');
  const closed = once(ws, 'close'); ws.close(); await closed;
  await until(() => f.upstream.records.some(record => record.path === '/input/disconnect'));
  assert.ok(f.upstream.records.filter(record => record.path !== '/input/connect').every(record => record.headers['x-pbs-controller'] === NATIVE_TOKEN));
});

test('in-flight acquisitions reserve the bounded controller capacity', { timeout: 8000 }, async t => {
  const release = deferred();
  t.after(() => release.resolve());
  const f = await fixture(t, async (_request, response) => {
    await release.promise; response.writeHead(409); response.end('{"error":"busy"}');
  });
  const requests = Array.from({ length: 4 }, () => f.call('/input/connect', { method: 'POST', headers: { 'X-PBS-Input': '1' } }));
  await until(() => f.upstream.records.length === 4);
  const extra = await f.call('/input/connect', { method: 'POST', headers: { 'X-PBS-Input': '1' } });
  assert.equal(extra.status, 503); assert.equal(f.upstream.records.length, 4);
  release.resolve(); await Promise.all(requests);
});

test('lease cleanup and attachment cannot overtake an in-flight neutral preparation POST', { timeout: 8000 }, async t => {
  const received = deferred(), release = deferred();
  t.after(() => release.resolve());
  const f = await fixture(t, async (request, response) => {
    if (request.url === '/input/state') { received.resolve(); await release.promise; }
    response.end('{"message":"OK"}');
  });
  const lease = new ControllerLease(f.config, NATIVE_TOKEN, () => {});
  lease.keepAlive(); await received.promise;
  let settled = false;
  const attach = lease.prepareAttach().then(() => { settled = true; }, () => { settled = true; });
  lease.expire(lease.expiresAt + 1);
  await delay(30);
  assert.equal(settled, false);
  assert.equal(f.upstream.records.length, 1);
  release.resolve(); await attach; await lease.done;
  assert.deepEqual(f.upstream.records.map(record => record.path), ['/input/state', '/input/disconnect']);
  assert.equal(f.upstream.maximumInFlight, 1);
});

class TestSocket extends EventEmitter {
  readyState = 1; bufferedAmount = 0; pings = 0; messages = [];
  close() { this.readyState = 2; }
  terminate() { this.readyState = 3; this.emit('close'); }
  ping(_data, _mask, callback) { this.pings++; callback?.(); }
  send(message, callback) { this.messages.push(message); callback?.(); }
}

test('silent mobile socket is terminated and only its exact owner is neutralized/disconnected', { timeout: 5000 }, async t => {
  const f = await fixture(t);
  const socket = new TestSocket();
  const session = new InputSession(f.config, NATIVE_TOKEN, socket, () => {});
  session.heartbeat(session.lastHeardAt + 14999);
  assert.equal(session.closing, false); assert.equal(socket.pings, 1);
  session.heartbeat(session.lastHeardAt + 15000);
  await session.done;
  assert.equal(socket.readyState, 3);
  assert.deepEqual(f.upstream.records.map(record => record.path), ['/input/state', '/input/disconnect']);
  assert.ok(f.upstream.records.every(record => record.headers['x-pbs-controller'] === NATIVE_TOKEN));
});

test('old queued presses are discarded after a native stall instead of replayed', { timeout: 8000 }, async t => {
  const received = deferred(), release = deferred();
  t.after(() => release.resolve());
  let first = true;
  const f = await fixture(t, async (_request, response) => {
    if (first) { first = false; received.resolve(); await release.promise; }
    response.end('{"message":"OK"}');
  }, { timeouts: { requestMs: 3000 } });
  const { ws } = await f.ws();
  const closed = once(ws, 'close');
  ws.send(state(1, 1)); await received.promise;
  ws.send(state(2, 2)); ws.send(state(3, 4));
  await delay(1050); release.resolve(); await closed;
  await until(() => f.upstream.records.some(record => record.path === '/input/disconnect'));
  assert.deepEqual(f.upstream.records.map(record => record.body.toString()), [state(1, 1), state(2), '']);
});

test('diagnostics are allowlisted, credential-free, bounded and cannot break streaming', () => {
  let now = 0;
  const entries = [];
  const emit = createDiagnostics(entry => entries.push(entry), () => now);
  emit('input_closed', { id: 1, reason: 'socket_error', token: NATIVE_TOKEN, body: state(1), route: 'https://secret.invalid', message: 'private' });
  assert.deepEqual(entries[0], { event: 'input_closed', id: 1, reason: 'socket_error' });
  for (let i = 0; i < 200; i++) emit('media_closed', { id: i, reason: NATIVE_TOKEN });
  assert.equal(entries.length, 120);
  now = 60001; emit('media_closed', { route: '/frames', durationMs: 33, reason: 'downstream_closed' });
  assert.equal(entries[120].event, 'diagnostics_suppressed');
  assert.ok(!JSON.stringify(entries).includes(NATIVE_TOKEN));
  assert.doesNotThrow(() => createDiagnostics(() => { throw new Error('sink failed'); })('input_opened'));
});

test('media diagnostic distinguishes a caller cancellation from an upstream reset', { timeout: 8000 }, async t => {
  const entries = [];
  const f = await fixture(t, (request, response) => {
    if (request.url === '/frames') { response.socket.destroy(); return; }
    response.setHeader('Content-Type', 'application/octet-stream'); response.write(Buffer.from([1, 2, 3]));
  }, {}, { onDiagnostic: entry => entries.push(entry) });
  assert.equal((await f.call('/frames')).status, 502);
  await new Promise((resolve, reject) => {
    const request = http.get({ host: '127.0.0.1', port: f.port, path: '/audio', agent: false,
      headers: { Authorization: `Bearer ${f.config.bearerToken}` } }, response => {
      response.on('error', () => {});
      response.once('data', () => { response.destroy(); request.destroy(); resolve(); });
    });
    request.on('error', reject);
  });
  await until(() => entries.filter(entry => entry.event === 'media_closed').length === 2);
  assert.equal(entries.find(entry => entry.event === 'media_closed' && entry.route === '/frames').reason, 'upstream_error');
  assert.equal(entries.find(entry => entry.event === 'media_closed' && entry.route === '/audio').reason, 'downstream_closed');
});
