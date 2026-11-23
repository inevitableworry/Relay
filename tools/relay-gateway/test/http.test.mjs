// SPDX-License-Identifier: MIT
import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { fixture, call, state, nativeHeaders, until, closeServer } from '../support.mjs';

test('health is minimal; all other endpoints require one exact Bearer header', { timeout: 8000 }, async t => {
  const f = await fixture(t);
  const health = await call(f.port, '/health');
  assert.equal(health.status, 200);
  assert.deepEqual(JSON.parse(health.text), { service: 'relay-gateway', version: '1.1.0' });
  for (const path of ['/config.json', '/frames', '/audio', '/stop', '/health?extra=1']) {
    assert.equal((await call(f.port, path)).status, 401, path);
  }
  assert.equal((await call(f.port, '/health', { method: 'POST' })).status, 401);
  assert.equal((await call(f.port, '/config.json', { token: 'incorrect-test-value' })).status, 401);
  assert.equal((await call(f.port, '/config.json', { rawHeaders: [
    'Host', `127.0.0.1:${f.port}`,
    'Authorization', `Bearer ${f.config.bearerToken}`, 'Authorization', `Bearer ${f.config.bearerToken}`,
  ] })).status, 401);
  assert.equal((await f.call('/config.json')).status, 200);
  assert.equal(f.upstream.records.length, 1);
  assert.equal(f.gateway.server.address().address, '127.0.0.1');
});

test('strict route/query allowlist and body validation prevent extra upstream operations', { timeout: 10000 }, async t => {
  const f = await fixture(t);
  const rejected = [
    ['POST', '/stop', 404], ['GET', '/relay/start', 405], ['GET', '/video/keyframe', 405],
    ['GET', '/config.json?x=1', 400], ['GET', '/%63onfig.json', 400],
    ['GET', 'http://127.0.0.1/config.json', 400], ['GET', '//config.json', 400],
    ['POST', '/capture/profile?height=480', 400], ['POST', '/capture/profile?height=1080&height=720', 400],
    ['POST', '/capture/profile?height=1080&bitrate=12000000', 400],
    ['POST', '/capture/profile?height=1080&minqp=20&bitrate=12000000', 400],
    ['POST', '/capture/profile?height=1080&bitrate=999999&minqp=20', 400],
    ['POST', '/capture/profile?height=1080&bitrate=100000001&minqp=20', 400],
    ['POST', '/capture/profile?height=1080&bitrate=1000000&minqp=17', 400],
    ['POST', '/capture/profile?height=1080&bitrate=1000000&minqp=36', 400],
  ];
  for (const [method, path, expected] of rejected) assert.equal((await f.call(path, { method })).status, expected, path);
  assert.equal(f.upstream.records.length, 0);
  for (const height of [720, 1080, 1440, 2160]) {
    assert.equal((await f.call(`/capture/profile?height=${height}`, { method: 'POST' })).status, 200);
  }
  for (const query of ['height=720&bitrate=1000000&minqp=18', 'height=2160&bitrate=100000000&minqp=35']) {
    assert.equal((await f.call(`/capture/profile?${query}`, { method: 'POST' })).status, 200);
  }
  const before = f.upstream.records.length;
  assert.equal((await f.call('/video/keyframe', { method: 'POST', body: 'unexpected' })).status, 400);
  assert.equal((await f.call('/input/state', { method: 'POST', headers: nativeHeaders, body: '1,2,128,128,128,128,0,256' })).status, 400);
  assert.equal((await f.call('/input/state', { method: 'POST', headers: nativeHeaders, body: '4294967296,0,128,128,128,128,0,0' })).status, 400);
  assert.equal((await f.call('/input/state', { method: 'POST', headers: nativeHeaders, body: '1,0,128,128,128,128,0,0\n' })).status, 400);
  assert.equal((await f.call('/input/state', { method: 'POST', headers: nativeHeaders, body: Buffer.from([0xff]) })).status, 400);
  assert.equal((await f.call('/input/state', { method: 'POST', headers: nativeHeaders, body: '1'.repeat(257) })).status, 413);
  assert.equal((await f.call('/input/state', { method: 'POST', headers: { ...nativeHeaders, 'Content-Type': 'application/json' }, body: state(1) })).status, 415);
  assert.equal((await f.call('/input/connect', { method: 'POST' })).status, 400);
  assert.equal(f.upstream.records.length, before);
});

