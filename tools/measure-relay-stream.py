#!/usr/bin/env python3
"""Read-only ADB telemetry capture. No UI, input, settings or PS5 commands.

Metrics are emitted only by the application's DEBUG build. A report measures the
logged samples; it does not declare a quality profile or the whole app successful.
"""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
from datetime import datetime, timezone
from decimal import Decimal
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import statistics
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'com.portalstyle.remoteplay'
TAGS = ('RelayJbMetrics', 'RelayJbQuality', 'RelayJbConnection', 'PortalMenuAudio')
LOG_FORMAT = 'threadtime,epoch,usec'
MAX_RAW_BYTES = 64 * 1024 * 1024
HEADER = re.compile(r'^\s*(?P<epoch>\d{9,}(?:\.\d{1,9})?)\s+(?P<pid>\d+)\s+(?P<tid>\d+)\s+(?P<level>[VDIWEFS])\s+(?P<tag>[^:]+?)\s*:\s?(?P<message>.*)$')
METRIC_INTS = ('t', 'width', 'height', 'frames', 'age_ms', 'lag_ms', 'rtt_ms', 'underruns')
METRIC_FLOATS = ('fps', 'video_mbps', 'audio_mbps')
UNSIGNED = re.compile(r'^\d+$')
DECIMAL = re.compile(r'^\d+(?:\.\d+)?$')


def utc_now():
    return datetime.now(timezone.utc).isoformat(timespec='milliseconds')


def parse_header(line):
    match = HEADER.fullmatch(line.rstrip('\r\n'))
    if not match:
        return None
    result = match.groupdict()
    result['pid'] = int(result['pid'])
    result['tid'] = int(result['tid'])
    result['tag'] = result['tag'].strip()
    return result


def pairs(message):
    result = {}
    for token in message.split():
        key, separator, value = token.partition('=')
        if not separator or not key or key in result or not value:
            raise ValueError('Malformed or duplicate key/value')
        result[key] = value
    return result


def unsigned(value):
    if not UNSIGNED.fullmatch(value) or len(value) > 19:
        raise ValueError('Invalid nonnegative integer')
    return int(value)


def parse_event(header):
    """Strictly parse the current emitters; callers retain rejected raw evidence."""
    event = {key: header[key] for key in ('epoch', 'pid', 'tid', 'level', 'tag')}
    message = header['message']
    if header['tag'] == 'RelayJbMetrics':
        fields = pairs(message)
        if set(fields) != set(METRIC_INTS + METRIC_FLOATS):
            raise ValueError('Missing or unknown metric field')
        event.update({key: (-1 if key == 'rtt_ms' and fields[key] == '-1' else unsigned(fields[key])) for key in METRIC_INTS})
        for key in METRIC_FLOATS:
            if not DECIMAL.fullmatch(fields[key]):
                raise ValueError('Invalid finite nonnegative metric')
            value = float(fields[key])
            if not math.isfinite(value):
                raise ValueError('Non-finite metric')
            event[key] = value
        event['kind'] = 'metrics'
    elif header['tag'] == 'RelayJbQuality':
        action, separator, remainder = message.partition(' ')
        if action not in ('start', 'reconnect') or not separator:
            raise ValueError('Unknown quality event')
        fields_part, reason_separator, reason = remainder.partition(' reason=')
        fields = pairs(fields_part)
        if not {'height', 'bitrate', 'minqp'} <= set(fields) or set(fields) - {'height', 'bitrate', 'minqp', 'auto'}:
            raise ValueError('Invalid quality fields')
        for key in ('height', 'bitrate', 'minqp'):
            event[key] = None if fields[key] == 'null' else unsigned(fields[key])
        if 'auto' in fields:
            if fields['auto'] not in ('true', 'false'):
                raise ValueError('Invalid automatic quality flag')
            event['auto'] = fields['auto'] == 'true'
        event.update(kind='quality', action=action, reason=reason if reason_separator else None)
    elif header['tag'] == 'RelayJbConnection':
        fields = pairs(message)
        if set(fields) != {'event', 'route', 'stage', 'reason', 'attempt', 'count'}:
            raise ValueError('Missing or unknown connection field')
        if fields['route'] not in ('lan', 'remote') or fields['stage'] not in ('connect', 'video', 'audio', 'controller', 'status', 'cleanup'):
            raise ValueError('Invalid connection route or stage')
        if any(not re.fullmatch(r'[a-z0-9_]{1,48}', fields[key]) for key in ('event', 'reason')):
            raise ValueError('Invalid connection identifier')
        event.update(kind='connection', **{key: fields[key] for key in ('event', 'route', 'stage', 'reason')},
                     attempt=unsigned(fields['attempt']), count=unsigned(fields['count']))
    elif header['tag'] == 'PortalMenuAudio':
        match = re.fullmatch(r'Continuous PCM loop (\d+) queued; underruns=(\d+)', message)
        if match:
            event.update(kind='menu_audio', loop=unsigned(match[1]), underrun_counter=unsigned(match[2]))
        else:
            event.update(kind='other_menu_audio', message=message)
    else:
        return None
    return event


