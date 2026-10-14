"""Exercise updater publication against isolated local Git repositories."""

import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "publish_updater.sh"


class UpdaterPublicationTests(unittest.TestCase):
    def git(self, root, *args):
        return subprocess.run(["git", *args], cwd=root, check=True, text=True,
                              capture_output=True).stdout.strip()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        self.origin = root / "origin.git"
        self.git(root, "init", "--bare", str(self.origin))
        self.work = root / "work"
        self.git(root, "clone", str(self.origin), str(self.work))
        self.git(self.work, "switch", "-c", "main")
        self.git(self.work, "config", "user.name", "Fixture")
        self.git(self.work, "config", "user.email", "fixture@example.com")
        for relative in ("docs/intelligence-plan.json", "app/src/main/assets/intelligence-plan.json"):
            path = self.work / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(json.dumps({"revision": 1}))
        self.git(self.work, "add", ".")
        self.git(self.work, "commit", "-m", "Source fixture")
        self.git(self.work, "push", "-u", "origin", "main")
        self.source = self.git(self.work, "rev-parse", "HEAD")
        self.runner = root / "runner"
        self.runner.mkdir()
        apk = self.work / "app/build/outputs/apk/release/app-arm64-v8a-release.apk"
        apk.parent.mkdir(parents=True)
        apk.write_bytes(b"verified-build-fixture")
        self.stamped = json.dumps({"revision": 2, "throughVersion": "3.5.395"})
        for relative in ("docs/intelligence-plan.json", "app/src/main/assets/intelligence-plan.json"):
            (self.work / relative).write_text(self.stamped)

    def publish(self, ref="refs/heads/main"):
        env = dict(os.environ, GITHUB_REF=ref, GITHUB_SHA=self.source, RUNNER_TEMP=str(self.runner))
        result = subprocess.run(["bash", str(SCRIPT)], cwd=self.work, env=env,
                                text=True, capture_output=True)
        self.assertEqual(0, result.returncode, result.stderr)
        return result.stdout

    def test_public_plan_matches_bundled_plan_after_reset_and_push(self):
        self.publish()
        for relative in ("docs/intelligence-plan.json", "app/src/main/assets/intelligence-plan.json"):
            self.assertEqual(self.stamped, self.git(self.origin, "show", "main:" + relative))
        self.assertEqual(b"verified-build-fixture", (self.work / "artifacts/app-debug.apk").read_bytes())
        self.assertNotEqual(self.source, self.git(self.origin, "rev-parse", "main"))

    def test_superseded_build_cannot_replace_newer_source_or_plan(self):
        other = Path(self.temp.name) / "other"
        self.git(Path(self.temp.name), "clone", "--branch", "main", str(self.origin), str(other))
        self.git(other, "config", "user.name", "Fixture")
        self.git(other, "config", "user.email", "fixture@example.com")
        (other / "docs/intelligence-plan.json").write_text('{"revision": 9}')
        self.git(other, "commit", "-am", "Newer source")
        self.git(other, "push", "origin", "main")
        newer = self.git(self.origin, "rev-parse", "main")
        self.assertIn("Main changed", self.publish())
        self.assertEqual(newer, self.git(self.origin, "rev-parse", "main"))
        self.assertEqual('{"revision": 9}', self.git(self.origin, "show", "main:docs/intelligence-plan.json"))
        self.assertFalse((self.work / "artifacts/app-debug.apk").exists())

    def test_feature_branch_candidate_does_not_publish(self):
        self.assertIn("outside main", self.publish("refs/heads/feature/context"))
        self.assertEqual(self.source, self.git(self.origin, "rev-parse", "main"))
        self.assertFalse((self.work / "artifacts/app-debug.apk").exists())


if __name__ == "__main__":
    unittest.main()
