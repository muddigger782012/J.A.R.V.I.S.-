"""GET-only recovery selection contracts. APK identities use an explicitly modeled SDK."""
import copy
import hashlib
import json
import os
from pathlib import Path
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import patch

import candidate_audit as audit
import candidate_audit_test as modeled
from candidate_audit_test import apk_fixture, SHA, OTHER, CERT


class ReadFixture:
    def __init__(self):
        self.calls = []
        self.requests = 5  # Reserve the real controller's artifact provenance/download calls.
        self.values = {}
        self.apks = {}
        self.next_id = 20
        self.hook = lambda path, count: None

    def add(self, tag, version=53, source=SHA, signer=CERT, payload=None):
        data = payload if payload is not None else apk_fixture(f"modeled APK {version} {signer}".encode())
        digest = hashlib.sha256(data).hexdigest()
        facts = dict(sourceCommitSha=source, packageName=audit.PACKAGE, versionCode=version,
                     apkSha256=digest, apkBytes=len(data), signerSha256=signer)
        self.apks[digest] = facts
        self.next_id += 1
        prefix = f"/repos/{audit.REPO}"
        self.values[f"{prefix}/releases/tags/{tag}"] = dict(id=self.next_id, tag_name=tag, target_commitish=source,
            draft=False, prerelease=True, assets=[dict(id=self.next_id, name="Jarvis-Developer.apk", size=len(data), digest="sha256:"+digest)])
        self.values[f"{prefix}/releases/assets/{self.next_id}"] = data
        self.values[f"{prefix}/git/ref/tags/{tag}"] = dict(ref="refs/tags/"+tag, object=dict(type="commit", sha=source))
        return facts

    def get(self, path, missing=False, binary=False, **kwargs):
        self.calls.append(path); self.requests += 1
        if self.requests > 24:
            raise audit.AuditFailure("Modeled GET budget exhausted")
        self.hook(path, self.calls.count(path))
        value = self.values.get(path)
        if value is None and not missing:
            raise audit.AuditFailure("Modeled GET unavailable")
        if binary:
            assert isinstance(value, bytes)
        return copy.deepcopy(value)

    def inspect(self, path, source, signed=True):
        data = Path(path).read_bytes()
        digest = hashlib.sha256(data).hexdigest()
        if digest not in self.apks:
            raise audit.AuditFailure("Modeled SDK rejected the APK")
        result = dict(self.apks[digest], sourceCommitSha=source)
        return result

    def metadata(self, tag):
        return self.values[f"/repos/{audit.REPO}/releases/tags/{tag}"]


class CandidateRecoveryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.api = ReadFixture()
        self.baseline = self.api.add("developer-latest")
        self.tags = audit.recovery_tags(self.baseline)
        self.inspect_patch = patch.object(audit, "inspect_apk", self.api.inspect)
        self.inspect_patch.start(); self.addCleanup(self.inspect_patch.stop)

    def lookup(self):
        return audit.verified_backup(self.api, self.baseline, self.temp.name)

    def test_immutable_snapshot_is_verified_without_reading_or_replacing_an_older_legacy_release(self):
        self.api.add(self.tags[1]); self.api.add("developer-previous", version=40, source=OTHER)
        result = self.lookup()
        self.assertEqual(dict(self.baseline, verified=True), result)
        self.assertFalse(any(path.endswith("/developer-previous") for path in self.api.calls))
        self.assertLessEqual(self.api.requests, 24)

    def test_digest_qualified_snapshot_wins_over_an_older_build_of_the_same_source(self):
        self.api.add(self.tags[0]); self.api.add(self.tags[1], version=52)
        self.assertTrue(self.lookup()["verified"])
        self.assertFalse(any(path.endswith("/"+self.tags[1]) for path in self.api.calls))

    def test_exact_legacy_backup_and_annotated_legacy_tags_remain_supported_within_the_read_budget(self):
        self.api.add("developer-previous")
        prefix = f"/repos/{audit.REPO}/git"
        self.api.values[prefix+"/ref/tags/developer-previous"]["object"] = dict(type="tag", sha=OTHER)
        self.api.values[prefix+"/tags/"+OTHER] = dict(object=dict(type="commit", sha=SHA))
        self.assertTrue(self.lookup()["verified"])
        self.assertLessEqual(self.api.requests, 24)

    def test_missing_copies_return_no_proof_and_never_invent_a_backup(self):
        self.assertIsNone(self.lookup())
        self.assertEqual(3, len(self.api.calls))

    def test_older_source_only_snapshot_can_fall_back_to_an_exact_legacy_copy(self):
        self.api.add(self.tags[1], version=52); self.api.add("developer-previous")
        self.assertEqual(dict(self.baseline, verified=True), self.lookup())
        self.assertLessEqual(self.api.requests, 24)

    def test_wrong_version_source_or_signer_never_becomes_verified(self):
        for field, value in [("versionCode",52),("sourceCommitSha",OTHER),("signerSha256","e"*64),("packageName","com.jarvis.beta")]:
            with self.subTest(field=field):
                api = ReadFixture(); baseline = api.add("developer-latest")
                tag = audit.recovery_tags(baseline)[1]
                facts = api.add(tag)
                api.apks[facts["apkSha256"]][field] = value
                if field == "sourceCommitSha": api.metadata(tag)["target_commitish"] = OTHER
                with patch.object(audit,"inspect_apk",api.inspect):
                    if field == "sourceCommitSha":
                        with self.assertRaises(audit.AuditFailure): audit.verified_backup(api,baseline,self.temp.name)
                    else:
                        self.assertFalse(audit.verified_backup(api,baseline,self.temp.name)["verified"])

    def test_digest_named_corruption_stops_instead_of_falling_back_to_a_good_legacy_copy(self):
        self.api.add(self.tags[0], version=52); self.api.add("developer-previous")
        with self.assertRaises(audit.AuditFailure): self.lookup()
        self.assertFalse(any(path.endswith("/developer-previous") for path in self.api.calls))

    def test_immutable_tags_must_directly_point_to_the_baseline_source(self):
        for change in [dict(type="commit",sha=OTHER),dict(type="tag",sha=SHA),dict(type="commit",sha="invalid"),[]]:
            with self.subTest(change=change):
                self.api.add(self.tags[1])
                self.api.values[f"/repos/{audit.REPO}/git/ref/tags/{self.tags[1]}"]["object"] = change
                with self.assertRaises(audit.AuditFailure): self.lookup()
                self.api.calls.clear(); self.api.requests = 5

    def test_unpublished_or_wrong_identity_recoveries_are_rejected(self):
        for field,value in [("draft",True),("prerelease",False),("tag_name","another-tag"),("id",True),("assets",{})]:
            with self.subTest(field=field):
                self.api.add(self.tags[1]); self.api.metadata(self.tags[1])[field] = value
                with self.assertRaises(audit.AuditFailure): self.lookup()
                self.api.calls.clear(); self.api.requests = 5

    def test_metadata_digest_and_size_mismatches_cannot_override_actual_sdk_bytes(self):
        for field,value in [("digest","sha256:"+"e"*64),("size",1),("digest",None)]:
            with self.subTest(field=field):
                self.api.add(self.tags[1]); self.api.metadata(self.tags[1])["assets"][0][field] = value
                with self.assertRaises(audit.AuditFailure): self.lookup()
                self.api.calls.clear(); self.api.requests = 5

    def test_snapshot_asset_or_ref_changes_during_inspection_fail_closed(self):
        for kind in ["asset","ref"]:
            self.api.add(self.tags[1])
            def drift(path,count):
                if kind == "asset" and path.endswith("/releases/tags/"+self.tags[1]) and count == 2:
                    self.api.metadata(self.tags[1])["assets"][0]["id"] += 100
                if kind == "ref" and path.endswith("/git/ref/tags/"+self.tags[1]) and count == 2:
                    self.api.values[path]["object"]["sha"] = OTHER
            self.api.hook = drift
            with self.assertRaises(audit.AuditFailure): self.lookup()
            self.api.calls.clear(); self.api.requests = 5

    def test_moving_latest_baseline_is_rechecked_after_a_snapshot_passes(self):
        self.api.add(self.tags[1])
        def move(path,count):
            if path.endswith("/releases/tags/developer-latest"): self.api.add("developer-latest",version=54)
        self.api.hook = move
        with self.assertRaises(audit.AuditFailure): self.lookup()

    def test_reference_copy_proof_does_not_assert_that_it_is_the_current_release(self):
        self.api.add(self.tags[1]); self.api.add("developer-latest",version=54)
        result = audit.inspect_recovery_copy(self.api,self.baseline,self.tags[1],self.temp.name)
        self.assertTrue(result["verified"])
        self.assertFalse(any(path.endswith("/developer-latest") for path in self.api.calls))

    def test_unsafe_baseline_or_tag_input_is_rejected_before_any_network_request(self):
        for value in [None,{},dict(self.baseline,sourceCommitSha="../other"),dict(self.baseline,apkSha256="invalid")]:
            with self.assertRaises(audit.AuditFailure): audit.verified_backup(self.api,value,self.temp.name)
        with self.assertRaises(audit.AuditFailure): audit.inspect_recovery_copy(self.api,self.baseline,"arbitrary-release",self.temp.name)
        self.assertEqual([],self.api.calls)

    def test_a_verified_copy_still_cannot_authorize_canonical_controller_self_promotion(self):
        self.api.add(self.tags[1]); copy = self.lookup()
        values = modeled.CandidateAuditTest.ready_evidence(self)
        values[1] = self.baseline; values[2] = copy
        values[5]["branchIsolated"] = False
        result = audit.evaluate(*values)
        self.assertEqual("BLOCKED",result["status"])
        self.assertIn("CANDIDATE_UNVERIFIED",result["recovery"]["missingReasons"])
        self.assertNotIn("BACKUP_UNVERIFIED",result["recovery"]["missingReasons"])

    def run_readonly(self, reference_source="", reference_hash=""):
        args = SimpleNamespace(controller=self.temp.name,directory=self.temp.name,
                               reference_source=reference_source,reference_apk_sha256=reference_hash)
        with patch.dict(os.environ,{"GH_TOKEN":"modeled-read-token","GITHUB_SHA":SHA,"GITHUB_REPOSITORY":audit.REPO}), \
                patch.object(audit,"git",return_value=SHA),patch.object(audit,"GitHubRead",return_value=self.api):
            audit.audit_recovery(args)
        return json.loads((Path(self.temp.name)/"recovery-discovery.json").read_text())

    def test_readonly_audit_can_verify_a_historical_copy_while_current_backup_remains_blocked(self):
        tag = "developer-recovery-"+OTHER
        historical = self.api.add(tag,version=52,source=OTHER)
        report = self.run_readonly(OTHER,historical["apkSha256"])
        self.assertEqual("PASSED",report["status"])
        self.assertEqual("BLOCKED",report["currentBackup"]["status"])
        self.assertTrue(report["historicalReference"]["facts"]["verified"])
        self.assertFalse(report["historicalReference"]["currentBaselineClaim"])
        self.assertEqual("NOT_ASSESSED",report["promotionEligibility"])
        self.assertLessEqual(self.api.requests,24)

    def test_even_a_verified_current_copy_in_readonly_report_grants_no_promotion_or_action_authority(self):
        self.api.add(self.tags[1])
        report = self.run_readonly()
        self.assertEqual("VERIFIED",report["currentBackup"]["status"])
        self.assertEqual("NOT_ASSESSED",report["promotionEligibility"])
        for field in ["mutationPerformed","signingPerformed","installationPerformed"]:
            self.assertIs(report[field],False)

    def test_missing_reference_arguments_fail_before_network_and_wrong_expected_bytes_cannot_pass(self):
        with self.assertRaises(audit.AuditFailure): self.run_readonly(SHA,"")
        self.assertEqual([],self.api.calls)
        self.api.add("developer-recovery-"+OTHER,version=52,source=OTHER)
        with self.assertRaises(audit.AuditFailure): self.run_readonly(OTHER,"f"*64)
        self.assertFalse((Path(self.temp.name)/"recovery-discovery.json").exists())
