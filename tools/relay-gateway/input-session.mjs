// SPDX-License-Identifier: MIT
import { performance } from 'node:perf_hooks';
import { setTimeout as delay } from 'node:timers/promises';
import { WebSocket } from 'ws';
import { parseControl, followsSequence, nativeControlHeaders } from './protocol.mjs';
import { bufferedUpstream } from './upstream.mjs';

const HOLD_MS = 24;
const MAX_PENDING = 64;
const MAX_QUEUED_AGE_MS = 1000;
const PEER_TIMEOUT_MS = 15000;

/** One native owner, one ordered worker. Closing never overtakes an in-flight POST. */
export class InputSession {
  constructor(config, token, ws, onDone, diagnostic = () => {}, prepared = {}) {
    this.config = config;
    this.token = token;
    this.ws = ws;
    this.onDone = onDone;
    this.diagnostic = diagnostic;
    this.queue = [];
    this.running = null;
    this.closing = false;
    this.cleaning = false;
    this.lastQueuedSequence = prepared.lastClientSequence ?? null;
    this.nextNativeSequence = prepared.nextNativeSequence ?? null;
    this.lastSubmittedSequence = prepared.nextNativeSequence === undefined ? null : (prepared.nextNativeSequence - 1) >>> 0;
    this.lastButtons = 0;
    this.lastTransitionAcknowledged = -Infinity;
    this.lastHeardAt = performance.now();
    this.lastInputAt = this.lastHeardAt;
    this.done = new Promise(resolve => { this.resolveDone = resolve; });
    ws.on('message', (data, binary) => this.receive(data, binary));
    ws.on('pong', () => { this.lastHeardAt = performance.now(); });
    ws.once('close', () => this.close(1000, 'Controller disconnected', 'socket_closed'));
    ws.on('error', () => this.close(1011, 'Controller connection failed', 'socket_error'));
    this.heartbeatTimer = setInterval(() => this.heartbeat(), 5000);
    this.heartbeatTimer.unref();
    this.neutralTimer = setInterval(() => this.keepAlive(), 500);
    this.neutralTimer.unref();
  }

  heartbeat(now = performance.now()) {
    if (this.closing) return;
    if (now - this.lastHeardAt >= PEER_TIMEOUT_MS || now - this.lastInputAt >= PEER_TIMEOUT_MS) {
      this.close(1001, 'Controller connection timed out', 'peer_silent');
      this.ws.terminate();
    } else if (this.ws.readyState === WebSocket.OPEN) {
      this.ws.ping(undefined, undefined, error => { if (error) this.close(1011, 'Controller connection failed', 'socket_error'); });
    }
  }

  receive(data, binary) {
    if (this.closing) return;
    this.lastHeardAt = performance.now();
    this.lastInputAt = this.lastHeardAt;
    if (binary) { this.close(1003, 'Text controller states required'); return; }
    let state;
    try { state = parseControl(data); }
    catch { this.close(1008, 'Invalid controller state'); return; }
    if (this.lastQueuedSequence !== null && !followsSequence(state.sequence, this.lastQueuedSequence)) {
      this.close(1008, 'Controller sequence is not increasing');
      return;
    }
    if (this.queue.length + (this.running ? 1 : 0) >= MAX_PENDING) {
      this.close(1008, 'Controller queue limit exceeded', 'queue_overflow');
      return;
    }
    this.lastQueuedSequence = state.sequence;
    this.queue.push({ ...state, receivedAt: performance.now() });
    this.pump();
  }

  async preserveDigitalHold() {
    // Timers may wake early. Recheck a monotonic clock so a coalesced tap stays down
    // for at least 24 ms AFTER the PS5 acknowledged its changed digital state.
    let remaining;
    while ((remaining = this.lastTransitionAcknowledged + HOLD_MS - performance.now()) > 0) {
      await delay(Math.ceil(remaining));
    }
  }

  takeNativeSequence(fallback = 0) {
    const sequence = this.nextNativeSequence ?? fallback;
    this.nextNativeSequence = (sequence + 1) >>> 0;
    this.lastSubmittedSequence = sequence;
    return sequence;
  }