def percentile(values, percent):
    """Linear interpolation at (n-1)*p, including true zero/stall samples."""
    if not values:
        return None
    ordered = sorted(values)
    rank = (len(ordered) - 1) * percent / 100
    lo, hi = math.floor(rank), math.ceil(rank)
    return ordered[lo] + (ordered[hi] - ordered[lo]) * (rank - lo)


def numeric_summary(values):
    if not values:
        return {'count': 0, 'median': None, 'p10': None, 'p95': None, 'max': None, 'min': None, 'mean': None}
    return {'count': len(values), 'median': statistics.median(values), 'p10': percentile(values, 10),
            'p95': percentile(values, 95), 'max': max(values), 'min': min(values), 'mean': statistics.fmean(values)}


def metric_summary(samples):
    result = {'sample_count': len(samples), 'fps': numeric_summary([s['fps'] for s in samples])}
    for key in ('video_mbps', 'audio_mbps', 'age_ms', 'lag_ms', 'rtt_ms'):
        result[key] = numeric_summary([s[key] for s in samples if key != 'rtt_ms' or s[key] >= 0])
    result['status_unavailable_samples'] = sum(s['rtt_ms'] == -1 for s in samples)
    # PbsStreamSession subtracts lastUnderruns before logging. These are DELTAS.
    result['audio_underruns_sum'] = sum(s['underruns'] for s in samples) if samples else None
    result['zero_fps_samples'] = sum(s['fps'] == 0 for s in samples)
    result['zero_dimension_samples'] = sum(s['width'] == 0 or s['height'] == 0 for s in samples)
    result['positive_frame_activity_samples'] = sum(s['width'] > 0 and s['height'] > 0 and s['frames'] > 0 and s['fps'] > 0 for s in samples)
    return result


def process_changes(observations):
    changes = []
    previous = None
    for item in observations:
        if item.get('error'):
            continue  # An ADB failure is not evidence that the app stopped.
        current = sorted(item['pids'])
        if previous is not None and current != previous:
            changes.append({'at_seconds': item['at_seconds'], 'from_pids': previous, 'to_pids': current})
        previous = current
    return changes


