#!/usr/bin/env python3
"""Offline tests only: never invokes ADB or a real device."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('relay_measurement', Path(__file__).with_name('measure-relay-stream.py'))
measure = importlib.util.module_from_spec(spec)
spec.loader.exec_module(measure)


def log(message, epoch='1763827201.000001', pid=100, tag='RelayJbMetrics', tid=200):
    return f'{epoch}  {pid:5d} {tid:5d} D {tag}: {message}\n'


def sample(t=1000, frames=60, fps=60, width=1920, height=1080, video=4, audio=3.072, age=10, lag=0, rtt=3, underruns=0, **header):
    return log(f't={t} width={width} height={height} frames={frames} fps={fps:.2f} video_mbps={video:.3f} audio_mbps={audio:.3f} age_ms={age} lag_ms={lag} rtt_ms={rtt} underruns={underruns}', **header)


class TelemetryTests(unittest.TestCase):
    def test_unavailable_status_keeps_real_media_metrics_without_a_fake_zero_rtt(self):
        result = self.analyze([sample(rtt=-1), sample(t=2000, frames=120, rtt=25, epoch='1763827202.000001')])
        self.assertEqual(result['overall']['sample_count'], 2)
        self.assertEqual(result['overall']['fps']['median'], 60)
        self.assertEqual(result['overall']['rtt_ms']['count'], 1)
        self.assertEqual(result['overall']['rtt_ms']['median'], 25)
        self.assertEqual(result['overall']['status_unavailable_samples'], 1)
        self.assertEqual(self.analyze([sample(rtt=-2)])['overall']['sample_count'], 0)

    def test_connection_causes_and_missed_status_counts_are_saved_separately(self):
        result = self.analyze([log('event=status_missed route=remote stage=status reason=timeout attempt=0 count=5', tag='RelayJbConnection'), sample(rtt=-1)])
        self.assertEqual(result['connection_events'][0]['count'], 5)
        self.assertEqual(result['connection_events'][0]['route'], 'remote')
        self.assertEqual(result['overall']['sample_count'], 1)

    def analyze(self, lines, pids=None, anchor='1763827200.000000'):
        return measure.analyze_lines(lines, {100} if pids is None else pids, anchor)

    def test_actual_dimensions_and_sample_statistics_retain_stalls(self):
        samples = [sample(t=(i + 1) * 1000, frames=(i + 1) * 60,
                          fps=fps, video=i + 1, age=age, lag=lag, underruns=underruns,
                          epoch=f'176382720{i + 1}.000001')
                   for i, (fps, age, lag, underruns) in enumerate([(0, 5, 0, 0), (30, 10, 1, 2), (60, 100, 2, 0), (60, 15, 50, 1)])]
        result = self.analyze(samples)
        self.assertEqual(result['actual_dimensions'][0]['width'], 1920)
        self.assertEqual(result['actual_dimensions'][0]['height'], 1080)
        overall = result['overall']
        self.assertEqual(overall['sample_count'], 4)
        self.assertEqual(overall['fps']['median'], 45)
        self.assertAlmostEqual(overall['fps']['p10'], 9)
        self.assertEqual(overall['zero_fps_samples'], 1)
        self.assertEqual(overall['video_mbps']['median'], 2.5)
        self.assertAlmostEqual(overall['age_ms']['p95'], 87.25)
        self.assertEqual(overall['age_ms']['max'], 100)
        self.assertAlmostEqual(overall['lag_ms']['p95'], 42.8)
        self.assertEqual(overall['audio_underruns_sum'], 3)  # Logged values are already deltas.
        self.assertEqual(result['segments'][0]['observed_counter_increase'], 180)

    def test_empty_invalid_or_old_metrics_never_become_success_or_zero_statistics(self):
        bad = sample().replace('fps=60.00', 'fps=NaN')
        result = self.analyze([bad, sample(epoch='1763827200.000000'), sample(pid=999)])
        self.assertEqual(result['overall']['sample_count'], 0)
        self.assertIsNone(result['overall']['fps']['median'])
        self.assertIsNone(result['overall']['audio_underruns_sum'])
        self.assertEqual(result['rejected_counts']['malformed_events'], 1)
        self.assertEqual(result['rejected_counts']['at_or_before_capture_anchor'], 1)
        self.assertEqual(result['rejected_counts']['unverified_process'], 1)
        self.assertEqual(measure.measurement_status(result, {'complete': True, 'errors': []}), ('unverified', 2))

    def test_precision_boundary_and_exact_duplicate_are_excluded(self):
        line = sample(epoch='1763827200.123457')
        result = self.analyze([sample(epoch='1763827200.123456'), line, line], anchor='1763827200.123456')
        self.assertEqual(result['overall']['sample_count'], 1)
        self.assertEqual(result['rejected_counts']['duplicate_events'], 1)
        self.assertEqual(result['metric_samples'][0]['epoch'], '1763827200.123457')

    def test_counter_resets_and_profile_requests_are_separate_from_actual_dimensions(self):
        lines = [
            log('start auto=true height=1080 bitrate=20000000 minqp=20', tag='RelayJbQuality'),
            sample(epoch='1763827202.0', frames=600, t=10000),
            log('reconnect height=720 bitrate=12000000 minqp=22 reason=network backlog rose', epoch='1763827203.0', tag='RelayJbQuality'),
            sample(epoch='1763827204.0', frames=630, t=11000),  # Old decoder still reports actual 1080p.
            sample(epoch='1763827205.0', width=1280, height=720, frames=30, t=12000),
            sample(epoch='1763827206.0', pid=101, width=1280, height=720, frames=10, t=13000),
        ]
        result = self.analyze(lines, {100, 101})
        self.assertEqual(len(result['segments']), 4)
        self.assertEqual(result['segments'][1]['requested_profile_index'], 1)
        self.assertEqual(result['segments'][1]['height'], 1080)
        self.assertIn('frame_counter_reset', result['segments'][2]['start_reasons'])
        self.assertIn('decoded_dimensions_changed', result['segments'][2]['start_reasons'])
        self.assertIn('pid_changed', result['segments'][3]['start_reasons'])
        self.assertEqual(result['profile_transitions'][1]['reason'], 'network backlog rose')

    def test_quality_nullable_values_and_strict_metric_validation(self):
        event = measure.parse_event(measure.parse_header(log('start auto=false height=null bitrate=null minqp=null', tag='RelayJbQuality')))
        self.assertIsNone(event['height'])
        self.assertFalse(event['auto'])
        for altered in [sample().replace('frames=60', 'frames=-1'), sample().replace(' fps=60.00', ''),
                        sample().strip() + ' fps=60.00', sample().replace('video_mbps=4.000', 'video_mbps=Infinity')]:
            with self.subTest(altered=altered):
                self.assertEqual(self.analyze([altered])['overall']['sample_count'], 0)

    def test_menu_audio_counters_are_not_summed_as_interval_deltas(self):
        lines = [log(f'Continuous PCM loop {loop} queued; underruns={counter}', epoch=f'17638272{index:02d}.0', tag='PortalMenuAudio')
                 for index, (loop, counter) in enumerate([(10, 2), (11, 5), (12, 6), (1, 3), (2, 4)], 1)]
        menu = self.analyze(lines)['menu_audio']
        self.assertEqual(menu['sample_count'], 5)
        self.assertEqual(len(menu['runs']), 2)
        self.assertEqual(menu['observed_underrun_counter_increase'], 5)
        self.assertEqual([run['first_counter'] for run in menu['runs']], [2, 3])
        self.assertTrue(menu['first_counters_may_predate_capture'])

    def test_pid_errors_are_not_fabricated_app_exits(self):
        observations = [dict(at_seconds=0, pids=[100]), dict(at_seconds=2, pids=[], error='pidof_timeout'),
                        dict(at_seconds=4, pids=[100]), dict(at_seconds=6, pids=[]), dict(at_seconds=8, pids=[101])]
        changes = measure.process_changes(observations)
        self.assertEqual(len(changes), 2)
        self.assertEqual(changes[0], dict(at_seconds=6, from_pids=[100], to_pids=[]))
        self.assertEqual(changes[1]['to_pids'], [101])

    def test_partial_capture_is_not_complete_and_stalled_sample_is_retained(self):
        result = self.analyze([sample(fps=0, frames=0, width=0, height=0)])
        self.assertEqual(result['overall']['sample_count'], 1)
        self.assertEqual(result['overall']['positive_frame_activity_samples'], 0)
        self.assertEqual(result['overall']['zero_dimension_samples'], 1)
        self.assertEqual(measure.measurement_status(result, {'complete': False, 'errors': ['connection lost']}), ('partial', 2))

    def test_commands_are_only_read_only_pidof_and_logcat_with_new_log_anchor(self):
        commands = [measure.adb_command('adb', 'device', operation, '1763827200.123456') for operation in ('pid', 'anchor', 'stream')]
        self.assertEqual(commands[0][3:], ['shell', 'pidof', measure.PACKAGE])
        for command in commands[1:]:
            self.assertEqual(command[3], 'logcat')
            self.assertNotIn('-c', command)
            self.assertNotIn('-G', command)
            self.assertNotIn('-f', command)
        self.assertIn('-T', commands[2])
        self.assertIn('1763827200.123456', commands[2])
        self.assertEqual(commands[2][-1], '*:S')
        with self.assertRaises(ValueError):
            measure.adb_command('adb', 'device', 'input')


if __name__ == '__main__':
    unittest.main(verbosity=2)
