"""Static safety contracts for disposable Phase 6 checklist acceptance."""
import pathlib
import unittest

SCRIPT = pathlib.Path(__file__).with_name('checklist_acceptance.sh')


class ChecklistAcceptanceContractTest(unittest.TestCase):
    def test_device_and_release_boundaries(self):
        text = SCRIPT.read_text(encoding='utf-8')
        for required in (
            'set -euo pipefail',
            'ro.kernel.qemu',
            'emulator-[0-9]+',
            'verify-device',
            'adb install -r -t',
            'am force-stop',
            'savedTaskSurvivesHostForceStop',
            'corruptStorageRequiresConfirmedResetBeforeEditing',
            'ownerDeviceInstalled=False',
            'published=False',
        ):
            with self.subTest(required=required):
                self.assertIn(required, text)

    def test_each_instrumentation_case_has_independent_pass_assertion(self):
        text = SCRIPT.read_text(encoding='utf-8')
        self.assertEqual(text.count('grep -Eq \'^OK \\(1 test\\)\''), 3)
        self.assertIn('sourceCommitSha', text)
        self.assertIn('GITHUB_SHA', text)


    def test_emulator_api_is_verified_before_installation(self):
        text = SCRIPT.read_text(encoding='utf-8')
        guard = 'actual_api="$(adb shell getprop ro.build.version.sdk'
        self.assertIn('CHECKLIST_API_LEVEL:?', text)
        self.assertIn(guard, text)
        self.assertIn('[[ "$actual_api" == "$expected_api" ]]', text)
        self.assertLess(text.index(guard), text.index('adb install -r -t'))

    def test_recovery_and_restart_are_separate_device_runs(self):
        text = SCRIPT.read_text(encoding='utf-8')
        for case in ('save-recreate', 'process-relaunch', 'corrupt-recovery'):
            self.assertIn('> "$results/' + case + '.txt" 2>&1', text)
            self.assertIn('grep -Eq', text)
        self.assertIn('hostForceStopVerified=True', text)

if __name__ == '__main__':
    unittest.main()
