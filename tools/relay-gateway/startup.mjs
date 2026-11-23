// SPDX-License-Identifier: MIT
import { open, mkdir } from 'node:fs/promises';
import path from 'node:path';
import net from 'node:net';
import { createHash, randomUUID, timingSafeEqual } from 'node:crypto';
import { performance } from 'node:perf_hooks';
import { setTimeout as delay } from 'node:timers/promises';
import { GatewayError } from './protocol.mjs';
import { probeService, explicitlyRefused } from './upstream.mjs';

async function verifiedPayload(pin) {
  const handle = await open(pin.file, 'r');
  try {
    const stat = await handle.stat();
    if (!stat.isFile() || stat.size !== pin.size) throw new GatewayError(503, 'Pinned payload size check failed');
    const bytes = Buffer.alloc(pin.size);
    let offset = 0;
    while (offset < bytes.length) {
      const result = await handle.read(bytes, offset, bytes.length - offset, offset);
      if (!result.bytesRead) throw new GatewayError(503, 'Pinned payload read was incomplete');
      offset += result.bytesRead;
    }
    const extra = await handle.read(Buffer.alloc(1), 0, 1, bytes.length);
    if (extra.bytesRead || bytes[0] !== 0x7f || bytes[1] !== 0x45 || bytes[2] !== 0x4c || bytes[3] !== 0x46 ||
        !timingSafeEqual(createHash('sha256').update(bytes).digest(), Buffer.from(pin.sha256, 'hex'))) {
      throw new GatewayError(503, 'Pinned payload integrity check failed');
    }
    return bytes;
  } finally { await handle.close(); }
}

function transfer(config, bytes) {
  return new Promise((resolve, reject) => {
    const socket = new net.Socket();
    let done = false;
    const finish = error => {
      if (done) return;
      done = true;
      clearTimeout(timer);
      socket.destroy();
      if (error) reject(error); else resolve();
    };
    const timer = setTimeout(() => finish(new Error('Transfer timeout')), config.timeouts.uploadMs);
    socket.on('error', finish);
    socket.on('close', () => { if (!done) finish(new Error('Transfer closed early')); });
    socket.connect(config.payload.loaderPort, config.ps5Host, () => {
      socket.setNoDelay(true);
      socket.end(bytes, () => finish());
    });
  });
}

/** One durable journal file is one operator-approved automatic startup opportunity. */
export function createStarter(config) {
  let attempt = null;
  async function run() {
    for (let probe = 0; probe < 2; probe++) {
      try {
        await probeService(config);
        return { state: 'running' };
      } catch (error) {
        if (!explicitlyRefused(error)) throw new GatewayError(error.status ?? 502,
          'PS5 absence was not confirmed; no payload was sent');
      }
    }
    if (!config.payload) throw new GatewayError(501, 'Automatic payload startup is not configured');
    let bytes;
    try { bytes = await verifiedPayload(config.payload); }
    catch (error) { throw new GatewayError(503, error instanceof GatewayError ? error.message : 'Pinned payload could not be verified'); }
    try { await mkdir(path.dirname(config.startupJournal), { recursive: true, mode: 0o700 }); }
    catch { throw new GatewayError(503, 'Startup journal could not be created; no payload was sent'); }
    let journal;
    try { journal = await open(config.startupJournal, 'wx', 0o600); }
    catch (error) {
      if (error.code === 'EEXIST') throw new GatewayError(409, 'A previous startup attempt is recorded; local review is required');
      throw new GatewayError(503, 'Startup journal could not be created; no payload was sent');
    }
    const record = async (phase, details = {}) => {
      await journal.writeFile(JSON.stringify({ phase, time: new Date().toISOString(), ...details }) + '\n');
      await journal.sync();
    };
    try {
      // This must be durable BEFORE connecting to the ELF loader. Any crash, truncated
      // journal or ambiguous upload therefore leaves a file that blocks another attempt.
      await record('armed', { attempt: randomUUID(), host: config.ps5Host, capturePort: config.ps5Port,
        loaderPort: config.payload.loaderPort, payloadSha256: config.payload.sha256, payloadSize: bytes.length,
        confirmedRefusals: 2 });
      try {
        await probeService(config); // Fresh, independent socket after hashing and durable journal creation.
        await record('service-already-running');
        return { state: 'running' };
      } catch (error) {
        if (!explicitlyRefused(error)) {
          await record('final-probe-not-refused');
          throw new GatewayError(502, 'PS5 absence was not confirmed; no payload was sent');
        }
      }
      await record('absence-confirmed', { confirmedRefusals: 3 });
      try {
        await transfer(config, bytes);
        await record('local-transfer-finished'); // This is not a remote execution acknowledgement.
      } catch {
        await record('transfer-unverified');
        throw new GatewayError(503, 'Payload transfer was not verified; local review is required before another attempt');
      }
      const deadline = performance.now() + config.timeouts.readyMs;
      while (performance.now() < deadline) {
        try {
          await probeService(config, Math.max(1, Math.min(config.timeouts.probeMs, Math.ceil(deadline - performance.now()))));
          await record('service-verified');
          return { state: 'started' };
        } catch (error) {
          if (error.code === 'UPSTREAM_ANSWERED' || error.code === 'UPSTREAM_REDIRECT') break;
        }
        await delay(Math.min(250, Math.max(0, deadline - performance.now())));
      }
      await record('startup-unverified');
      throw new GatewayError(503, 'Payload startup was not verified; local review is required before another attempt');
    } catch (error) {
      if (error instanceof GatewayError) throw error;
      throw new GatewayError(503, 'Startup journal or transfer failed; local review is required');
    } finally { await journal.close().catch(() => {}); }
  }
  return {
    start() {
      if (!attempt) attempt = run().finally(() => { attempt = null; });
      return attempt;
    },
    async settle() { if (attempt) await attempt.catch(() => {}); },
  };
}
