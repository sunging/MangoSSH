"""Exercise preflight against real ephemeral fixtures and verify diagnostic redaction."""
import importlib.util
import io
import json
from pathlib import Path
import socket
import subprocess
import sys
import time
import unittest
from unittest.mock import patch
from contextlib import redirect_stdout

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('support', ROOT / 'tools/device-regression-support.py')
support = importlib.util.module_from_spec(spec)
spec.loader.exec_module(support)


class DiagnosticsTest(unittest.TestCase):
    def test_reverse_mapping_parser_ignores_unrelated_text(self):
        self.assertEqual({22349: 22349}, support.mappings('emulator tcp:22349 tcp:22349\nnot a mapping'))

    def test_logs_never_copy_messages_or_credentials(self):
        text = 'FATAL EXCEPTION: secret-password\nSSHException: private-key\nTraceback (most recent call last)'
        summary = json.dumps(support.safe_log_summary(text))
        self.assertNotIn('secret-password', summary)
        self.assertNotIn('private-key', summary)
        self.assertEqual(1, support.safe_log_summary(text)['fatal_exception'])

    def test_offline_device_is_rejected_before_mutation(self):
        with patch.object(support, 'adb', return_value='offline') as adb, redirect_stdout(io.StringIO()):
            with self.assertRaises(RuntimeError): support.prepare()
            adb.assert_called_once_with('get-state')

    def test_idle_restoration_waits_for_the_asynchronous_transition(self):
        with patch.object(support, 'adb', side_effect=['IDLE', 'INACTIVE', 'ACTIVE']) as adb, \
             patch.object(support.time, 'sleep'):
            support.wait_idle('ACTIVE', 'doze-not-restored')
            self.assertEqual(3, adb.call_count)

    def test_idle_restoration_timeout_remains_fatal(self):
        with patch.object(support, 'adb', return_value='IDLE'), redirect_stdout(io.StringIO()):
            with self.assertRaises(RuntimeError): support.wait_idle('ACTIVE', 'doze-not-restored', timeout=0)

    def test_deep_idle_is_enabled_and_verified_before_forcing_it(self):
        with patch.object(support, 'check_emulator'), patch.object(support, 'checked_window_state'), \
             patch.object(support, 'adb', return_value='1') as adb, \
             patch.object(support, 'wait_idle') as wait_idle, \
             patch.object(support, 'wait_window_ready'), redirect_stdout(io.StringIO()):
            support.prepare()
        calls = adb.call_args_list
        enable = calls.index(unittest.mock.call('shell', 'dumpsys', 'deviceidle', 'enable', 'deep'))
        verify = calls.index(unittest.mock.call('shell', 'dumpsys', 'deviceidle', 'enabled', 'deep'))
        force = calls.index(unittest.mock.call('shell', 'dumpsys', 'deviceidle', 'force-idle'))
        self.assertLess(enable, verify)
        self.assertLess(verify, force)
        self.assertEqual([unittest.mock.call('IDLE', 'doze-unavailable'),
                          unittest.mock.call('ACTIVE', 'doze-not-restored')], wait_idle.call_args_list)

    def test_disabled_deep_idle_aborts_before_sleeping_or_forcing_it(self):
        def response(*args):
            return '0' if args == ('shell', 'dumpsys', 'deviceidle', 'enabled', 'deep') else '1'
        with patch.object(support, 'check_emulator'), patch.object(support, 'checked_window_state'), \
             patch.object(support, 'adb', side_effect=response) as adb, redirect_stdout(io.StringIO()):
            with self.assertRaisesRegex(RuntimeError, 'doze-disabled'): support.prepare()
        self.assertNotIn(unittest.mock.call('shell', 'input', 'keyevent', '223'), adb.call_args_list)
        self.assertNotIn(unittest.mock.call('shell', 'dumpsys', 'deviceidle', 'force-idle'), adb.call_args_list)

    def test_active_anr_is_rejected_before_network_or_doze_changes(self):
        dump = 'mCurrentFocus=Window{abc u0 Application Not Responding: com.android.systemui}\nprivate-document'
        output = io.StringIO()
        with patch.object(support, 'check_emulator'), patch.object(support, 'adb', return_value=dump) as adb, \
             redirect_stdout(output):
            with self.assertRaisesRegex(RuntimeError, 'device-anr-dialog'): support.prepare()
        adb.assert_called_once_with('shell', 'dumpsys', 'window', 'displays')
        self.assertEqual('com.android.systemui', json.loads(output.getvalue())['anr_process'])
        self.assertNotIn('private-document', output.getvalue())
        self.assertNotIn('Application Not Responding', output.getvalue())

    def test_old_anr_window_does_not_override_current_launcher_focus(self):
        dump = ('Window{old u0 Application Not Responding: com.android.systemui}\n'
                '  mCurrentFocus=Window{new u0 com.google.android.apps.nexuslauncher/.NexusLauncherActivity}')
        self.assertEqual(dict(focused=True, anr=False, anr_process=None), support.window_health(dump))

    def test_unknown_anr_title_is_rejected_without_logging_its_contents(self):
        output = io.StringIO()
        dump = 'mCurrentFocus=Window{abc u0 Application Not Responding: /private-document/secret-key}'
        with patch.object(support, 'adb', return_value=dump), redirect_stdout(output):
            with self.assertRaisesRegex(RuntimeError, 'device-anr-dialog'): support.wait_window_ready()
        self.assertIsNone(json.loads(output.getvalue())['anr_process'])
        self.assertNotIn('private-document', output.getvalue())

    def test_window_readiness_waits_for_focus_after_boot(self):
        with patch.object(support, 'adb', side_effect=[
            'mCurrentFocus=null', 'mCurrentFocus=Window{abc u0 com.google.android.apps.nexuslauncher/.NexusLauncherActivity}',
        ]) as adb, patch.object(support.time, 'sleep'), redirect_stdout(io.StringIO()):
            support.wait_window_ready()
            self.assertEqual(2, adb.call_count)

    def test_window_focus_timeout_remains_fatal(self):
        with patch.object(support, 'adb', return_value='mCurrentFocus=null'), redirect_stdout(io.StringIO()):
            with self.assertRaisesRegex(RuntimeError, 'device-window-not-ready'): support.wait_window_ready(timeout=0)

    def test_device_forward_is_removed_when_probe_fails(self):
        maps = '\n'.join(f'emulator tcp:{p} tcp:{p}' for p in support.REVERSE_PORTS)
        def adb(*args):
            if args == ('reverse', '--list'): return maps
            if args[0:2] == ('forward', 'tcp:0'): return '45678'
            return ''
        with patch.object(support, 'check_emulator'), patch.object(support, 'wait_window_ready'), \
             patch.object(support, 'adb', side_effect=adb) as calls, \
             patch.object(support, 'ssh_probe', side_effect=[None, RuntimeError('device-probe')]), redirect_stdout(io.StringIO()):
            with self.assertRaises(RuntimeError): support.probe()
            self.assertIn(unittest.mock.call('forward', '--remove', 'tcp:45678'), calls.call_args_list)

    def test_real_ssh_and_resource_fixtures_complete_handshake_and_authentication(self):
        for script, options in [('ssh-test-fixture.py', []), ('ssh-test-fixture.py', ['--legacy-algorithms']),
                                ('server-resource-fixture.py', ['--mode', 'linux']),
                                ('server-resource-fixture.py', ['--mode', 'stall'])]:
            with self.subTest(script=script, options=options):
                with socket.socket() as listener:
                    listener.bind(('127.0.0.1', 0))
                    port = listener.getsockname()[1]
                process = subprocess.Popen([sys.executable, str(ROOT / 'tools' / script), '--port', str(port), *options],
                                           stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                try:
                    deadline = time.monotonic() + 15
                    while True:
                        if process.poll() is not None: self.fail('Fixture exited before readiness')
                        try:
                            with socket.create_connection(('127.0.0.1', port), .2): break
                        except OSError:
                            if time.monotonic() >= deadline: self.fail('Fixture did not listen')
                            time.sleep(.05)
                    output = io.StringIO()
                    with redirect_stdout(output): support.ssh_probe(port, 'test-service')
                    self.assertEqual('ready', json.loads(output.getvalue())['status'])
                finally:
                    process.terminate()
                    process.wait(timeout=10)


if __name__ == '__main__':
    unittest.main()
