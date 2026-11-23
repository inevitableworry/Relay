// SPDX-License-Identifier: MIT
import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { readFileSync } from 'node:fs';
import { mkdir, mkdtemp, writeFile, readFile, access, rm } from 'node:fs/promises';
import { createHash, randomBytes } from 'node:crypto';
import { createGateway } from '../gateway.mjs';
import { loadConfig, validateConfig } from '../config.mjs';
import { call, makeNative, listen, closeServer } from '../support.mjs';

const runtimeRoot = fileURLToPath(new URL('../.test-runtime/', import.meta.url));

async function environment(t, { running = false, handler, onUpload = true, timeouts = {}, wrongHash = false, wrongSize = false } = {}) {
  await mkdir(runtimeRoot, { recursive: true });
  const directory = await mkdtemp(path.join(runtimeRoot, 'startup-'));
  const bytes = Buffer.alloc(1024, 0x5a);
  bytes.set([0x7f, 0x45, 0x4c, 0x46]);
  const payloadFile = path.join(directory, 'test-payload.elf');
  const journalFile = path.join(directory, 'attempt.ndjson');
  await writeFile(payloadFile, bytes);
  const native = makeNative(handler);
  let capturePort;
  if (running) capturePort = await listen(native.server);
  else {
    const reserve = http.createServer();
    capturePort = await listen(reserve);
    await closeServer(reserve);
  }
  const uploads = [];
  const journalBeforeUpload = [];
  const loaderSockets = new Set();
  const loader = net.createServer(socket => {
    loaderSockets.add(socket);
    socket.on('close', () => loaderSockets.delete(socket));
    socket.on('error', () => {});
    const chunks = [];
    let before;
    try { before = readFileSync(journalFile, 'utf8'); } catch { before = 'missing'; }
    socket.on('data', chunk => chunks.push(chunk));
    socket.on('end', async () => {
      uploads.push(Buffer.concat(chunks));
      journalBeforeUpload.push(before);
      if (onUpload && !native.server.listening) await listen(native.server, capturePort);
    });
  });
  const loaderPort = await listen(loader);
  const config = {
    ps5Host: '127.0.0.1', ps5Port: capturePort, bearerToken: randomBytes(32).toString('hex'),
    payload: { file: payloadFile, size: bytes.length + (wrongSize ? 1 : 0),
      sha256: wrongHash ? '0'.repeat(64) : createHash('sha256').update(bytes).digest('hex'),
      loaderPort, capturePort },
    startupJournal: journalFile,
    timeouts: { requestMs: 1000, probeMs: 4000, uploadMs: 1000, readyMs: 1000, ...timeouts },
  };
  const gateway = createGateway(config);
  const address = await gateway.listen(0);
  t.after(async () => {
    await gateway.close();
    await closeServer(native.server);
    for (const socket of loaderSockets) socket.destroy();
    await closeServer(loader);
    assert.equal(path.dirname(directory), path.resolve(runtimeRoot));
    await rm(directory, { recursive: true, force: true });
  });
  return { directory, config, gateway, native, loader, bytes, uploads, journalBeforeUpload,
    start: () => call(address.port, '/relay/start', { token: config.bearerToken, method: 'POST' }) };
}

test('configuration pins the capture port and resolves local paths without exposing a bind override', async t => {
  const e = await environment(t, { running: true });
  assert.throws(() => validateConfig({ ...e.config, payload: { ...e.config.payload, capturePort: e.config.ps5Port + 1 } }), /capturePort/);
  assert.throws(() => validateConfig({ ...e.config, bearerToken: 'short' }), /bearerToken/);
  assert.throws(() => validateConfig({ ...e.config, ps5Host: 'http://example.invalid' }), /ps5Host/);
  const relative = { ...e.config, payload: { ...e.config.payload, file: 'test-payload.elf' }, startupJournal: 'attempt.ndjson' };
  const configFile = path.join(e.directory, 'configuration.json');
  await writeFile(configFile, JSON.stringify(relative));
  const loaded = await loadConfig(configFile);
  assert.equal(loaded.payload.file, e.config.payload.file);
  assert.equal(loaded.startupJournal, e.config.startupJournal);
});

