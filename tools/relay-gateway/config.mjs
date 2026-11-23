// SPDX-License-Identifier: MIT
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import net from 'node:net';

function integer(value, name, min, max) {
  if (!Number.isSafeInteger(value) || value < min || value > max) {
    throw new Error(`Invalid ${name}`);
  }
  return value;
}

function knownKeys(value, allowed, name) {
  if (!value || typeof value !== 'object' || Array.isArray(value) ||
      Object.keys(value).some(key => !allowed.includes(key))) throw new Error(`Invalid ${name}`);
}

export function validateConfig(input) {
  knownKeys(input, ['ps5Host', 'ps5Port', 'bearerToken', 'payload', 'startupJournal', 'timeouts'], 'configuration');
  const host = input.ps5Host;
  if (typeof host !== 'string' || host.length > 253 ||
      (!net.isIP(host) && !/^(?=.{1,253}$)[a-zA-Z0-9](?:[a-zA-Z0-9.-]*[a-zA-Z0-9])?$/.test(host))) {
    throw new Error('Invalid ps5Host');
  }
  const bearer = input.bearerToken;
  if (typeof bearer !== 'string' || !/^[\x21-\x7e]{32,512}$/.test(bearer)) {
    throw new Error('bearerToken must contain at least 32 random printable characters');
  }
  const ps5Port = integer(input.ps5Port, 'ps5Port', 1, 65535);
  let payload = null;
  if (input.payload != null) {
    const p = input.payload;
    knownKeys(p, ['file', 'sha256', 'size', 'loaderPort', 'capturePort'], 'payload configuration');
    if (!p || typeof p !== 'object' || typeof p.file !== 'string' || !path.isAbsolute(p.file) ||
        typeof p.sha256 !== 'string' || !/^[a-fA-F0-9]{64}$/.test(p.sha256)) {
      throw new Error('Invalid pinned payload file or SHA-256');
    }
    payload = Object.freeze({
      file: p.file,
      sha256: p.sha256.toLowerCase(),
      size: integer(p.size, 'payload.size', 64, 8 * 1024 * 1024),
      loaderPort: integer(p.loaderPort, 'payload.loaderPort', 1, 65535),
      capturePort: integer(p.capturePort, 'payload.capturePort', 1, 65535),
    });
    if (payload.capturePort !== ps5Port) throw new Error('Pinned payload capturePort must equal ps5Port');
    if (typeof input.startupJournal !== 'string' || !path.isAbsolute(input.startupJournal) ||
        path.resolve(input.startupJournal) === path.resolve(payload.file)) {
      throw new Error('Invalid startupJournal path');
    }
  }
  const t = input.timeouts ?? {};
  knownKeys(t, ['requestMs', 'probeMs', 'uploadMs', 'readyMs'], 'timeout configuration');
  const timeouts = Object.freeze({
    requestMs: integer(t.requestMs ?? 3000, 'timeouts.requestMs', 50, 30000),
    probeMs: integer(t.probeMs ?? 4000, 'timeouts.probeMs', 50, 5000),
    uploadMs: integer(t.uploadMs ?? 10000, 'timeouts.uploadMs', 100, 30000),
    readyMs: integer(t.readyMs ?? 12000, 'timeouts.readyMs', 100, 60000),
  });
  return Object.freeze({ ps5Host: host, ps5Port, bearerToken: bearer, payload,
    startupJournal: input.startupJournal, timeouts });
}

export async function loadConfig(filename) {
  const absolute = path.resolve(filename);
  const bytes = await readFile(absolute);
  if (bytes.length > 65536) throw new Error('Configuration is too large');
  let value;
  try { value = JSON.parse(bytes.toString('utf8').replace(/^\uFEFF/, '')); }
  catch { throw new Error('Configuration must be valid JSON'); }
  const directory = path.dirname(absolute);
  if (value?.payload && typeof value.payload.file === 'string') value.payload.file = path.resolve(directory, value.payload.file);
  if (typeof value?.startupJournal === 'string') value.startupJournal = path.resolve(directory, value.startupJournal);
  return validateConfig(value);
}
