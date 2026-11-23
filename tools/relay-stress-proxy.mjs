// SPDX-License-Identifier: MIT
// Development-only transport impairment. Never creates frames or native capabilities.
import http from 'node:http';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { readFile } from 'node:fs/promises';
import { EventEmitter, once } from 'node:events';
import { performance } from 'node:perf_hooks';
import { pipeline } from 'node:stream/promises';
import { setTimeout as delay } from 'node:timers/promises';
import { GatewayError, routeFor, forwardingHeaders, uniqueHeader, parseControl } from './relay-gateway/protocol.mjs';
import { openUpstream } from './relay-gateway/upstream.mjs';

export const LIMITS = Object.freeze({ queueBytes: 2 * 1024 * 1024, queueChunks: 128,
  inputChunkBytes: 1024 * 1024, quantumBytes: 16 * 1024, burstBytes: 32 * 1024,
  videoStreams: 2, connections: 32 });

function number(value, name, minimum, maximum, integer = false) {
  if (!Number.isFinite(value) || value < minimum || value > maximum || (integer && !Number.isInteger(value))) {
    throw new Error(`Invalid ${name}`);
  }
  return value;
}

function localIpv4(host) {
  if (net.isIP(host) !== 4) return false;
  const octets = host.split('.').map(Number);
  return host === '127.0.0.1' || octets[0] === 10 || (octets[0] === 172 && octets[1] >= 16 && octets[1] <= 31) ||
    (octets[0] === 192 && octets[1] === 168);
}

export function validateStressConfig(input) {
  const allowed = ['upstreamHost', 'upstreamPort', 'listenPort', 'runSeconds', 'videoMbps', 'videoDelayMs', 'schedule',
    'statusDropEvery', 'cutVideoAfterSeconds'];
  if (!input || typeof input !== 'object' || Object.keys(input).some(key => !allowed.includes(key)) || !localIpv4(input.upstreamHost)) {
    throw new Error('Choose one explicit private LAN IPv4 address or 127.0.0.1 as upstreamHost');
  }
  const upstreamPort = number(input.upstreamPort ?? 9999, 'upstreamPort', 1, 65535, true);
  const listenPort = number(input.listenPort ?? 37842, 'listenPort', 0, 65535, true);
  if (input.upstreamHost === '127.0.0.1' && upstreamPort === listenPort) throw new Error('The proxy cannot target itself');
  const runSeconds = number(input.runSeconds ?? 300, 'runSeconds', 1, 1800);
  const statusDropEvery = number(input.statusDropEvery ?? 0, 'statusDropEvery', 0, 1000, true);
  const cutVideoAfterSeconds = number(input.cutVideoAfterSeconds ?? 0, 'cutVideoAfterSeconds', 0, runSeconds);
  if (input.schedule !== undefined && (input.videoMbps !== undefined || input.videoDelayMs !== undefined)) {
    throw new Error('Use a schedule or constant impairment flags, not both');
  }
  const phases = input.schedule ?? [{ afterSeconds: 0, videoMbps: input.videoMbps ?? 0, videoDelayMs: input.videoDelayMs ?? 0 }];
  if (!Array.isArray(phases) || !phases.length || phases.length > 16) throw new Error('A schedule needs 1 to 16 phases');
  const schedule = phases.map((phase, index) => {
    if (!phase || typeof phase !== 'object' || Object.keys(phase).some(key => !['afterSeconds', 'videoMbps', 'videoDelayMs'].includes(key))) {
      throw new Error('Invalid schedule phase');
    }
    const afterSeconds = number(phase.afterSeconds, 'afterSeconds', 0, runSeconds);
    const videoMbps = number(phase.videoMbps, 'videoMbps', 0, 200);
    if (videoMbps > 0 && videoMbps < .1) throw new Error('Use at least 0.1 Mbps, or zero for unlimited');
    const videoDelayMs = number(phase.videoDelayMs ?? 0, 'videoDelayMs', 0, 5000, true);
    if ((index === 0 && afterSeconds !== 0) || (index > 0 && afterSeconds <= phases[index - 1].afterSeconds)) {
      throw new Error('Schedule phases must start at zero and increase strictly');
    }
    return Object.freeze({ afterSeconds, videoMbps, videoDelayMs });
  });
  return Object.freeze({ upstreamHost: input.upstreamHost, upstreamPort, listenPort, runSeconds,
    statusDropEvery, cutVideoAfterSeconds, schedule: Object.freeze(schedule) });
}

