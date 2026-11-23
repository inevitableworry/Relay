// SPDX-License-Identifier: MIT
import http from 'node:http';
import { GatewayError } from './protocol.mjs';

function timeoutError() {
  const error = new GatewayError(504, 'PS5 service did not respond in time', 'ETIMEDOUT');
  return error;
}

export function openUpstream(config, { method = 'GET', path, headers = {}, body = Buffer.alloc(0), timeoutMs = config.timeouts.requestMs, signal }) {
  return new Promise((resolve, reject) => {
    const forwarded = { ...headers };
    if (method === 'POST') {
      forwarded['Content-Type'] = 'text/plain; charset=us-ascii';
      forwarded['Content-Length'] = body.length;
    }
    const request = http.request({ hostname: config.ps5Host, port: config.ps5Port,
      method, path, headers: forwarded, agent: false, signal, maxHeaderSize: 16384 });
    const timer = setTimeout(() => request.destroy(timeoutError()), timeoutMs);
    request.once('response', response => {
      clearTimeout(timer);
      if (response.statusCode >= 300 && response.statusCode < 400) {
        response.destroy();
        reject(new GatewayError(502, 'PS5 redirects are not permitted', 'UPSTREAM_REDIRECT'));
        return;
      }
      resolve({ request, response });
    });
    request.once('error', error => { clearTimeout(timer); reject(error); });
    request.end(body);
  });
}

export async function bufferedUpstream(config, options) {
  const controller = new AbortController();
  const timeoutMs = options.timeoutMs ?? config.timeouts.requestMs;
  const timer = setTimeout(() => controller.abort(timeoutError()), timeoutMs);
  try {
    const { response } = await openUpstream(config, { ...options, signal: controller.signal, timeoutMs });
    const chunks = [];
    let length = 0;
    for await (const chunk of response) {
      length += chunk.length;
      if (length > 65536) {
        response.destroy();
        throw new GatewayError(502, 'PS5 response exceeded the protocol limit');
      }
      chunks.push(chunk);
    }
    return { status: response.statusCode, type: response.headers['content-type'] ?? 'application/json', body: Buffer.concat(chunks, length) };
  } catch (error) {
    if (controller.signal.aborted) throw timeoutError();
    throw error;
  } finally { clearTimeout(timer); }
}

export async function probeService(config, timeoutMs = config.timeouts.probeMs) {
  const result = await bufferedUpstream(config, { path: '/config.json', timeoutMs });
  if (result.status !== 200) throw new GatewayError(502, 'A service answered but is not ready for Relay', 'UPSTREAM_ANSWERED');
  let value;
  try { value = JSON.parse(result.body.toString('utf8')); } catch { /* Answering HTTP must never authorize injection. */ }
  if (value?.platform !== 'PS5' || !Number.isInteger(value.width) || value.width < 0 || value.width > 8192 ||
      !Number.isInteger(value.height) || value.height < 0 || value.height > 4320) {
    throw new GatewayError(502, 'A service answered with an incompatible configuration', 'UPSTREAM_ANSWERED');
  }
  return value;
}

export function explicitlyRefused(error) {
  return error?.code === 'ECONNREFUSED' && (!Array.isArray(error.errors) ||
    (error.errors.length > 0 && error.errors.every(e => e.code === 'ECONNREFUSED')));
}
