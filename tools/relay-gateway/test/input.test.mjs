// SPDX-License-Identifier: MIT
import test from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { performance } from 'node:perf_hooks';
import { setTimeout as delay } from 'node:timers/promises';
import { fixture, deferred, until, rejectedWs, state, nativeHeaders, NATIVE_TOKEN } from '../support.mjs';

test('WebSocket handshake requires Bearer, exact route and native ownership token', async t => {
  const f = await fixture(t);
  assert.equal(await rejectedWs(f.port, { 'X-PBS-Controller': NATIVE_TOKEN }), 401);
  assert.equal(await rejectedWs(f.port, { Authorization: `Bearer ${f.config.bearerToken}` }), 400);
  const headers = { Authorization: `Bearer ${f.config.bearerToken}`, 'X-PBS-Controller': NATIVE_TOKEN };
  assert.equal(await rejectedWs(f.port, headers, '/relay/input?token=unsafe'), 404);
  const connection = await f.ws();
  assert.equal(await rejectedWs(f.port, headers), 409);
  const closed = once(connection.ws, 'close'); connection.ws.close(); await closed;
  await until(() => f.upstream.records.some(record => record.path === '/input/disconnect'));
  assert.equal(f.upstream.records.some(record => record.path === '/input/connect'), false);
});

test('coalesced short taps stay ordered and held for 24 ms after native acceptance', { timeout: 8000 }, async t => {
  const f = await fixture(t, async (_request, response, record) => {
    await delay(4);
    record.acknowledged = performance.now();
    response.end('{"message":"OK"}');
  });
  const { ws, messages } = await f.ws();
  for (const body of [state(1, 1), state(2, 1, 200), state(3, 0), state(4, 2), state(5, 0)]) ws.send(body);
  await until(() => messages.length === 5);
  assert.deepEqual(messages, ['ok,1', 'ok,2', 'ok,3', 'ok,4', 'ok,5']);
  const states = f.upstream.records.filter(record => record.path === '/input/state');
  assert.deepEqual(states.map(record => record.body.toString()), [state(1, 1), state(2, 1, 200), state(3), state(4, 2), state(5)]);
  for (const [before, after] of [[0, 2], [2, 3], [3, 4]]) {
    assert.ok(states[after].arrived - states[before].acknowledged >= 24,
      'A changed digital state must be held after PS5 acknowledgement');
  }
  assert.equal(f.upstream.maximumInFlight, 1);
  const closed = once(ws, 'close'); ws.close(); await closed;
  await until(() => f.upstream.records.some(record => record.path === '/input/disconnect'));
});

test('close discards pending states, waits for in-flight acknowledgement, then neutralizes exact owner', { timeout: 8000 }, async t => {
  const firstReceived = deferred();
  const allowAcknowledgement = deferred();
  let held = false;
  const f = await fixture(t, async (request, response, record) => {
    if (request.url === '/input/state' && !held) {
      held = true; firstReceived.resolve(); await allowAcknowledgement.promise;
    }
    record.acknowledged = performance.now();
    response.end('{"message":"OK"}');
  });
  t.after(() => allowAcknowledgement.resolve());
  const { ws } = await f.ws();
  ws.send(state(50, 1)); ws.send(state(51, 2)); ws.send(state(52, 4));
  await firstReceived.promise;
  const closeEvent = once(ws, 'close'); ws.close(); await closeEvent;
  const redundantDisconnect = f.call('/input/disconnect', { method: 'POST', headers: nativeHeaders });
  await delay(30);
  assert.equal(f.upstream.records.length, 1, 'Cleanup must not overtake the native POST');
  allowAcknowledgement.resolve();
  assert.equal((await redundantDisconnect).status, 200);
  assert.deepEqual(f.upstream.records.map(record => [record.path, record.body.toString()]), [
    ['/input/state', state(50, 1)], ['/input/state', state(51)], ['/input/disconnect', ''],
  ]);
  assert.ok(f.upstream.records[1].arrived - f.upstream.records[0].acknowledged >= 24);
  assert.ok(f.upstream.records.every(record => record.headers['x-pbs-controller'] === NATIVE_TOKEN));
  assert.equal(f.upstream.maximumInFlight, 1);
  assert.equal((await f.call('/input/disconnect', { method: 'POST', headers: nativeHeaders })).status, 200);
  assert.equal(f.upstream.records.length, 3, 'Repeated HTTP cleanup should reuse the completed exact-owner result');
});

test('queue overflow is bounded and discards unsent states before cleanup', { timeout: 8000 }, async t => {
  const firstReceived = deferred();
  const release = deferred();
  let first = true;
  const f = await fixture(t, async (_request, response) => {
    if (first) { first = false; firstReceived.resolve(); await release.promise; }
    response.end('{"message":"OK"}');
  });
  t.after(() => release.resolve());
  const { ws } = await f.ws();
  const closed = once(ws, 'close');
  ws.send(state(1, 1));
  await firstReceived.promise;
  for (let sequence = 2; sequence <= 70; sequence++) ws.send(state(sequence, 1));
  const [code] = await closed;
  assert.equal(code, 1008);
  assert.equal(f.upstream.records.length, 1);
  release.resolve();
  await until(() => f.upstream.records.some(record => record.path === '/input/disconnect'));
  assert.deepEqual(f.upstream.records.map(record => record.body.toString()), [state(1, 1), state(2), '']);
});

test('binary states close without forwarding invalid input', { timeout: 8000 }, async t => {
  const f = await fixture(t);
  const { ws } = await f.ws();
  const closed = once(ws, 'close');
  ws.send(Buffer.from(state(1, 1)));
  assert.equal((await closed)[0], 1003);
  await until(() => f.upstream.records.some(record => record.path === '/input/disconnect'));
  assert.deepEqual(f.upstream.records.map(record => record.body.toString()), [state(0), '']);
});

test('uint32 sequence wrap is accepted; an old or repeated sequence is not', { timeout: 8000 }, async t => {
  const f = await fixture(t);
  const { ws, messages } = await f.ws();
  ws.send(state(0xffffffff)); ws.send(state(0));
  await until(() => messages.length === 2);
  assert.deepEqual(messages, ['ok,4294967295', 'ok,0']);
  const closed = once(ws, 'close'); ws.send(state(0));
  assert.equal((await closed)[0], 1008);
  await until(() => f.upstream.records.some(record => record.path === '/input/disconnect'));
  assert.deepEqual(f.upstream.records.map(record => record.body.toString()), [state(0xffffffff), state(0), state(1), '']);
});