/** Shared token bucket: all video responses compete for the one configured cap. */
class VideoBudget {
  constructor(schedule) { this.schedule = schedule; this.origin = null; this.lastRefill = null; this.tokens = 0; }
  start(now) { if (this.origin === null) { this.origin = now; this.lastRefill = now; } }
  phase(now) {
    const elapsed = this.origin === null ? 0 : Math.max(0, (now - this.origin) / 1000);
    let index = 0;
    while (this.origin !== null && index + 1 < this.schedule.length &&
        this.origin + this.schedule[index + 1].afterSeconds * 1000 <= now) index++;
    return { ...this.schedule[index], index, elapsedSeconds: elapsed };
  }
  refill(now) {
    this.start(now);
    let cursor = this.lastRefill;
    while (cursor < now) {
      const phase = this.phase(cursor);
      const nextBoundary = this.schedule[phase.index + 1];
      const end = Math.min(now, nextBoundary ? this.origin + nextBoundary.afterSeconds * 1000 : now);
      const earned = phase.videoMbps === 0 ? LIMITS.burstBytes : (end - cursor) * phase.videoMbps * 125;
      this.tokens = Math.min(LIMITS.burstBytes, this.tokens + earned);
      cursor = end;
    }
    this.lastRefill = now;
  }
  claimOrWait(bytes, now) {
    this.refill(now);
    const phase = this.phase(now);
    if (phase.videoMbps === 0) return 0;
    if (this.tokens >= bytes) { this.tokens -= bytes; return 0; }
    const next = this.schedule[phase.index + 1];
    const boundaryWait = next ? this.origin + next.afterSeconds * 1000 - now : Infinity;
    return Math.max(.1, Math.min((bytes - this.tokens) / (phase.videoMbps * 125), boundaryWait, 250));
  }
}

async function* delayedVideo(source, budget, controller, stats) {
  const signal = controller.signal;
  const changed = new EventEmitter();
  const queue = [];
  let queuedBytes = 0;
  let finished = false;
  let failure = null;
  let drained = false;
  const producer = (async () => {
    try {
      for await (const chunk of source) {
        if (chunk.length > LIMITS.inputChunkBytes) throw new GatewayError(502, 'Upstream read exceeded the development proxy limit');
        const receivedAt = performance.now();
        const readyAt = receivedAt + budget.phase(receivedAt).videoDelayMs;
        for (let offset = 0; offset < chunk.length; offset += 65536) {
          const part = chunk.subarray(offset, Math.min(offset + 65536, chunk.length));
          while (queuedBytes + part.length > LIMITS.queueBytes || queue.length >= LIMITS.queueChunks) {
            await once(changed, 'change', { signal });
          }
          signal.throwIfAborted();
          queue.push({ bytes: part, offset: 0, readyAt });
          queuedBytes += part.length;
          stats.activeQueuedBytes += part.length;
          stats.maxQueuedBytesPerStream = Math.max(stats.maxQueuedBytesPerStream, queuedBytes);
          stats.maxQueuedChunksPerStream = Math.max(stats.maxQueuedChunksPerStream, queue.length);
          stats.maxTotalQueuedBytes = Math.max(stats.maxTotalQueuedBytes, stats.activeQueuedBytes);
          changed.emit('change');
        }
      }
    } catch (error) { failure = error; }
    finally { finished = true; changed.emit('change'); }
  })();
  try {
    while (true) {
      signal.throwIfAborted();
      if (!queue.length) {
        if (finished) { if (failure) throw failure; drained = true; break; }
        await once(changed, 'change', { signal });
        continue;
      }
      const entry = queue[0];
      const remainingDelay = entry.readyAt - performance.now();
      if (remainingDelay > 0) { await delay(Math.ceil(remainingDelay), undefined, { signal }); continue; }
      const length = Math.min(LIMITS.quantumBytes, entry.bytes.length - entry.offset);
      const wait = budget.claimOrWait(length, performance.now());
      if (wait > 0) { await delay(Math.ceil(wait), undefined, { signal }); continue; }
      const part = entry.bytes.subarray(entry.offset, entry.offset + length);
      entry.offset += length;
      queuedBytes -= length;
      stats.activeQueuedBytes -= length;
      if (entry.offset === entry.bytes.length) queue.shift();
      changed.emit('change');
      stats.videoBytesEmitted += length;
      yield part; // pipeline awaits writable backpressure before requesting another part.
    }
  } finally {
    if (!drained) { controller.abort(); source.destroy(); }
    changed.emit('change');
    await producer;
    stats.activeQueuedBytes -= queuedBytes;
  }
}

