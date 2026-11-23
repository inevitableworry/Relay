// SPDX-License-Identifier: MIT
import { performance } from 'node:perf_hooks';
import { bufferedUpstream } from './upstream.mjs';
import { GatewayError, nativeControlHeaders, parseControl, followsSequence } from './protocol.mjs';

export const OWNER_ATTACH_MS = 15000;

/** Owns only a token returned by this gateway's successful native acquisition. */
export class ControllerLease {
  constructor(config, token, onDone, diagnostic = () => {}) {
    this.config = config; this.token = token; this.onDone = onDone; this.diagnostic = diagnostic;
    this.closing = false; this.transferred = false; this.running = null; this.cleaning = false;
    this.attaching = false; this.nextNativeSequence = 0; this.lastClientSequence = null;
    this.done = new Promise(resolve => { this.resolveDone = resolve; });
    this.renew();
    // Native PBS expires an idle controller after two seconds. Keep only a neutral
    // owner alive while the authenticated client receives the token and upgrades.
    this.neutralTimer = setInterval(() => this.keepAlive(), 500);
    this.neutralTimer.unref();
  }
  renew() {
    if (this.closing || this.transferred) return;
    clearTimeout(this.timer);
    this.expiresAt = performance.now() + OWNER_ATTACH_MS;
    this.timer = setTimeout(() => this.expire(), OWNER_ATTACH_MS);
    this.timer.unref();
  }
  expire(now = performance.now()) {
    if (!this.closing && !this.transferred && now >= this.expiresAt) this.close('attach_timeout');
  }
  async prepareAttach() {
    if (this.closing || this.transferred || this.attaching)
      throw new GatewayError(409, 'Controller ownership is closing or busy');
    this.attaching = true;
    if (this.running) await this.running.catch(() => {});
    if (this.closing) throw new GatewayError(409, 'Controller ownership is closing');
  }
  attach() {
    if (this.closing || this.transferred || this.running)
      throw new GatewayError(409, 'Controller ownership is closing or busy');
    this.transferred = true;
    clearTimeout(this.timer); clearInterval(this.neutralTimer);
    return { nextNativeSequence: this.nextNativeSequence, lastClientSequence: this.lastClientSequence };
  }
  takeSequence() {
    const sequence = this.nextNativeSequence;
    this.nextNativeSequence = (sequence + 1) >>> 0;
    return sequence;
  }
  keepAlive() {
    if (this.closing || this.transferred || this.attaching || this.running) return;
    this.running = bufferedUpstream(this.config, { method: 'POST', path: '/input/state',
      headers: nativeControlHeaders(this.token),
      body: Buffer.from(`${this.takeSequence()},0,128,128,128,128,0,0`, 'ascii') })
      .then(result => { if (result.status !== 200) this.close('native_rejected'); })
      .catch(() => { this.close('native_rejected'); })
      .finally(() => { this.running = null; if (this.closing) this.cleanup(); });
  }
  async state(body) {
    if (this.closing || this.transferred || this.attaching || this.running)
      throw new GatewayError(409, 'Controller ownership is closing or busy');
    const state = parseControl(body);
    if (this.lastClientSequence !== null && !followsSequence(state.sequence, this.lastClientSequence))
      throw new GatewayError(409, 'Controller sequence is not increasing');
    this.lastClientSequence = state.sequence;
    const nativeBody = Buffer.from(`${this.takeSequence()}${state.text.slice(state.text.indexOf(','))}`, 'ascii');
    // HTTP clients remain supported, but idle ownership still expires. Cleanup waits for this POST.
    const request = bufferedUpstream(this.config, { method: 'POST', path: '/input/state',
      headers: nativeControlHeaders(this.token), body: nativeBody });
    this.running = request;
    try {
      const result = await request;
      if (result.status === 200) this.renew();
      return result;
    } finally {
      this.running = null;
      if (this.closing) this.cleanup();
    }
  }
  close(reason = 'client_disconnected') {
    if (this.transferred) return Promise.resolve(null);
    if (!this.closing) {
      this.closing = true; clearTimeout(this.timer); clearInterval(this.neutralTimer);
      this.diagnostic('owner_lease_closed', { reason });
      if (!this.running) this.cleanup();
    }
    return this.done;
  }
  async cleanup() {
    if (this.cleaning) return;
    this.cleaning = true;
    let result;
    try {
      result = await bufferedUpstream(this.config, { method: 'POST', path: '/input/disconnect',
        headers: nativeControlHeaders(this.token) });
    } catch {
      result = { status: 502, type: 'application/json', body: Buffer.from('{"error":"PS5 controller disconnect was not verified"}') };
    }
    this.diagnostic('owner_lease_cleanup', { status: result.status });
    this.onDone(this, result);
    this.resolveDone(result);
  }
}
