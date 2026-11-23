// SPDX-License-Identifier: MIT
import http from 'node:http';
import { createHash, timingSafeEqual } from 'node:crypto';
import { pipeline } from 'node:stream/promises';
import { performance } from 'node:perf_hooks';
import { WebSocketServer } from 'ws';
import { validateConfig } from './config.mjs';
import { GatewayError, routeFor, uniqueHeader, controllerToken, forwardingHeaders, parseControl } from './protocol.mjs';
import { openUpstream, bufferedUpstream, explicitlyRefused } from './upstream.mjs';
import { createStarter } from './startup.mjs';
import { InputSession } from './input-session.mjs';
import { ControllerLease } from './controller-lease.mjs';
import { createDiagnostics } from './diagnostics.mjs';

const VERSION = '1.1.0';
const BIND_HOST = '127.0.0.1';
const DEFAULT_PORT = 37841;

function bodyOf(request) {
  return new Promise((resolve, reject) => {
    let length = 0;
    const chunks = [];
    const finish = (error, value) => {
      clearTimeout(timer);
      request.removeListener('data', onData);
      request.removeListener('end', onEnd);
      request.removeListener('error', onError);
      request.removeListener('aborted', onAborted);
      if (error) { request.pause(); reject(error); } else resolve(value);
    };
    const onData = chunk => {
      length += chunk.length;
      if (length > 256) finish(new GatewayError(413, 'Request body is too large'));
      else chunks.push(chunk);
    };
    const onEnd = () => finish(null, Buffer.concat(chunks, length));
    const onError = () => finish(new GatewayError(400, 'Request body was not received'));
    const onAborted = () => finish(new GatewayError(400, 'Request was cancelled'));
    const timer = setTimeout(() => finish(new GatewayError(408, 'Request body timed out')), 5000);
    request.on('data', onData);
    request.once('end', onEnd);
    request.once('error', onError);
    request.once('aborted', onAborted);
  });
}

function baseHeaders(response) {
  response.setHeader('Cache-Control', 'no-store');
  response.setHeader('X-Content-Type-Options', 'nosniff');
}

function reply(response, status, value) {
  if (response.destroyed || response.writableEnded) return;
  const body = Buffer.from(JSON.stringify(value));
  baseHeaders(response);
  response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': body.length });
  response.end(body);
}

function nativeReply(response, result) {
  if (response.destroyed || response.writableEnded) return;
  baseHeaders(response);
  response.writeHead(result.status, { 'Content-Type': result.type, 'Content-Length': result.body.length });
  response.end(result.body);
}

function rejectUpgrade(socket, status, message) {
  if (socket.destroyed) return;
  const body = JSON.stringify({ error: message });
  socket.end(`HTTP/1.1 ${status} ${http.STATUS_CODES[status] ?? 'Error'}\r\nConnection: close\r\nContent-Type: application/json\r\nCache-Control: no-store\r\nContent-Length: ${Buffer.byteLength(body)}\r\n\r\n${body}`);
}