function requestBody(request) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    const finish = (error, bytes) => {
      clearTimeout(timer);
      request.removeListener('data', data);
      request.removeListener('end', end);
      request.removeListener('error', failed);
      request.removeListener('aborted', failed);
      if (error) { request.pause(); reject(error); } else resolve(bytes);
    };
    const data = chunk => { size += chunk.length; if (size > 256) finish(new GatewayError(413, 'Body limit exceeded')); else chunks.push(chunk); };
    const end = () => finish(null, Buffer.concat(chunks, size));
    const failed = () => finish(new GatewayError(400, 'Request body cancelled'));
    const timer = setTimeout(() => finish(new GatewayError(408, 'Request body timed out')), 5000);
    request.on('data', data); request.once('end', end); request.once('error', failed); request.once('aborted', failed);
  });
}

function json(response, status, value) {
  if (response.destroyed || response.writableEnded) return;
  const bytes = Buffer.from(JSON.stringify(value));
  response.writeHead(status, { 'Content-Type': 'application/json', 'Content-Length': bytes.length, 'Cache-Control': 'no-store' });
  response.end(bytes);
}

export function createStressProxy(input) {
  const config = validateStressConfig(input);
  const native = { ps5Host: config.upstreamHost, ps5Port: config.upstreamPort, timeouts: { requestMs: 3000 } };
  const budget = new VideoBudget(config.schedule);
  const controllers = new Set();
  const handlers = new Set();
  const videoResponses = new Set();
  const stats = { upstreamRequests: 0, videoStreamsOpened: 0, activeVideoStreams: 0, videoBytesEmitted: 0,
    activeQueuedBytes: 0, maxQueuedBytesPerStream: 0, maxQueuedChunksPerStream: 0, maxTotalQueuedBytes: 0,
    statusRequests: 0, statusResponsesDropped: 0, videoResponsesInterrupted: 0, profileRequests: [] };
  let stopping = false;
  let closePromise = null;
  let expiry = null;
  let videoCutTimer = null;
  let videoCutScheduled = false;
  const snapshot = () => ({ service: 'relay-stress-proxy', version: '1.0.0', developmentOnly: true,
    scenarioStarted: budget.origin !== null, currentPhase: budget.phase(performance.now()), schedule: config.schedule,
    faults: { statusDropEvery: config.statusDropEvery, cutVideoAfterSeconds: config.cutVideoAfterSeconds },
    limits: LIMITS, stats: { ...stats, profileRequests: [...stats.profileRequests] } });

  const server = http.createServer({ maxHeaderSize: 16384, requestTimeout: 10000, headersTimeout: 5000,
    connectionsCheckingInterval: 1000, keepAliveTimeout: 2000 }, async (request, response) => {
    const controller = new AbortController();
    controllers.add(controller);
    let handlerFinished;
    const completion = new Promise(resolve => { handlerFinished = resolve; });
    handlers.add(completion);
    const cancel = () => controller.abort();
    response.once('close', cancel);
    let video = false;
    let controlTimer = null;
    try {
      if (stopping) throw new GatewayError(503, 'Development proxy is stopping');
      if (request.method === 'GET' && request.url === '/health') {
        response.setHeader('Connection', 'close'); json(response, 200, snapshot()); return;
      }
      if (request.url?.startsWith('/relay/')) throw new GatewayError(404, 'Remote gateway operations are not part of this development proxy');
      const route = routeFor(request.method, request.url);
      const headers = forwardingHeaders(request, route.path);
      const type = uniqueHeader(request, 'content-type');
      if (request.method === 'POST' && type !== undefined && (typeof type !== 'string' ||
          !/^text\/plain(?:\s*;\s*charset=(?:us-ascii|utf-8))?\s*$/i.test(type))) throw new GatewayError(415, 'Text bodies required');
      const body = await requestBody(request);
      if (route.path === '/input/state') parseControl(body);
      else if (body.length) throw new GatewayError(400, 'This route requires an empty body');
      video = route.path === '/frames';
      if (video && stats.activeVideoStreams >= LIMITS.videoStreams) { video = false; throw new GatewayError(503, 'Development video stream limit reached'); }
      if (video) stats.activeVideoStreams++;
      stats.upstreamRequests++;
      if (!route.media) controlTimer = setTimeout(() => controller.abort(), 3000);
      const upstream = await openUpstream(native, { method: request.method, path: route.path, headers, body, signal: controller.signal });
      if (!route.media) {
        const chunks = [];
        let size = 0;
        for await (const chunk of upstream.response) {
          size += chunk.length;
          if (size > 65536) throw new GatewayError(502, 'Native control response exceeded limit');
          chunks.push(chunk);
        }
        if (route.path === '/status.json') {
          stats.statusRequests++;
          if (config.statusDropEvery > 0 && stats.statusRequests % config.statusDropEvery === 0) {
            // The real status response was read, then intentionally lost on this test link.
            // Do not forge a PBS status or touch any media/controller connection.
            stats.statusResponsesDropped++;
            response.destroy();
            return;
          }
        }
        if (route.path.startsWith('/capture/profile?')) {
          if (stats.profileRequests.length >= 32) stats.profileRequests.shift();
          stats.profileRequests.push({ atSeconds: budget.phase(performance.now()).elapsedSeconds,
            path: route.path, upstreamStatus: upstream.response.statusCode });
        }
        response.writeHead(upstream.response.statusCode, { 'Content-Type': upstream.response.headers['content-type'] ?? 'application/json',
          'Content-Length': size, 'Cache-Control': 'no-store' });
        response.end(Buffer.concat(chunks, size));
      } else {
        response.statusCode = upstream.response.statusCode;
        response.setHeader('Content-Type', upstream.response.headers['content-type'] ?? 'application/octet-stream');
        response.setHeader('Cache-Control', 'no-store');
        const length = upstream.response.headers['content-length'];
        if (typeof length === 'string' && /^\d+$/.test(length)) response.setHeader('Content-Length', length);
        if (video && upstream.response.statusCode === 200) {
          budget.start(performance.now());
          stats.videoStreamsOpened++;
          videoResponses.add(response);
          if (!videoCutScheduled && config.cutVideoAfterSeconds > 0) {
            videoCutScheduled = true;
            videoCutTimer = setTimeout(() => {
              for (const active of videoResponses) {
                if (!active.destroyed && !active.writableEnded) {
                  stats.videoResponsesInterrupted++;
                  active.destroy();
                }
              }
            }, config.cutVideoAfterSeconds * 1000);
          }
          await pipeline(delayedVideo(upstream.response, budget, controller, stats), response, { signal: controller.signal });
        } else {
          // Audio and error responses receive no artificial bandwidth or latency changes.
          await pipeline(upstream.response, response, { signal: controller.signal });
        }
      }
    } catch (error) {
      if (response.headersSent) response.destroy();
      else if (!response.destroyed) {
        response.setHeader('Connection', 'close');
        json(response, error instanceof GatewayError ? error.status : 502,
          { error: error instanceof GatewayError ? error.message : 'Native passthrough failed' });
      }
    } finally {
      if (controlTimer) clearTimeout(controlTimer);
      controller.abort();
      response.removeListener('close', cancel);
      controllers.delete(controller);
      videoResponses.delete(response);
      if (video) stats.activeVideoStreams--;
      handlers.delete(completion);
      handlerFinished();
    }
  });
  server.maxConnections = LIMITS.connections;
  server.on('upgrade', (_request, socket) => socket.end('HTTP/1.1 404 Not Found\r\nConnection: close\r\nContent-Length: 0\r\n\r\n'));
  server.on('checkContinue', (_request, response) => { response.setHeader('Connection', 'close'); json(response, 417, { error: 'Expect is not supported' }); });
  server.on('clientError', (_error, socket) => { if (!socket.destroyed) socket.end('HTTP/1.1 400 Bad Request\r\nConnection: close\r\nContent-Length: 0\r\n\r\n'); });
  const api = {
    server, snapshot,
    listen() {
      return new Promise((resolve, reject) => {
        const failed = error => { server.removeListener('listening', ready); reject(error); };
        const ready = () => {
          server.removeListener('error', failed);
          if (config.upstreamHost === '127.0.0.1' && config.upstreamPort === server.address().port) {
            api.close().then(() => reject(new Error('Proxy cannot target itself'))); return;
          }
          expiry = setTimeout(() => { api.close(); }, config.runSeconds * 1000);
          expiry.unref();
          resolve(server.address());
        };
        server.once('error', failed); server.once('listening', ready);
        server.listen(config.listenPort, '127.0.0.1');
      });
    },
    close() {
      if (!closePromise) closePromise = (async () => {
        stopping = true;
        if (expiry) clearTimeout(expiry);
        if (videoCutTimer) clearTimeout(videoCutTimer);
        const stopped = new Promise(resolve => server.close(() => resolve()));
        for (const controller of controllers) controller.abort();
        server.closeAllConnections();
        await stopped;
        await Promise.all([...handlers]);
      })();
      return closePromise;
    },
  };
  return api;
}