def analyze_lines(lines, allowed_pids, anchor_epoch):
    anchor = Decimal(anchor_epoch)
    events, rejected = [], Counter()
    malformed = []
    seen = set()
    for number, line in enumerate(lines, 1):
        if len(line) > 16384:
            rejected['oversized_lines'] += 1
            continue
        header = parse_header(line)
        if not header:
            if line.strip() and not line.startswith('---------'):
                rejected['unparsed_lines'] += 1
            continue
        if header['tag'] not in TAGS:
            rejected['other_tags'] += 1
            continue
        if Decimal(header['epoch']) <= anchor:
            rejected['at_or_before_capture_anchor'] += 1
            continue
        if header['pid'] not in allowed_pids:
            rejected['unverified_process'] += 1
            continue
        try:
            event = parse_event(header)
        except ValueError as error:
            rejected['malformed_events'] += 1
            if len(malformed) < 32:
                malformed.append({'line': number, 'tag': header['tag'], 'reason': str(error)})
            continue
        identity = tuple(sorted(event.items()))
        if identity in seen:
            rejected['duplicate_events'] += 1
            continue
        seen.add(identity)
        event['line'] = number
        events.append(event)

    metrics = [event for event in events if event['kind'] == 'metrics']
    profiles = [event for event in events if event['kind'] == 'quality']
    dimensions = defaultdict(list)
    for sample in metrics:
        dimensions[(sample['width'], sample['height'])].append(sample)
    segments, active_profile = [], {}
    profile_number = 0
    previous = None
    for event in events:
        if event['kind'] == 'quality':
            active_profile[event['pid']] = profile_number
            profile_number += 1
            continue
        if event['kind'] != 'metrics':
            continue
        profile = active_profile.get(event['pid'])
        reasons = []
        if previous is None:
            reasons.append('first_sample')
        else:
            if event['pid'] != previous['pid']:
                reasons.append('pid_changed')
            if event['t'] <= previous['t']:
                reasons.append('elapsed_time_reset_or_reordering')
            if event['frames'] < previous['frames']:
                reasons.append('frame_counter_reset')
            if (event['width'], event['height']) != (previous['width'], previous['height']):
                reasons.append('decoded_dimensions_changed')
            if profile != segments[-1]['requested_profile_index']:
                reasons.append('quality_request')
        if reasons:
            segments.append({'pid': event['pid'], 'width': event['width'], 'height': event['height'],
                             'start_reasons': reasons, 'requested_profile_index': profile, '_samples': []})
        segments[-1]['_samples'].append(event)
        previous = event
    for segment in segments:
        samples = segment.pop('_samples')
        gaps = [b['t'] - a['t'] for a, b in zip(samples, samples[1:])]
        segment.update(metric_summary(samples))
        segment.update(first_epoch=samples[0]['epoch'], last_epoch=samples[-1]['epoch'],
                       first_elapsed_ms=samples[0]['t'], last_elapsed_ms=samples[-1]['t'],
                       frame_counter_first=samples[0]['frames'], frame_counter_last=samples[-1]['frames'],
                       observed_counter_increase=samples[-1]['frames'] - samples[0]['frames'] if len(samples) > 1 else None,
                       max_sample_gap_ms=max(gaps) if gaps else None,
                       gaps_over_2500ms=[gap for gap in gaps if gap > 2500])

    menu = [event for event in events if event['kind'] == 'menu_audio']
    menu_runs = []
    previous = None
    for event in menu:
        if previous is None or event['pid'] != previous['pid'] or event['loop'] <= previous['loop'] or event['underrun_counter'] < previous['underrun_counter']:
            menu_runs.append({'pid': event['pid'], 'first_loop': event['loop'], 'first_counter': event['underrun_counter'],
                              'counter_increase_between_observations': 0, 'sample_count': 0})
        else:
            menu_runs[-1]['counter_increase_between_observations'] += event['underrun_counter'] - previous['underrun_counter']
        menu_runs[-1].update(last_loop=event['loop'], last_counter=event['underrun_counter'])
        menu_runs[-1]['sample_count'] += 1
        previous = event
    return {
        'overall': metric_summary(metrics),
        'actual_dimensions': [dict(width=w, height=h, **metric_summary(group)) for (w, h), group in sorted(dimensions.items())],
        'segments': segments, 'profile_transitions': profiles, 'metric_samples': metrics,
        'connection_events': [event for event in events if event['kind'] == 'connection'],
        'menu_audio': {'sample_count': len(menu), 'runs': menu_runs, 'samples': menu,
                       'observed_underrun_counter_increase': sum(run['counter_increase_between_observations'] for run in menu_runs) if menu else None,
                       'first_counters_may_predate_capture': True},
        'other_menu_audio_events': [event for event in events if event['kind'] == 'other_menu_audio'],
        'rejected_counts': dict(rejected), 'malformed_examples': malformed,
    }