/** No hostname override is exposed. Only tests select an ephemeral loopback port. */
export function createGateway(input, { onDiagnostic } = {}) {
  const config = validateConfig(input);
  const bearerHash = createHash('sha256').update(config.bearerToken).digest();
  const starter = createStarter(config);
  const sessions = new Map();
  const leases = new Map();
  const acquisitions = new Set();
  const diagnostic = createDiagnostics(onDiagnostic);
  let nextId = 1;
  const recentlyClosed = new Map();
  const mediaControllers = new Set();
  let stopping = false;
  let closePromise = null;

  function authorized(request) {
    const header = uniqueHeader(request, 'authorization');
    const valid = typeof header === 'string' && header.startsWith('Bearer ');
    // Equal-sized digests are compared even for missing, malformed or duplicate headers.
    const digest = createHash('sha256').update(valid ? header.slice(7) : '').digest();
    return timingSafeEqual(bearerHash, digest) && valid;
  }

  function recentResult(key) {
    const now = performance.now();
    for (const [token, record] of recentlyClosed) {
      if (record.expires <= now) recentlyClosed.delete(token);
    }
    return recentlyClosed.get(key)?.result;
  }

  function sessionEnded(session, result) {
    const key = session.token.toLowerCase();
    if (sessions.get(key) === session) sessions.delete(key);
    if (leases.get(key) === session) leases.delete(key);
    // Android may issue a redundant HTTP disconnect after closing its WebSocket.
    // A bounded cache makes that cleanup idempotent without acquiring another owner.
    if (recentlyClosed.size >= 64) recentlyClosed.delete(recentlyClosed.keys().next().value);
    recentlyClosed.set(key, { expires: performance.now() + 60000, result });
  }

  async function streamMedia(request, response, route, headers) {
    const id = nextId++;
    const began = performance.now();
    let outcome = 'upstream_error';
    let upstreamFailed = false;
    const controller = new AbortController();
    mediaControllers.add(controller);
    const cancel = () => {
      if (!response.writableFinished && !upstreamFailed) outcome = 'downstream_closed';
      controller.abort();
    };
    response.once('close', cancel);
    try {
      const upstream = await openUpstream(config, { method: 'GET', path: route.path, headers, signal: controller.signal });
      upstream.response.once('error', () => {
        if (!controller.signal.aborted) { upstreamFailed = true; outcome = 'upstream_error'; }
      });
      diagnostic('media_opened', { id, route: route.path, status: upstream.response.statusCode });
      baseHeaders(response);
      response.statusCode = upstream.response.statusCode;
      response.setHeader('Content-Type', upstream.response.headers['content-type'] ?? 'application/octet-stream');
      const size = upstream.response.headers['content-length'];
      if (typeof size === 'string' && /^[0-9]+$/.test(size)) response.setHeader('Content-Length', size);
      // Byte-for-byte pass-through with Node stream backpressure and cancellation.
      // Do not decompress, concatenate, inspect or transform media chunks.
      await pipeline(upstream.response, response, { signal: controller.signal });
      outcome = 'upstream_ended';
    } catch (error) {
      if (!response.destroyed && !response.headersSent && !controller.signal.aborted) throw error;
    } finally {
      response.removeListener('close', cancel);
      mediaControllers.delete(controller);
      diagnostic('media_closed', { id, route: route.path, reason: outcome, durationMs: Math.max(0, Math.round(performance.now() - began)) });
    }
  }

  async function handle(request, response) {
    try {
      if (stopping) throw new GatewayError(503, 'Gateway is shutting down');
      if (request.method === 'GET' && request.url === '/health') {
        response.setHeader('Connection', 'close');
        reply(response, 200, { service: 'relay-gateway', version: VERSION });
        return;
      }
      if (!authorized(request)) {
        response.setHeader('WWW-Authenticate', 'Bearer');
        throw new GatewayError(401, 'Authentication required');
      }
      const route = routeFor(request.method, request.url);
      const headers = forwardingHeaders(request, route.path);
      const type = uniqueHeader(request, 'content-type');
      if (request.method === 'POST' && type !== undefined &&
          (typeof type !== 'string' || !/^text\/plain(?:\s*;\s*charset=(?:us-ascii|utf-8))?\s*$/i.test(type))) {
        throw new GatewayError(415, 'Only text/plain request bodies are supported');
      }
      const body = await bodyOf(request);
      if (stopping) throw new GatewayError(503, 'Gateway is shutting down');
      if (route.path === '/input/state') parseControl(body);
      else if (body.length) throw new GatewayError(400, 'This route requires an empty body');
      if (route.path === '/relay/start') {
        reply(response, 200, await starter.start());
        return;
      }
      if (route.path === '/input/connect') {
        if (sessions.size + leases.size + acquisitions.size >= 4) throw new GatewayError(503, 'Controller owner limit reached');
        const acquisition = (async () => {
        const id = nextId++;
        // Do not abort a native acquisition merely because its HTTP caller disappears.
        // Finish reading its result so a returned exact token can still be released.
        let result;
        try { result = await bufferedUpstream(config, { method: 'POST', path: route.path, headers, body }); }
        catch (error) { diagnostic('owner_acquisition_unverified', { id, reason: 'request_failed' }); throw error; }
        if (result.status === 200) {
          let acquired;
          try { acquired = JSON.parse(result.body.toString('utf8'))?.token; } catch {}
          if (typeof acquired !== 'string' || !/^[a-fA-F0-9]{32}$/.test(acquired)) {
            diagnostic('owner_acquisition_unverified', { id });
            throw new GatewayError(502, 'The native controller acquisition was not verified');
          }
          const key = acquired.toLowerCase();
          if (sessions.has(key) || leases.has(key) || recentResult(key))
            throw new GatewayError(502, 'The native controller returned a reused owner');
          const lease = new ControllerLease(config, acquired, sessionEnded,
            (event, fields) => diagnostic(event, { id, ...fields }));
          leases.set(key, lease);
          diagnostic('owner_acquired', { id });
          if (stopping || response.destroyed) {
            await lease.close(stopping ? 'gateway_shutdown' : 'client_response_lost');
            return;
          }
          response.once('close', () => {
            if (!response.writableFinished) lease.close('client_response_lost');
          });
          // Native idle time began at its grant, before this reply travelled over LAN.
          // Verify an immediate neutral state before promising a prepared token to Android.
          lease.keepAlive();
          await lease.running;
          if (lease.closing) {
            await lease.done;
            throw new GatewayError(503, 'The native controller preparation was not verified');
          }
          if (stopping || response.destroyed) {
            await lease.close(stopping ? 'gateway_shutdown' : 'client_response_lost');
            return;
          }
        }
        nativeReply(response, result);
        })();
        acquisitions.add(acquisition);
        try { await acquisition; } finally { acquisitions.delete(acquisition); }
        return;
      }
      const token = headers['X-PBS-Controller'];
      const key = token?.toLowerCase();
      if (route.path === '/input/disconnect') {
        const active = sessions.get(key);
        if (active) { nativeReply(response, await active.close()); return; }
        const waiting = leases.get(key);
        if (waiting) { nativeReply(response, await waiting.close()); return; }
        const completed = recentResult(key);
        if (completed) { nativeReply(response, completed); return; }
      } else if (route.path === '/input/state' && sessions.has(key)) {
        throw new GatewayError(409, 'This controller is using the WebSocket input channel');
      }
      if (route.path === '/input/state' && leases.has(key)) {
        nativeReply(response, await leases.get(key).state(body)); return;
      }
      if (route.media) await streamMedia(request, response, route, headers);
      else nativeReply(response, await bufferedUpstream(config, { method: request.method, path: route.path, headers, body }));
    } catch (error) {
      if (response.headersSent) { response.destroy(); return; }
      response.setHeader('Connection', 'close');
      reply(response, error instanceof GatewayError ? error.status : explicitlyRefused(error) ? 503 : 502,
        { error: error instanceof GatewayError ? error.message : 'PS5 service is unavailable' });
    }
  }

  const server = http.createServer({ maxHeaderSize: 16384, headersTimeout: 5000, requestTimeout: 15000,
    connectionsCheckingInterval: 1000, keepAliveTimeout: 5000 }, handle);
  server.maxConnections = 64;
  server.maxRequestsPerSocket = 500;
  server.on('checkContinue', (request, response) => {
    response.setHeader('Connection', 'close');
    reply(response, authorized(request) ? 417 : 401, { error: 'Request expectation is not supported' });
  });
  server.on('clientError', (_error, socket) => rejectUpgrade(socket, 400, 'Invalid HTTP request'));
  const sockets = new WebSocketServer({ noServer: true, maxPayload: 256, perMessageDeflate: false,
    handleProtocols: () => false });
  server.on('upgrade', async (request, socket, head) => {
    socket.on('error', () => {});
    try {
      if (stopping) throw new GatewayError(503, 'Gateway is shutting down');
      if (!authorized(request)) throw new GatewayError(401, 'Authentication required');
      if (request.method !== 'GET' || request.url !== '/relay/input') throw new GatewayError(404, 'Unknown WebSocket route');
      const token = controllerToken(request);
      const key = token.toLowerCase();
      if (sessions.has(key) || recentResult(key)) throw new GatewayError(409, 'Controller channel is already used or closed');
      const lease = leases.get(key);
      if (lease?.closing || lease?.attaching) throw new GatewayError(409, 'Controller ownership is closing or busy');
      if (sessions.size >= 4) throw new GatewayError(503, 'Controller channel limit reached');
      if (lease) {
        // ws validates the remaining upgrade fields after preparation. Rejection/abandonment
        // must release this exact lease even when handleUpgrade returns without its callback.
        const abandon = () => { if (!lease.transferred) lease.close('client_response_lost'); };
        socket.once('close', abandon); socket.once('error', abandon);
        socket.pause();
        await lease.prepareAttach();
        if (socket.destroyed || stopping) { await lease.close('client_response_lost'); return; }
        socket.resume();
      }
      sockets.handleUpgrade(request, socket, head, ws => {
        const prepared = lease?.attach();
        if (lease) leases.delete(key);
        const id = nextId++;
        diagnostic('input_opened', { id });
        sessions.set(key, new InputSession(config, lease?.token ?? token, ws, sessionEnded,
          (event, fields) => diagnostic(event, { id, ...fields }), prepared));
      });
    } catch (error) {
      rejectUpgrade(socket, error instanceof GatewayError ? error.status : 400,
        error instanceof GatewayError ? error.message : 'WebSocket upgrade failed');
    }
  });

  return {
    server,
    listen(port = DEFAULT_PORT) {
      if (!Number.isInteger(port) || port < 0 || port > 65535) return Promise.reject(new Error('Invalid loopback port'));
      return new Promise((resolve, reject) => {
        const failed = error => { server.removeListener('listening', ready); reject(error); };
        const ready = () => { server.removeListener('error', failed); resolve(server.address()); };
        server.once('error', failed);
        server.once('listening', ready);
        server.listen(port, BIND_HOST);
      });
    },
    close() {
      if (!closePromise) closePromise = (async () => {
        stopping = true;
        const stopped = new Promise(resolve => server.close(() => resolve()));
        for (const controller of mediaControllers) controller.abort();
        await Promise.all([
          ...[...sessions.values()].map(session => session.close(1001, 'Gateway is shutting down')),
          ...[...leases.values()].map(lease => lease.close('gateway_shutdown')),
          ...[...acquisitions].map(acquisition => acquisition.catch(() => {})),
        ]);
        for (const socket of sockets.clients) socket.terminate();
        await new Promise(resolve => sockets.close(resolve));
        await starter.settle();
        server.closeAllConnections();
        await stopped;
      })();
      return closePromise;
    },
  };
}