async function main() {
  const help = 'node tools/relay-stress-proxy.mjs --upstream-host <private IPv4> [--upstream-port 9999] [--port 37842] [--run-seconds 300] [--video-mbps 0] [--video-delay-ms 0] [--schedule <JSON file>] [--status-drop-every 0] [--cut-video-after-seconds 0]';
  if (process.argv.slice(2).includes('--help')) { process.stdout.write(help + '\n'); return; }
  const flags = new Map();
  const known = new Set(['--upstream-host', '--upstream-port', '--port', '--run-seconds', '--video-mbps', '--video-delay-ms', '--schedule',
    '--status-drop-every', '--cut-video-after-seconds']);
  const args = process.argv.slice(2);
  for (let i = 0; i < args.length; i += 2) {
    if (!known.has(args[i]) || flags.has(args[i]) || args[i + 1] === undefined) throw new Error('Invalid CLI');
    flags.set(args[i], args[i + 1]);
  }
  const input = { upstreamHost: flags.get('--upstream-host') };
  for (const [flag, name] of [['--upstream-port', 'upstreamPort'], ['--port', 'listenPort'], ['--run-seconds', 'runSeconds'],
      ['--video-mbps', 'videoMbps'], ['--video-delay-ms', 'videoDelayMs'],
      ['--status-drop-every', 'statusDropEvery'], ['--cut-video-after-seconds', 'cutVideoAfterSeconds']]) {
    if (flags.has(flag)) input[name] = Number(flags.get(flag));
  }
  if (flags.has('--schedule')) {
    const bytes = await readFile(flags.get('--schedule'));
    if (bytes.length > 16384) throw new Error('Schedule too large');
    input.schedule = JSON.parse(bytes.toString('utf8').replace(/^\uFEFF/, ''));
  }
  const proxy = createStressProxy(input);
  const address = await proxy.listen();
  process.stdout.write(`Development video impairment proxy on 127.0.0.1:${address.port}; bounded runtime ${input.runSeconds ?? 300}s.\n`);
  process.stdout.write(JSON.stringify({ schedule: proxy.snapshot().schedule, faults: proxy.snapshot().faults,
    scope: 'real native bytes; explicit test faults only; audio and controller traffic unshaped' }) + '\n');
  process.once('SIGINT', () => { proxy.close(); });
  process.once('SIGTERM', () => { proxy.close(); });
  serverClosed(proxy.server).then(async () => {
    await proxy.close();
    process.stdout.write(JSON.stringify(proxy.snapshot()) + '\n');
  });
}

function serverClosed(server) { return new Promise(resolve => server.once('close', resolve)); }

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch(() => {
    process.stderr.write('Development proxy could not start. Check the private upstream, schedule and loopback port. No credential/request data was logged.\n');
    process.exitCode = 1;
  });
}
