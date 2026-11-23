// SPDX-License-Identifier: MIT
import { performance } from 'node:perf_hooks';

/** Bounded, allowlisted metadata. Never accepts a request, URL, body, token, or Error object. */
export function createDiagnostics(sink = () => {}, clock = () => performance.now()) {
  let windowAt = clock(), count = 0, suppressed = 0;
  return (event, fields = {}) => {
    if (clock() - windowAt >= 60000) {
      if (suppressed) { try { sink({ event: 'diagnostics_suppressed', count: suppressed }); } catch {} }
      windowAt = clock(); count = 0; suppressed = 0;
    }
    if (count++ >= 120) { suppressed++; return; }
    if (!/^[a-z_]{1,48}$/.test(event)) return;
    const entry = { event };
    for (const key of ['id', 'status', 'durationMs', 'code', 'count']) {
      if (Number.isSafeInteger(fields[key]) && fields[key] >= 0) entry[key] = fields[key];
    }
    if (['/frames', '/audio', '/input/connect', '/input/disconnect'].includes(fields.route)) entry.route = fields.route;
    const reasons = new Set(['client_disconnected', 'client_response_lost', 'attach_timeout', 'gateway_shutdown',
      'peer_silent', 'socket_closed', 'socket_error', 'queue_overflow', 'state_expired', 'invalid_state',
      'sequence_invalid', 'native_rejected', 'ack_backpressure', 'upstream_error', 'downstream_closed',
      'upstream_ended', 'request_failed']);
    if (reasons.has(fields.reason)) entry.reason = fields.reason;
    try { sink(entry); } catch { /* Diagnostics must not change streaming behavior. */ }
  };
}
