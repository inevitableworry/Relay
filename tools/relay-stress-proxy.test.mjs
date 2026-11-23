// SPDX-License-Identifier: MIT
// Only ephemeral loopback services are used. No ADB or real PS5 requests.
import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { once } from 'node:events';
import { performance } from 'node:perf_hooks';
import { setTimeout as delay } from 'node:timers/promises';
import { createStressProxy, validateStressConfig, LIMITS } from './relay-stress-proxy.mjs';

const TOKEN = '00112233445566778899aabbccddeeff';
const body = Buffer.from('10,1,128,128,128,128,0,0');
const controls = { 'X-PBS-Input': '1', 'X-PBS-Controller': TOKEN, 'Content-Type': 'text/plain' };

function listen(server) {
  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => { server.removeListener('error', reject); resolve(server.address().port); });
  });
}

async function fixture(t, config = {}, handler) {
  const records = [];
  const native = http.createServer(async (request, response) => {
    const chunks = [];
    for await (const chunk of request) chunks.push(chunk);
    const record = { method: request.method, path: request.url, headers: request.headers,
      body: Buffer.concat(chunks), arrived: performance.now() };
    records.push(record);
    try {
      if (handler) await handler(request, response, record);
      else { response.setHeader('Content-Type', 'application/json'); response.end('{"message":"OK"}'); }
    } catch { response.destroy(); }
  });
  const nativePort = await listen(native);
  const proxy = createStressProxy({ upstreamHost: '127.0.0.1', upstreamPort: nativePort, listenPort: 0, runSeconds: 10, ...config });
  const address = await proxy.listen();
  t.after(async () => {
    await proxy.close();
    native.closeAllConnections();
    await new Promise(resolve => native.close(resolve));
  });
  return { proxy, native, records, port: address.port };
}

function get(port, path = '/frames', { method = 'GET', headers = {}, data = Buffer.alloc(0) } = {}) {
  return new Promise((resolve, reject) => {
    const request = http.request({ hostname: '127.0.0.1', port, path, method, agent: false,
      headers: { ...headers, ...(data.length ? { 'Content-Length': data.length } : {}) } }, response => {
      const chunks = [];
      let firstByteAt = null;
      response.on('data', chunk => { firstByteAt ??= performance.now(); chunks.push(chunk); });
      response.once('error', reject);
      response.once('end', () => resolve({ status: response.statusCode, body: Buffer.concat(chunks),
        headers: response.headers, firstByteAt, endedAt: performance.now() }));
    });
    request.once('error', reject);
    request.setTimeout(8000, () => request.destroy(new Error('Test response timed out')));
    request.end(data);
  });
}

async function until(predicate, timeout = 3000) {
  const deadline = performance.now() + timeout;
  while (!predicate()) {
    if (performance.now() > deadline) throw new Error('Test condition timed out');
    await delay(5);
  }
}

test('upstream and schedule validation forbid public targets, URL routing and bind overrides', () => {
  for (const upstreamHost of ['0.0.0.0', '8.8.8.8', 'example.com', 'http://192.168.1.1/', '127.0.0.2', '::1']) {
    assert.throws(() => validateStressConfig({ upstreamHost }));
  }
  for (const upstreamHost of ['10.0.0.1', '172.16.5.3', '192.168.1.99', '127.0.0.1']) {
    assert.equal(validateStressConfig({ upstreamHost }).upstreamHost, upstreamHost);
  }
  assert.throws(() => validateStressConfig({ upstreamHost: '127.0.0.1', bindHost: '0.0.0.0' }));
  assert.throws(() => validateStressConfig({ upstreamHost: '127.0.0.1', upstreamPort: 37842 }));
  assert.throws(() => validateStressConfig({ upstreamHost: '127.0.0.1', videoMbps: .01 }));
  assert.throws(() => validateStressConfig({ upstreamHost: '127.0.0.1', schedule: [{ afterSeconds: 1, videoMbps: 10 }] }));
  assert.throws(() => validateStressConfig({ upstreamHost: '127.0.0.1', runSeconds: 1801 }));
  assert.throws(() => validateStressConfig({ upstreamHost: '127.0.0.1', videoDelayMs: 5001 }));
  assert.throws(() => validateStressConfig({ upstreamHost: '127.0.0.1', statusDropEvery: 1.5 }));
  assert.throws(() => validateStressConfig({ upstreamHost: '127.0.0.1', runSeconds: 5, cutVideoAfterSeconds: 6 }));
});