def resolve_adb(explicit):
    if explicit:
        found = shutil.which(explicit)
        candidate = Path(explicit)
        if found:
            return found
        if candidate.is_file():
            return str(candidate.resolve())
        raise RuntimeError('ADB executable was not found')
    found = shutil.which('adb')
    if found:
        return found
    candidates = [Path(os.environ[name]) / 'platform-tools' / 'adb.exe' for name in ('ANDROID_SDK_ROOT', 'ANDROID_HOME') if os.environ.get(name)]
    candidates.extend([ROOT / '.tools/android-sdk/platform-tools/adb.exe', ROOT / '.tools/platform-tools/adb.exe'])
    for candidate in candidates:
        if candidate.is_file():
            return str(candidate.resolve())
    raise RuntimeError('ADB was not found; provide --adb')


def adb_command(adb, serial, operation, anchor=None):
    base = [str(adb), '-s', serial]
    if operation == 'pid':
        return base + ['shell', 'pidof', PACKAGE]
    if operation == 'anchor':
        # Keep only this record's timestamp. Unrelated message text is not saved.
        return base + ['logcat', '-b', 'main', '-d', '-t', '1', '-v', LOG_FORMAT, '*:V']
    if operation == 'stream' and anchor is not None:
        return base + ['logcat', '-b', 'main', '-v', LOG_FORMAT, '-T', anchor, *[tag + ':D' for tag in TAGS], '*:S']
    raise ValueError('Unknown read-only ADB operation')


def run_read(command, timeout=5):
    return subprocess.run(command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          timeout=timeout, creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0), check=False)


def observe_pid(adb, serial, at_seconds, timeout=5):
    result = {'at_seconds': round(at_seconds, 3), 'host_utc': utc_now(), 'pids': []}
    try:
        process = run_read(adb_command(adb, serial, 'pid'), timeout)
        out = process.stdout.decode('utf-8', errors='replace').strip()
        err = process.stderr.decode('utf-8', errors='replace').strip()
        if process.returncode == 1 and not out and not err:
            return result  # pidof's explicit "no matching process" result.
        if process.returncode != 0 or not re.fullmatch(r'\d+(?:\s+\d+)*', out):
            result['error'] = f'pidof_failed_exit_{process.returncode}'
            result['stderr'] = err[:2000]
            result['unexpected_stdout'] = out[:1000]
        else:
            result['pids'] = sorted({int(value) for value in out.split()})
    except subprocess.TimeoutExpired:
        result['error'] = 'pidof_timeout'
    except OSError:
        result['error'] = 'pidof_could_not_run'
    return result


def stop_own_process(process):
    if process is None or process.poll() is not None:
        return
    process.terminate()  # Only this collector's local adb logcat client.
    try:
        process.wait(timeout=3)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=3)


def measurement_status(analysis, capture):
    if not analysis['overall']['sample_count']:
        return 'unverified', 2
    if not capture['complete'] or capture['errors']:
        return 'partial', 2
    return 'measured', 0


