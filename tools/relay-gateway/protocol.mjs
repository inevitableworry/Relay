// SPDX-License-Identifier: MIT
export class GatewayError extends Error {
  constructor(status, message, code = 'GATEWAY_ERROR') { super(message); this.status = status; this.code = code; }
}

const GET_PATHS = new Set(['/config.json', '/status.json', '/input/status', '/frames', '/audio']);
const POST_PATHS = new Set(['/video/keyframe', '/input/connect', '/input/state', '/input/disconnect', '/relay/start']);

/** Match the raw request target, never a normalized URL that could hide encoded paths. */
export function routeFor(method, target) {
  if (typeof target !== 'string' || target.length > 512 || !target.startsWith('/') ||
      target.startsWith('//') || /[\\#\x00-\x20\x7f%]/.test(target)) {
    throw new GatewayError(400, 'Invalid request target');
  }
  if (method === 'GET' && GET_PATHS.has(target)) return { path: target, media: target === '/frames' || target === '/audio' };
  if (method === 'POST' && POST_PATHS.has(target)) return { path: target, media: false };
  if (method === 'POST' && target.startsWith('/capture/profile')) {
    const match = /^\/capture\/profile\?height=(720|1080|1440|2160)(?:&bitrate=([0-9]{1,9})&minqp=([0-9]{1,2}))?$/.exec(target);
    if (!match || (match[2] !== undefined &&
        (Number(match[2]) < 1000000 || Number(match[2]) > 100000000 || Number(match[3]) < 18 || Number(match[3]) > 35))) {
      throw new GatewayError(400, 'Invalid capture profile query');
    }
    return { path: target, media: false };
  }
  const base = target.split('?')[0];
  if (GET_PATHS.has(base) || POST_PATHS.has(base) || base === '/capture/profile' || base === '/health') {
    throw new GatewayError(target.includes('?') ? 400 : 405, 'Unsupported request');
  }
  throw new GatewayError(404, 'Unknown route');
}

export function uniqueHeader(request, name) {
  let value;
  for (let i = 0; i < request.rawHeaders.length; i += 2) {
    if (request.rawHeaders[i].toLowerCase() !== name) continue;
    if (value !== undefined) return null;
    value = request.rawHeaders[i + 1];
  }
  return value;
}

export function controllerToken(request, required = true) {
  const value = uniqueHeader(request, 'x-pbs-controller');
  if (value === undefined && !required) return undefined;
  if (typeof value !== 'string' || !/^[a-fA-F0-9]{32}$/.test(value)) throw new GatewayError(400, 'Invalid controller token');
  return value;
}

export function forwardingHeaders(request, path) {
  const result = {};
  const marker = uniqueHeader(request, 'x-pbs-input');
  if (marker !== undefined) {
    if (marker !== '1') throw new GatewayError(400, 'Invalid controller marker');
    result['X-PBS-Input'] = '1';
  }
  const needsToken = path === '/input/state' || path === '/input/disconnect';
  const token = controllerToken(request, needsToken);
  if (token !== undefined) result['X-PBS-Controller'] = token;
  if (request.method === 'POST' && path.startsWith('/input/') && marker !== '1') {
    throw new GatewayError(400, 'Controller marker required');
  }
  return result;
}

export function parseControl(buffer) {
  if (!Buffer.isBuffer(buffer) || buffer.length > 256) throw new GatewayError(400, 'Invalid controller state');
  const text = buffer.toString('ascii');
  if (buffer.some(byte => byte > 127) || !/^[0-9]+(?:,[0-9]+){7}$/.test(text)) {
    throw new GatewayError(400, 'Invalid controller state');
  }
  const values = text.split(',').map(Number);
  if (values.some((v, i) => !Number.isSafeInteger(v) || v < 0 || v > (i < 2 ? 0xffffffff : 255))) {
    throw new GatewayError(400, 'Controller state is out of range');
  }
  return { text, sequence: values[0], buttons: values[1] };
}

export function followsSequence(next, previous) {
  const delta = (next - previous) >>> 0;
  return delta > 0 && delta < 0x80000000;
}

export function nativeControlHeaders(token) {
  return { 'X-PBS-Input': '1', 'X-PBS-Controller': token };
}