test('allowlist, body validation and header stripping preserve only the native protocol', async t => {
  const f = await fixture(t);
  assert.equal(f.proxy.server.address().address, '127.0.0.1');
  const health = JSON.parse((await get(f.port, '/health')).body);
  assert.equal(health.developmentOnly, true);
  assert.equal(f.records.length, 0);
  for (const target of ['/stop', '/relay/start', '/relay/input', '/config.json?next=/stop', '/%73top', 'http://example.invalid/config.json']) {
    assert.ok((await get(f.port, target, { method: target === '/stop' || target === '/relay/start' ? 'POST' : 'GET' })).status >= 400);
  }
  assert.equal((await get(f.port, '/capture/profile?height=1080&bitrate=12000000', { method: 'POST' })).status, 400);
  assert.equal((await get(f.port, '/input/state', { method: 'POST', headers: controls, data: Buffer.from('bad') })).status, 400);
  assert.equal(f.records.length, 0);
  assert.equal((await get(f.port, '/input/state', { method: 'POST', data: body, headers: {
    ...controls, Authorization: 'Bearer test-only-value', Cookie: 'test-cookie', 'X-Unrelated': 'discard',
  } })).status, 200);
  assert.equal(f.records.length, 1);
  assert.equal(Buffer.compare(f.records[0].body, body), 0);
  assert.equal(f.records[0].headers['x-pbs-controller'], TOKEN);
  for (const header of ['authorization', 'cookie', 'x-unrelated']) assert.equal(f.records[0].headers[header], undefined);
  await f.proxy.close();
  assert.equal(f.records.length, 1, 'Closing the proxy must not invent controller cleanup requests');
});

test('finite binary media is exact and two video responses share one measured bandwidth cap', { timeout: 6000 }, async t => {
  const bytes = Buffer.from(Array.from({ length: 65536 }, (_, index) => index % 256));
  const f = await fixture(t, { videoMbps: 1 }, (_request, response) => {
    response.writeHead(200, { 'Content-Type': 'application/octet-stream', 'Content-Length': bytes.length });
    response.end(bytes);
  });
  const started = performance.now();
  const [first, second] = await Promise.all([get(f.port), get(f.port)]);
  const duration = Math.max(first.endedAt, second.endedAt) - started;
  assert.equal(first.status, 200);
  assert.equal(second.status, 200);
  assert.equal(Buffer.compare(first.body, bytes), 0);
  assert.equal(Buffer.compare(second.body, bytes), 0);
  assert.ok(duration >= 950, `131072 bytes at a shared 1 Mbps cap took only ${duration}ms`);
  assert.equal(f.proxy.snapshot().stats.videoBytesEmitted, bytes.length * 2);
  await until(() => f.proxy.snapshot().stats.activeVideoStreams === 0);
  assert.equal(f.proxy.snapshot().stats.activeQueuedBytes, 0);
});

test('video response delay does not delay audio, status or profile requests or change their bytes', { timeout: 6000 }, async t => {
  const bytes = Buffer.from(Array.from({ length: 32768 }, (_, index) => (index * 17) % 256));
  const configJson = Buffer.from('{"platform":"PS5","width":1920,"height":1080,"fps":60,"resolutions":[720,1080]}');
  const f = await fixture(t, { videoDelayMs: 300 }, (request, response) => {
    const media = request.url === '/audio' || request.url === '/frames';
    response.setHeader('Content-Type', media ? 'application/octet-stream' : 'application/json');
    response.end(media ? bytes : configJson);
  });
  const started = performance.now();
  const videoPending = get(f.port);
  const [audio, status, profile] = await Promise.all([
    get(f.port, '/audio'), get(f.port, '/status.json'),
    get(f.port, '/capture/profile?height=720&bitrate=12000000&minqp=22', { method: 'POST' }),
  ]);
  const video = await videoPending;
  assert.equal(Buffer.compare(video.body, bytes), 0);
  assert.equal(Buffer.compare(audio.body, bytes), 0);
  assert.equal(Buffer.compare(status.body, configJson), 0);
  assert.equal(Buffer.compare(profile.body, configJson), 0);
  assert.ok(video.firstByteAt - started >= 275);
  assert.ok(audio.endedAt < video.firstByteAt);
  assert.ok(status.endedAt < video.firstByteAt);
  assert.ok(profile.endedAt < video.firstByteAt);
  assert.equal(f.proxy.snapshot().stats.profileRequests[0].upstreamStatus, 200);
});

test('backpressure bounds the delay queue and disconnect cancels the real upstream stream', { timeout: 6000 }, async t => {
  let cancelled = false;
  const f = await fixture(t, { videoMbps: 1, videoDelayMs: 250 }, async (_request, response) => {
    response.setHeader('Content-Type', 'application/octet-stream');
    response.once('close', () => { cancelled = !response.writableEnded; });
    const chunk = Buffer.alloc(65536, 0x75);
    for (let index = 0; index < 4096 && !response.destroyed; index++) {
      if (!response.write(chunk)) await new Promise(resolve => {
        const finish = () => { response.removeListener('drain', finish); response.removeListener('close', finish); resolve(); };
        response.once('drain', finish); response.once('close', finish);
      });
    }
    if (!response.destroyed) response.end();
  });
  await new Promise((resolve, reject) => {
    const request = http.get({ hostname: '127.0.0.1', port: f.port, path: '/frames', agent: false }, response => {
      response.once('data', () => { response.destroy(); request.destroy(); resolve(); });
      response.on('error', () => {});
    });
    request.once('error', reject);
  });
  await until(() => cancelled && f.proxy.snapshot().stats.activeVideoStreams === 0);
  const stats = f.proxy.snapshot().stats;
  assert.ok(stats.maxQueuedBytesPerStream > 0);
  assert.ok(stats.maxQueuedBytesPerStream <= LIMITS.queueBytes);
  assert.ok(stats.maxQueuedChunksPerStream <= LIMITS.queueChunks);
  assert.equal(stats.activeQueuedBytes, 0);
});