def collect(args):
    output = Path(args.output_dir).resolve()
    output.mkdir(parents=True, exist_ok=True)
    slug = re.sub(r'[^A-Za-z0-9_-]+', '-', args.label).strip('-')[:64] or 'stream'
    stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S.%fZ')
    prefix = output / f'relay-stream-{stamp}-{slug}'
    raw_path, stderr_path, report_path = (Path(str(prefix) + suffix) for suffix in ('.log', '.stderr.log', '.json'))
    capture = {'serial': args.serial, 'package': PACKAGE, 'label': args.label, 'requested_seconds': args.seconds,
               'started_utc': None, 'finished_utc': None, 'actual_seconds': 0, 'anchor_epoch': None,
               'complete': False, 'pid_observations': [], 'errors': []}
    process, started = None, None
    with raw_path.open('xb') as raw, stderr_path.open('xb') as stderr:
        try:
            adb = resolve_adb(args.adb)
            capture['adb_executable'] = adb
            initial = observe_pid(adb, args.serial, 0)
            capture['pid_observations'].append(initial)
            if initial.get('error'):
                raise RuntimeError(initial['error'])
            anchor_result = run_read(adb_command(adb, args.serial, 'anchor'))
            headers = [parse_header(line) for line in anchor_result.stdout.decode('utf-8', errors='replace').splitlines()]
            epochs = [header['epoch'] for header in headers if header]
            if anchor_result.returncode != 0 or not epochs:
                stderr.write(anchor_result.stderr)
                raise RuntimeError('logcat_capture_anchor_unavailable')
            anchor = max(epochs, key=Decimal)
            capture['anchor_epoch'] = anchor
            started = time.monotonic()
            capture['started_utc'] = utc_now()
            command = adb_command(adb, args.serial, 'stream', anchor)
            capture['logcat_command'] = command
            process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=raw, stderr=stderr,
                                       creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
            print(f'Collecting read-only Relay telemetry into {raw_path}', flush=True)
            next_pid = started + 2
            deadline = started + args.seconds
            while time.monotonic() < deadline:
                now = time.monotonic()
                if process.poll() is not None:
                    raise RuntimeError(f'logcat_ended_early_exit_{process.returncode}')
                if raw_path.stat().st_size > MAX_RAW_BYTES or stderr_path.stat().st_size > 1024 * 1024:
                    raise RuntimeError('capture_log_size_limit_reached')
                if now >= next_pid:
                    observation = observe_pid(adb, args.serial, now - started, min(3, max(.2, deadline - now)))
                    capture['pid_observations'].append(observation)
                    if observation.get('error'):
                        capture['errors'].append(observation['error'])
                    next_pid = time.monotonic() + 2
                time.sleep(min(.2, max(0, deadline - time.monotonic())))
            if process.poll() is not None:
                raise RuntimeError(f'logcat_ended_early_exit_{process.returncode}')
            capture['complete'] = True
        except KeyboardInterrupt:
            capture['errors'].append('capture_interrupted')
        except (RuntimeError, OSError, subprocess.TimeoutExpired) as error:
            capture['errors'].append(str(error) if isinstance(error, RuntimeError) else type(error).__name__)
        finally:
            try:
                stop_own_process(process)
            except (OSError, subprocess.TimeoutExpired):
                capture['errors'].append('logcat_client_stop_not_verified')
            if started is not None:
                capture['actual_seconds'] = round(time.monotonic() - started, 3)
                final = observe_pid(adb, args.serial, capture['actual_seconds'])
                capture['pid_observations'].append(final)
                if final.get('error'):
                    capture['errors'].append(final['error'])
            capture['finished_utc'] = utc_now()
    allowed = {pid for observation in capture['pid_observations'] if not observation.get('error') for pid in observation['pids']}
    with raw_path.open('r', encoding='utf-8', errors='replace') as raw:
        analysis = analyze_lines(raw, allowed, capture['anchor_epoch'] or '0')
    capture['observed_pids'] = sorted(allowed)
    capture['process_changes'] = process_changes(capture['pid_observations'])
    capture['raw_log_bytes'] = raw_path.stat().st_size
    with raw_path.open('rb') as raw:
        capture['raw_log_sha256'] = hashlib.file_digest(raw, 'sha256').hexdigest()
    count = analysis['overall']['sample_count']
    status, exit_code = measurement_status(analysis, capture)
    if not count:
        capture['errors'].append('no_valid_fresh_metrics_from_observed_app_process')
    warnings = []
    if capture['process_changes']:
        warnings.append('app_process_changed_during_capture')
    if analysis['overall']['positive_frame_activity_samples'] == 0:
        warnings.append('no_positive_decoded_frame_activity_observed')
    if analysis['overall']['zero_dimension_samples']:
        warnings.append('some_samples_have_no_decoded_dimensions')
    if len(analysis['actual_dimensions']) > 1:
        warnings.append('multiple_actual_dimensions_use_per_segment_results')
    if analysis['rejected_counts'].get('malformed_events') or analysis['rejected_counts'].get('unparsed_lines'):
        warnings.append('some_telemetry_could_not_be_parsed_review_raw_log')
    if analysis['rejected_counts'].get('unverified_process'):
        warnings.append('some_tagged_logs_came_from_pids_not_observed_for_app')
    if any(segment['gaps_over_2500ms'] for segment in analysis['segments']):
        warnings.append('some_logged_sample_intervals_exceed_2500ms')
    report = {'schema_version': 1, 'status': status, 'quality_verdict': 'not_assessed',
              'capture': capture, 'analysis': analysis, 'warnings': warnings,
              'artifacts': {'raw_log': str(raw_path), 'adb_stderr': str(stderr_path), 'summary': str(report_path)},
              'interpretation': {
                  'fps': 'Logged interval decoded fps; zero samples are retained. Percentiles use linear interpolation at (n-1)*p.',
                  'media_mbps': 'Received payload megabits per second; excludes transport overhead.',
                  'age_ms': 'Time since the last frame was released to the decoder surface, not end-to-end latency.',
                  'lag_ms': 'Playback drift relative to the first presentation timestamp, not absolute console latency.',
                  'rtt_ms': 'Fresh capture-status HTTP round-trip, not controller-input latency. -1 means unavailable and is excluded from RTT aggregates; local media metrics remain valid.',
                  'stream_underruns': 'Sum of logged interval deltas. The first interval may straddle the capture start.',
                  'menu_underruns': 'Cumulative counter differences between observed loop records; first counter may predate capture.',
                  'process_scope': 'Only PIDs observed for the app via pidof are analyzed. Polling can miss a short-lived process.',
              }}
    report_path.write_text(json.dumps(report, indent=2, allow_nan=False) + '\n', encoding='utf-8')
    print(json.dumps({'status': status, 'samples': count, 'dimensions': [f"{d['width']}x{d['height']}" for d in analysis['actual_dimensions']],
                      'fps_median': analysis['overall']['fps']['median'], 'fps_p10': analysis['overall']['fps']['p10'],
                      'process_change_count': len(capture['process_changes']), 'summary': str(report_path)}, allow_nan=False), flush=True)
    return exit_code


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True, help='Already connected ADB device serial; this tool never connects or pairs devices.')
    parser.add_argument('--seconds', type=float, default=45, help='Capture duration from 1 to 3600 seconds (default: 45).')
    parser.add_argument('--label', required=True, help='Human label for the selected quality/test condition; never treated as proof of that quality.')
    parser.add_argument('--output-dir', default=str(ROOT / 'docs/device-results'))
    parser.add_argument('--adb', help='Explicit adb executable; otherwise PATH or configured SDK locations.')
    args = parser.parse_args(argv)
    if not math.isfinite(args.seconds) or not 1 <= args.seconds <= 3600:
        parser.error('--seconds must be between 1 and 3600')
    if not args.serial or len(args.serial) > 256 or any(ord(char) < 33 or ord(char) > 126 for char in args.serial):
        parser.error('--serial must be a non-space printable ADB serial')
    if not args.label.strip() or len(args.label) > 200:
        parser.error('--label must contain 1 to 200 characters')
    return collect(args)


if __name__ == '__main__':
    sys.exit(main())