test('only native input headers and text body are forwarded; gateway credentials never reach PS5', async t => {
  const f = await fixture(t);
  const response = await f.call('/input/state', { method: 'POST', body: state(1), headers: {
    ...nativeHeaders, Cookie: 'test-cookie', Origin: 'https://example.invalid', 'X-Extra': 'drop-this', Host: 'example.invalid',
  } });
  assert.equal(response.status, 200);
  const [received] = f.upstream.records;
  for (const name of ['authorization', 'cookie', 'origin', 'x-extra']) assert.equal(received.headers[name], undefined, name);
  assert.equal(received.headers['x-pbs-controller'], nativeHeaders['X-PBS-Controller']);
  assert.equal(received.headers['x-pbs-input'], '1');
  assert.equal(received.headers['content-type'], 'text/plain; charset=us-ascii');
  assert.equal(received.headers.host, `127.0.0.1:${f.config.ps5Port}`);
  assert.equal(received.body.toString(), state(1));
});

test('binary frames and audio pass unchanged; media cancellation closes the native stream', { timeout: 8000 }, async t => {
  const bytes = Buffer.from(Array.from({ length: 4096 }, (_, index) => index % 256));
  let nativeCancelled = false;
  const f = await fixture(t, (request, response) => {
    response.setHeader('Content-Type', 'application/octet-stream');
    if (request.url === '/audio') {
      response.once('close', () => { nativeCancelled = !response.writableEnded; });
      response.write(bytes.subarray(0, 64));
      return;
    }
    for (let i = 0; i < bytes.length; i += 113) response.write(bytes.subarray(i, i + 113));
    response.end();
  });
  const frames = await f.call('/frames');
  assert.equal(frames.status, 200);
  assert.deepEqual(frames.body, bytes);
  await new Promise((resolve, reject) => {
    const request = http.get({ host: '127.0.0.1', port: f.port, path: '/audio', agent: false,
      headers: { Authorization: `Bearer ${f.config.bearerToken}` } }, response => {
      response.once('data', chunk => {
        assert.deepEqual(chunk, bytes.subarray(0, 64));
        response.destroy(); request.destroy(); resolve();
      });
      response.on('error', () => {});
    });
    request.once('error', reject);
  });
  await until(() => nativeCancelled);
});

test('upstream redirects are rejected and never followed', async t => {
  const f = await fixture(t, (_request, response) => {
    response.writeHead(302, { Location: '/stop' }); response.end();
  });
  assert.equal((await f.call('/config.json')).status, 502);
  assert.equal((await f.call('/frames')).status, 502);
  assert.deepEqual(f.upstream.records.map(record => record.path), ['/config.json', '/frames']);
});

test('an explicitly absent service returns 503 so Android can request separately verified startup', { timeout: 10000 }, async t => {
  const f = await fixture(t, undefined, { timeouts: { requestMs: 5000 } });
  await closeServer(f.upstream.server);
  const response = await f.call('/config.json');
  assert.equal(response.status, 503);
  assert.deepEqual(JSON.parse(response.text), { error: 'PS5 service is unavailable' });
  assert.equal(f.upstream.records.length, 0);
});

test('a reset or timeout does not advertise explicit service absence', { timeout: 8000 }, async t => {
  const reset = await fixture(t, (request) => request.socket.destroy());
  assert.equal((await reset.call('/config.json')).status, 502);
  const slow = await fixture(t, () => {}, { timeouts: { requestMs: 150 } });
  assert.equal((await slow.call('/config.json')).status, 504);
});