test('scenario timing survives reconnect and reaches its recovery phase without an admin mutation', { timeout: 6000 }, async t => {
  const bytes = Buffer.alloc(65536, 0x42);
  const f = await fixture(t, { schedule: [
    { afterSeconds: 0, videoMbps: 0, videoDelayMs: 0 },
    { afterSeconds: .1, videoMbps: 1, videoDelayMs: 0 },
    { afterSeconds: 1.5, videoMbps: 0, videoDelayMs: 0 },
  ] }, (_request, response) => { response.setHeader('Content-Type', 'application/octet-stream'); response.end(bytes); });
  assert.equal(f.proxy.snapshot().scenarioStarted, false);
  await get(f.port);
  await delay(150);
  assert.equal(f.proxy.snapshot().currentPhase.index, 1);
  const started = performance.now();
  const second = await get(f.port);
  assert.ok(second.endedAt - started >= 220, 'Reconnecting must not restart the unlimited opening phase');
  assert.equal(f.proxy.snapshot().stats.videoStreamsOpened, 2);
  await until(() => f.proxy.snapshot().currentPhase.index === 2);
  const third = await get(f.port);
  assert.equal(Buffer.compare(third.body, bytes), 0);
  assert.equal(f.proxy.snapshot().currentPhase.videoMbps, 0);
});

test('redirects are rejected without following or modifying the upstream service', async t => {
  const f = await fixture(t, {}, (_request, response) => { response.writeHead(302, { Location: '/stop' }); response.end(); });
  assert.equal((await get(f.port, '/config.json')).status, 502);
  assert.equal((await get(f.port)).status, 502);
  assert.deepEqual(f.records.map(record => record.path), ['/config.json', '/frames']);
});

test('lost status responses leave real media bytes and controller routes untouched', async t => {
  const video = Buffer.alloc(24576, 0x37);
  const audio = Buffer.alloc(4096, 0x19);
  const f = await fixture(t, { statusDropEvery: 2 }, (request, response) => {
    response.end(request.url === '/frames' ? video : request.url === '/audio' ? audio : '{"message":"OK"}');
  });
  assert.equal((await get(f.port, '/status.json')).status, 200);
  await assert.rejects(get(f.port, '/status.json'));
  assert.equal(Buffer.compare((await get(f.port)).body, video), 0);
  assert.equal(Buffer.compare((await get(f.port, '/audio')).body, audio), 0);
  assert.equal((await get(f.port, '/input/status')).status, 200);
  assert.equal((await get(f.port, '/status.json')).status, 200);
  assert.equal(f.proxy.snapshot().stats.statusRequests, 3);
  assert.equal(f.proxy.snapshot().stats.statusResponsesDropped, 1);
  assert.equal(f.proxy.snapshot().stats.videoResponsesInterrupted, 0);
  assert.deepEqual(f.records.map(record => record.path), ['/status.json', '/status.json', '/frames', '/audio', '/input/status', '/status.json']);
  assert.ok(f.records.every(record => record.method === 'GET'));
});

test('one video TCP interruption cancels its native response and permits a real reconnect', { timeout: 5000 }, async t => {
  let videoCount = 0;
  let firstCancelled = false;
  const bytes = Buffer.alloc(8192, 0x63);
  const f = await fixture(t, { cutVideoAfterSeconds: .15 }, (request, response) => {
    if (request.url !== '/frames') { response.end('{"message":"OK"}'); return; }
    videoCount++;
    if (videoCount > 1) { response.end(bytes); return; }
    response.write(bytes);
    const timer = setInterval(() => response.write(bytes), 20);
    response.once('close', () => { clearInterval(timer); firstCancelled = true; });
  });
  await assert.rejects(get(f.port));
  await until(() => firstCancelled && f.proxy.snapshot().stats.activeVideoStreams === 0);
  assert.equal(Buffer.compare((await get(f.port)).body, bytes), 0);
  assert.equal((await get(f.port, '/input/status')).status, 200);
  await delay(180);
  assert.equal(f.proxy.snapshot().stats.videoResponsesInterrupted, 1);
  assert.equal(f.proxy.snapshot().stats.videoStreamsOpened, 2);
  assert.deepEqual(f.records.map(record => record.path), ['/frames', '/frames', '/input/status']);
  assert.ok(f.records.every(record => record.method === 'GET'));
});

test('bounded runtime closes the development listener without native control requests', { timeout: 4000 }, async t => {
  const f = await fixture(t, { runSeconds: 1 });
  await once(f.proxy.server, 'close');
  await f.proxy.close();
  assert.equal(f.proxy.server.listening, false);
  assert.equal(f.records.length, 0);
});
