"""Exercise the real shell gate with modeled adb/timeout, never a phone."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


GATE = Path(__file__).resolve().parents[1] / 'voice_profile_acceptance.sh'


class VoiceProfileGateTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.calls = self.root / 'calls.jsonl'
        self.budgets = self.root / 'budgets.jsonl'
        adb = self.root / 'adb'
        adb.write_text('''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
args = sys.argv[1:]
with Path(os.environ['GATE_CALLS']).open('a') as output:
    output.write(json.dumps(args) + '\\n')
if args == ['shell', 'getprop', 'ro.build.version.sdk']:
    print(os.environ['GATE_ACTUAL_API'] + '\\r')
elif args[:3] == ['shell', 'am', 'instrument']:
    print(os.environ['GATE_RESULT'])
    sys.exit(int(os.environ['GATE_EXIT']))
else:
    sys.exit(90)
''')
        timeout = self.root / 'timeout'
        timeout.write_text('''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
with Path(os.environ['GATE_BUDGETS']).open('a') as output:
    output.write(json.dumps(sys.argv[1:]) + '\\n')
os.execvp(sys.argv[2], sys.argv[2:])
''')
        adb.chmod(0o700)
        timeout.chmod(0o700)

    def run_gate(self, requested='34', actual='34', result='OK (1 test)', exit_code=0):
        env = dict(os.environ)
        env.update(PATH=str(self.root) + os.pathsep + env['PATH'], GATE_CALLS=str(self.calls),
                   GATE_BUDGETS=str(self.budgets), GATE_ACTUAL_API=actual,
                   GATE_RESULT=result, GATE_EXIT=str(exit_code))
        return subprocess.run(['bash', str(GATE), requested], cwd=self.root, env=env,
                              capture_output=True, text=True, timeout=10)

    def invocations(self):
        return [json.loads(line) for line in self.calls.read_text().splitlines()] if self.calls.exists() else []

    def test_api34_requires_the_full_profile_class_and_records_its_result(self):
        self.assertEqual(0, self.run_gate().returncode)
        calls = self.invocations()
        self.assertEqual(2, len(calls))
        args = calls[1]
        self.assertEqual(['shell', 'am', 'instrument'], args[:3])
        self.assertIn('com.assistant.core.VoiceProfileUiTest', args)
        self.assertIn('jarvis_disposable_emulator', args)
        self.assertIn('true', args)
        self.assertEqual('OK (1 test)\n', (self.root / 'voice-profile-results.txt').read_text())
        budget = json.loads(self.budgets.read_text())
        self.assertEqual('60s', budget[0])

    def test_android_failure_with_successful_adb_exit_still_fails_the_gate(self):
        self.assertNotEqual(0, self.run_gate(result='FAILURES!!!\nTests run: 1, Failures: 1').returncode)

    def test_a_changed_or_missing_case_count_is_not_accepted(self):
        self.assertNotEqual(0, self.run_gate(result='OK (2 tests)').returncode)

    def test_command_failure_cannot_be_hidden_by_a_success_looking_result(self):
        self.assertNotEqual(0, self.run_gate(exit_code=7).returncode)

    def test_wrong_emulator_cannot_silently_skip_the_required_api34_case(self):
        self.assertNotEqual(0, self.run_gate(actual='26').returncode)
        self.assertEqual(1, len(self.invocations()))
        self.assertFalse(self.budgets.exists())

    def test_api26_does_not_claim_unsupported_biasing_was_tested(self):
        self.assertEqual(0, self.run_gate(requested='26', actual='26').returncode)
        self.assertEqual(1, len(self.invocations()))
        self.assertFalse((self.root / 'voice-profile-results.txt').exists())
        self.assertFalse(self.budgets.exists())

    def test_unknown_api_fails_before_using_adb(self):
        self.assertNotEqual(0, self.run_gate(requested='35', actual='35').returncode)
        self.assertEqual([], self.invocations())

    def test_missing_framework_sdk_fails_closed(self):
        self.assertNotEqual(0, self.run_gate(actual='').returncode)
        self.assertFalse(self.budgets.exists())