  keepAlive() {
    if (this.closing || this.running || this.queue.length || performance.now() - this.lastInputAt < 250) return;
    // No old gameplay state is replayed while the mobile path is silent. A neutral-only
    // heartbeat preserves the existing owner for the bounded recovery/peer timeout.
    this.running = (async () => {
      if (this.lastButtons !== 0) await this.preserveDigitalHold();
      if (this.closing) return;
      const result = await bufferedUpstream(this.config, { method: 'POST', path: '/input/state',
        headers: nativeControlHeaders(this.token),
        body: Buffer.from(`${this.takeNativeSequence()},0,128,128,128,128,0,0`, 'ascii') });
      if (result.status !== 200) throw new Error('Native neutral state was rejected');
      if (this.lastButtons !== 0) this.lastTransitionAcknowledged = performance.now();
      this.lastButtons = 0;
    })().catch(() => { this.close(1011, 'PS5 controller did not accept the state', 'native_rejected'); })
      .finally(() => { this.running = null; if (this.closing) this.cleanup(); else if (this.queue.length) this.pump(); });
  }

  pump() {
    if (this.running || this.closing) return;
    this.running = this.process().catch(() => {
      this.close(1011, 'PS5 controller did not accept the state', 'native_rejected');
    }).finally(() => {
      this.running = null;
      if (this.closing) this.cleanup();
      else if (this.queue.length) this.pump();
    });
  }

  async process() {
    while (!this.closing && this.queue.length) {
      const state = this.queue.shift();
      if (performance.now() - state.receivedAt > MAX_QUEUED_AGE_MS) {
        this.close(1011, 'Queued controller input expired', 'state_expired'); break;
      }
      const changed = state.buttons !== this.lastButtons;
      if (changed) await this.preserveDigitalHold();
      if (this.closing) break;
      const nativeSequence = this.takeNativeSequence(state.sequence);
      const result = await bufferedUpstream(this.config, {
        method: 'POST', path: '/input/state', headers: nativeControlHeaders(this.token),
        body: Buffer.from(`${nativeSequence}${state.text.slice(state.text.indexOf(','))}`, 'ascii'),
      });
      if (result.status !== 200) throw new Error('Native state was rejected');
      if (changed) this.lastTransitionAcknowledged = performance.now();
      this.lastButtons = state.buttons;
      if (!this.closing && this.ws.readyState === WebSocket.OPEN) {
        if (this.ws.bufferedAmount > 8192) { this.close(1008, 'Controller acknowledgements are blocked', 'ack_backpressure'); break; }
        this.ws.send(`ok,${state.sequence}`, error => { if (error) this.close(1011, 'Controller connection failed'); });
      }
    }
  }

  close(code = 1000, reason = 'Controller disconnected', diagnosticReason = 'client_disconnected') {
    if (!this.closing) {
      this.closing = true;
      clearInterval(this.heartbeatTimer);
      clearInterval(this.neutralTimer);
      this.diagnostic('input_closed', { code, reason: diagnosticReason });
      this.queue.length = 0;
      if (this.ws.readyState === WebSocket.OPEN) this.ws.close(code, reason);
      if (!this.running) this.cleanup();
    }
    return this.done;
  }

  async cleanup() {
    if (this.cleaning) return;
    this.cleaning = true;
    if (this.lastButtons !== 0) await this.preserveDigitalHold();
    const next = this.lastSubmittedSequence === null ? 0 : (this.lastSubmittedSequence + 1) >>> 0;
    const headers = nativeControlHeaders(this.token);
    try {
      await bufferedUpstream(this.config, { method: 'POST', path: '/input/state', headers,
        body: Buffer.from(`${next},0,128,128,128,128,0,0`, 'ascii') });
    } catch { /* The exact-owner disconnect below also neutralizes native input. */ }
    let result;
    try {
      result = await bufferedUpstream(this.config, { method: 'POST', path: '/input/disconnect', headers });
    } catch {
      result = { status: 502, type: 'application/json', body: Buffer.from('{"error":"PS5 controller disconnect was not verified"}') };
    }
    this.onDone(this, result);
    this.diagnostic('input_cleanup', { status: result.status });
    this.resolveDone(result);
  }
}
