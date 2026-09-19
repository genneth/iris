#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.13"
# dependencies = []
# ///
"""Lease-boundary regression tests; no SDK, Toolbx or emulator is launched."""
import os
import fcntl
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("android-loop")

CONTROLLER = r'''#!/usr/bin/env bash
set -eu
printf '%s\n' "$*" >> "$FIXTURE/controller-log"
command=$1; shift
project=${1:-}; shift || true
args=" $* "
case "$command" in
acquire)
    [[ ${ACQUIRE_FAIL:-0} == 0 ]] || { echo 'device held by hartley' >&2; exit 1; }
    echo token123 > "$FIXTURE/current"
    echo emulator-5554
    [[ ${NO_TOKEN:-0} == 1 ]] || echo 'emulatorctl: lease token: token123 — pass it back' >&2
    ;;
serial|release)
    # Like emulatorctl, absent project/token is permissive. The wrapper must supply both.
    if [[ -n "$project" && "$project" != pupil ]] ||
       [[ "$args" == *' --lease '* && "$args" != *" --lease $(cat "$FIXTURE/current") "* ]]; then
        echo 'emulatorctl: your lease was superseded' >&2; exit 1
    fi
    if [[ "$command" == serial ]]; then echo emulator-5554; else touch "$FIXTURE/released"; fi
    ;;
esac
'''
TOOLBOX = r'''#!/usr/bin/env bash
set -eu
printf '%s\n' "$*" >> "$FIXTURE/adb-log"
if [[ ${DISPLACE:-0} == 1 ]]; then echo replacement > "$FIXTURE/current"; fi
case "$*" in
*get-state) echo device ;;
*sys.boot_completed) echo 1 ;;
*SurfaceFlinger) echo 'GLES: software' ;;
esac
'''

class LeaseTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.script = self.root / 'android/scripts/android-loop'
        self.script.parent.mkdir(parents=True)
        shutil.copyfile(SCRIPT, self.script)
        self.token = self.root / 'target/android-loop/lease-token'
        self.token.parent.mkdir(parents=True)
        (self.root / 'current').write_text('token123\n')
        for name, source in [('emulatorctl', CONTROLLER), ('toolbox', TOOLBOX)]:
            path = self.root / name
            path.write_text(source)
            path.chmod(0o755)
        self.env = dict(os.environ, FIXTURE=str(self.root), EMULATORCTL=str(self.root/'emulatorctl'),
                        PATH=f'{self.root}:{os.environ["PATH"]}')
        self.env.pop('PUPIL_EMULATOR_LEASE_FILE', None)

    def run_loop(self, command, **env):
        return subprocess.run(['bash', str(self.script), command], env=self.env | env,
                              text=True, capture_output=True, timeout=4)

    def log(self, name):
        path = self.root / name
        return path.read_text().splitlines() if path.exists() else []

    def test_start_persists_token_and_stop_presents_it(self):
        result = self.run_loop('start')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(self.token.exists(), 'acquire must persist its token')
        self.assertEqual(self.token.read_text().strip(), 'token123')
        self.assertEqual(self.run_loop('stop').returncode, 0)
        self.assertIn('release pupil --lease token123', self.log('controller-log'))
        self.assertFalse(self.token.exists())

    def test_no_token_refuses_all_device_entrypoints(self):
        for command in ['renderer', 'folded', 'unfolded', 'capture', 'wait', 'install', 'test-ui', 'stop']:
            with self.subTest(command=command):
                result = self.run_loop(command)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn('lease token', result.stderr.lower())
        self.assertEqual(self.log('adb-log'), [])
        self.assertFalse((self.root/'released').exists())

    def test_replaced_token_refuses_device_and_release(self):
        self.token.write_text('oldtoken\n')
        for command in ['renderer', 'wait', 'stop']:
            result = self.run_loop(command)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('superseded', result.stderr)
        self.assertEqual(self.log('adb-log'), [])
        self.assertFalse((self.root/'released').exists())

    def test_valid_token_reaches_device(self):
        self.token.write_text('token123\n')
        result = self.run_loop('renderer')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('serial pupil --lease token123', self.log('controller-log'))
        self.assertEqual(len(self.log('adb-log')), 1)

    def test_displacement_during_wait_stops_before_next_device_call(self):
        self.token.write_text('token123\n')
        result = self.run_loop('wait', DISPLACE='1')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('superseded', result.stderr)
        self.assertEqual(len(self.log('adb-log')), 1)

    def test_acquire_without_token_fails_closed(self):
        result = self.run_loop('start', NO_TOKEN='1')
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(self.token.exists())
        self.assertEqual(self.log('adb-log'), [])

    def test_renewal_reuses_token_when_controller_does_not_reannounce_it(self):
        self.token.write_text('token123\n')
        result = self.run_loop('start', NO_TOKEN='1')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('--lease token123', self.log('controller-log')[0])
        self.assertEqual(self.token.read_text(), 'token123\n')

    def test_independent_workflow_file_does_not_use_default_token(self):
        self.token.write_text('token123\n')
        result = self.run_loop('renderer', PUPIL_EMULATOR_LEASE_FILE=str(self.root/'other-token'))
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.log('adb-log'), [])

    def test_concurrent_command_refuses_to_share_workflow(self):
        self.token.write_text('token123\n')
        with Path(str(self.token) + '.lock').open('w') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            result = self.run_loop('renderer')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Another command', result.stderr)
        self.assertEqual(self.log('adb-log'), [])

    def test_failed_acquire_preserves_previous_token(self):
        self.token.write_text('oldtoken\n')
        result = self.run_loop('start', ACQUIRE_FAIL='1')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.token.read_text(), 'oldtoken\n')

if __name__ == '__main__':
    unittest.main()