test('a compatible running service succeeds without a loader connection or journal', async t => {
  const e = await environment(t, { running: true });
  const response = await e.start();
  assert.equal(response.status, 200);
  assert.deepEqual(JSON.parse(response.text), { state: 'running' });
  assert.equal(e.uploads.length, 0);
  await assert.rejects(access(e.config.startupJournal), { code: 'ENOENT' });
  assert.equal(e.native.records.length, 1);
});

test('HTTP answers, invalid configuration, timeout and reset cannot authorize payload upload', { timeout: 15000 }, async t => {
  const cases = [
    ['HTTP 404', (_request, response) => { response.writeHead(404); response.end('not this service'); }],
    ['incompatible HTTP 200', (_request, response) => response.end('{"platform":"other"}')],
    ['redirect', (_request, response) => { response.writeHead(302, { Location: '/config.json' }); response.end(); }],
    ['timeout', () => {}],
    ['reset', request => request.socket.destroy()],
  ];
  for (const [name, handler] of cases) await t.test(name, async child => {
    const e = await environment(child, { running: true, handler, timeouts: { probeMs: 50 } });
    const response = await e.start();
    assert.ok([502, 504].includes(response.status));
    assert.equal(e.uploads.length, 0);
    await assert.rejects(access(e.config.startupJournal), { code: 'ENOENT' });
  });
});

test('three refusals and a durable journal permit exactly one pinned ELF upload, including concurrent callers', { timeout: 30000 }, async t => {
  const e = await environment(t);
  const responses = await Promise.all([e.start(), e.start(), e.start()]);
  assert.ok(responses.every(response => response.status === 200), responses.map(response => response.text).join('; '));
  assert.ok(responses.every(response => JSON.parse(response.text).state === 'started'));
  assert.equal(e.uploads.length, 1);
  assert.deepEqual(e.uploads[0], e.bytes);
  const before = e.journalBeforeUpload[0].trim().split('\n').map(line => JSON.parse(line));
  assert.equal(before[0].phase, 'armed');
  assert.equal(before.find(record => record.phase === 'absence-confirmed')?.confirmedRefusals, 3);
  const journal = await readFile(e.config.startupJournal, 'utf8');
  assert.ok(journal.includes('service-verified'));
  assert.equal(journal.includes(e.config.bearerToken), false);
  assert.deepEqual(JSON.parse((await e.start()).text), { state: 'running' });
  assert.equal(e.uploads.length, 1);
});

test('incorrect pinned size or hash sends no payload and creates no attempt journal', { timeout: 30000 }, async t => {
  for (const options of [{ wrongHash: true }, { wrongSize: true }]) await t.test(Object.keys(options)[0], async child => {
    const e = await environment(child, options);
    assert.equal((await e.start()).status, 503);
    assert.equal(e.uploads.length, 0);
    await assert.rejects(access(e.config.startupJournal), { code: 'ENOENT' });
  });
});

test('an ambiguous upload remains blocked across retries and a gateway restart', { timeout: 40000 }, async t => {
  const e = await environment(t, { onUpload: false, timeouts: { readyMs: 100 } });
  assert.equal((await e.start()).status, 503);
  assert.equal(e.uploads.length, 1);
  const journal = await readFile(e.config.startupJournal, 'utf8');
  assert.ok(journal.includes('startup-unverified'));
  assert.equal((await e.start()).status, 409);
  assert.equal(e.uploads.length, 1);
  await e.gateway.close();
  const restarted = createGateway(e.config);
  const address = await restarted.listen(0);
  t.after(() => restarted.close());
  const retry = () => call(address.port, '/relay/start', { token: e.config.bearerToken, method: 'POST' });
  assert.equal((await retry()).status, 409);
  assert.equal(e.uploads.length, 1);
  await listen(e.native.server, e.config.ps5Port);
  assert.deepEqual(JSON.parse((await retry()).text), { state: 'running' });
  assert.equal(e.uploads.length, 1);
  assert.equal(await readFile(e.config.startupJournal, 'utf8'), journal);
});

test('an existing partial attempt journal is never truncated or automatically retried', { timeout: 15000 }, async t => {
  const e = await environment(t, { onUpload: false });
  await writeFile(e.config.startupJournal, '{"phase":');
  assert.equal((await e.start()).status, 409);
  assert.equal(e.uploads.length, 0);
  assert.equal(await readFile(e.config.startupJournal, 'utf8'), '{"phase":');
});
