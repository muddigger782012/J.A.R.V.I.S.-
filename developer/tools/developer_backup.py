#!/usr/bin/env python3
"""Fixed, approval-gated preservation of the current Developer APK.

Audit performs GETs only. Preserve can create one missing developer-previous
release and upload one fixed asset; it cannot edit/delete releases or refs,
change developer-latest, sign, build, publish a candidate or install anything.
"""
import argparse
import hashlib
import os
from pathlib import Path
import sys
import urllib.error
import urllib.request

import candidate_audit as audit

REPO = audit.REPO
PREFIX = f"/repos/{REPO}"
TAGS = {"developer-latest", "developer-previous"}


class BackupFailure(Exception):
    """Only fixed error codes are exposed; never an API body or exception."""


class _StatusOpener:
    def __init__(self, opener):
        self.opener = opener
        self.status = None

    def open(self, *args, **kwargs):
        try:
            return self.opener.open(*args, **kwargs)
        except urllib.error.HTTPError as error:
            self.status = error.code
            raise


class FixedRead(audit.GitHubRead):
    """Reuse bounded GET/download validation while preserving access failures."""
    def __init__(self, token, controller):
        super().__init__(token)
        self.controller = controller
        self.opener = _StatusOpener(self.opener)

    def get(self, path, **kwargs):
        self.opener.status = None
        try:
            value = super().get(path, **kwargs)
        except audit.AuditFailure:
            if self.opener.status in (401, 403):
                raise BackupFailure(f"GITHUB_READ_HTTP_{self.opener.status}") from None
            raise BackupFailure("GITHUB_READ_FAILED") from None
        for tag in TAGS:
            if path == PREFIX + "/releases/tags/" + tag and value is not None:
                check(isinstance(value, dict) and value.get("tag_name") == tag and value.get("draft") is False,
                      "RELEASE_IDENTITY_MISMATCH")
        if path.startswith(PREFIX + "/commits/"):
            source = path.removeprefix(PREFIX + "/commits/")
            check(audit.SHA.fullmatch(source) is not None and isinstance(value, dict) and value.get("sha") == source,
                  "BASELINE_COMMIT_MISMATCH")
            try:
                audit.git(["merge-base", "--is-ancestor", source, os.environ["GITHUB_SHA"]], self.controller)
            except (audit.AuditFailure, KeyError):
                raise BackupFailure("BASELINE_NOT_CONTROLLER_ANCESTOR") from None
        return value


class FixedWriter:
    """Two fixed POST operations. No generic write URL, verb, update or delete."""
    def __init__(self, token):
        self.token = token
        self.opener = urllib.request.build_opener(audit.NoRedirect)
        self.created = None
        self.create_attempted = False
        self.upload_attempted = False

    def _post(self, url, payload, content_type):
        headers = {"Authorization": "Bearer " + self.token,
                   "Accept": "application/vnd.github+json", "Content-Type": content_type,
                   "X-GitHub-Api-Version": "2022-11-28"}
        try:
            with self.opener.open(urllib.request.Request(url, data=payload, headers=headers, method="POST"), timeout=45) as response:
                check(response.status == 201, "GITHUB_WRITE_RESPONSE_INVALID")
                data = response.read(128_001)
        except urllib.error.HTTPError as error:
            code = f"GITHUB_WRITE_HTTP_{error.code}" if error.code in (401, 403, 404, 409, 422) else "GITHUB_WRITE_FAILED"
            raise BackupFailure(code) from None
        except (urllib.error.URLError, ValueError, OSError):
            raise BackupFailure("GITHUB_WRITE_FAILED") from None
        try:
            result = audit.decode_json(data)
            check(isinstance(result, dict), "GITHUB_WRITE_RESPONSE_INVALID")
            return result
        except audit.AuditFailure:
            raise BackupFailure("GITHUB_WRITE_RESPONSE_INVALID") from None

    def create_backup(self, source):
        check(audit.SHA.fullmatch(source or "") is not None and not self.create_attempted,
              "CREATE_INPUT_OR_STATE_INVALID")
        self.create_attempted = True
        result = self._post("https://api.github.com" + PREFIX + "/releases", audit.encoded(dict(
            tag_name="developer-previous", target_commitish=source, name="Jarvis Developer preserved recovery baseline",
            body="Exact approved developer-latest APK preservation. No candidate promotion or installation.",
            draft=False, prerelease=True, make_latest="false")), "application/json")
        check(type(result.get("id")) is int and result["id"] > 0 and
              result.get("tag_name") == "developer-previous" and result.get("target_commitish") == source and
              result.get("draft") is False and result.get("prerelease") is True,
              "CREATED_RELEASE_IDENTITY_MISMATCH")
        self.created = result["id"]
        return self.created

    def upload_apk(self, release_id, data):
        check(type(release_id) is int and release_id == self.created and not self.upload_attempted and
              isinstance(data, bytes) and 0 < len(data) <= audit.MAX_APK, "UPLOAD_INPUT_OR_STATE_INVALID")
        self.upload_attempted = True
        # Never use a server-provided upload_url or follow an authenticated redirect.
        result = self._post(f"https://uploads.github.com{PREFIX}/releases/{release_id}/assets?name=Jarvis-Developer.apk",
                            data, "application/vnd.android.package-archive")
        check(result.get("name") == "Jarvis-Developer.apk" and type(result.get("id")) is int and result["id"] > 0 and
              type(result.get("size")) is int and result["size"] == len(data), "UPLOADED_ASSET_IDENTITY_MISMATCH")


def check(condition, code):
    if not condition:
        raise BackupFailure(code)


def tag_source(api, tag):
    check(tag in TAGS, "FIXED_TAG_REQUIRED")
    value = api.get(PREFIX + "/git/ref/tags/" + tag, missing=True)
    if value is None:
        return None
    check(isinstance(value, dict) and value.get("ref") == "refs/tags/" + tag, "TAG_IDENTITY_MISMATCH")
    target = value.get("object", {})
    for _ in range(4):
        check(isinstance(target, dict) and audit.SHA.fullmatch(target.get("sha", "")) is not None,
              "TAG_TARGET_INVALID")
        if target.get("type") == "commit":
            return target["sha"]
        check(target.get("type") == "tag", "TAG_TARGET_INVALID")
        annotated = api.get(PREFIX + "/git/tags/" + target["sha"])
        check(isinstance(annotated, dict), "TAG_TARGET_INVALID")
        target = annotated.get("object", {})
    raise BackupFailure("TAG_DEPTH_EXCEEDED")


def latest(api, directory):
    try:
        baseline = audit.release_apk(api, "developer-latest", directory)
    except (AttributeError, TypeError, KeyError):
        raise BackupFailure("BASELINE_PROTOCOL_INVALID") from None
    check(baseline is not None, "BASELINE_MISSING")
    # Historical developer-latest is a moving tag. The published release records
    # its exact source in target_commitish; never follow or move the latest tag.
    baseline["latestTagSource"] = tag_source(api, "developer-latest")
    baseline["latestTagMatchesRecordedSource"] = baseline["latestTagSource"] == baseline["sourceCommitSha"]
    commit = api.get(PREFIX + "/commits/" + baseline["sourceCommitSha"])
    check(isinstance(commit, dict) and commit.get("sha") == baseline["sourceCommitSha"], "BASELINE_COMMIT_MISMATCH")
    return baseline


def previous(api, directory, baseline):
    try:
        backup = audit.release_apk(api, "developer-previous", directory)
    except (AttributeError, TypeError, KeyError):
        raise BackupFailure("PREVIOUS_PROTOCOL_INVALID") from None
    source = tag_source(api, "developer-previous")
    if backup is None:
        check(source is None, "PREVIOUS_TAG_EXISTS_WITHOUT_RELEASE")
        return None
    check(source == baseline["sourceCommitSha"] and audit.same_backup(baseline, backup),
          "EXISTING_BACKUP_MISMATCH_NO_OVERWRITE")
    return backup


def plan(api, directory):
    """No writer parameter, creation or mutation operation exists in this path."""
    baseline = latest(api, directory)
    backup = previous(api, directory, baseline)
    return dict(schemaVersion=1, status="VERIFIED" if backup else "APPROVAL_REQUIRED",
                baseline=baseline, previousStatus="VERIFIED" if backup else "MISSING",
                backup=backup, mutationPerformed=False,
                approvalRequires=["approve_backup=true", "approved_source_sha", "approved_apk_sha256"])


def preserve(api, writer, directory, approved_source, approved_hash, approve=False):
    check(os.environ.get("GITHUB_EVENT_NAME") == "workflow_dispatch" and
          os.environ.get("GITHUB_REF") == "refs/heads/" + audit.BRANCH and
          os.environ.get("GITHUB_REPOSITORY") == REPO, "MANUAL_TRUSTED_BRANCH_REQUIRED")
    check(approve is True and audit.SHA.fullmatch(approved_source or "") is not None and
          audit.HASH.fullmatch(approved_hash or "") is not None, "EXACT_EXPLICIT_APPROVAL_REQUIRED")
    first = plan(api, directory)
    baseline = first["baseline"]
    check(baseline["sourceCommitSha"] == approved_source and baseline["apkSha256"] == approved_hash,
          "APPROVED_SOURCE_OR_APK_CHANGED")
    if first["backup"] is not None:
        return first  # An existing exact backup is verified without any write.
    # Fresh bytes, source, certificate and both refs immediately before mutation.
    fresh = latest(api, directory)
    check(audit.same_backup(baseline, fresh), "LATEST_CHANGED_BEFORE_CREATE")
    backup = previous(api, directory, baseline)
    if backup is not None:
        return dict(first, status="VERIFIED", previousStatus="VERIFIED", backup=backup)
    data = audit.apk_bytes(Path(directory) / "developer-latest.apk")
    check(hashlib.sha256(data).hexdigest() == approved_hash and len(data) == baseline["apkBytes"],
          "LOCAL_BASELINE_CHANGED")
    # Once a POST is attempted, stop on uncertainty. Never retry, delete, edit or
    # clean up a partially created release. A new audit must inspect that state.
    try:
        release_id = writer.create_backup(approved_source)
        check(tag_source(api, "developer-previous") == approved_source, "CREATED_TAG_SOURCE_MISMATCH")
        writer.upload_apk(release_id, data)
        backup = previous(api, directory, baseline)
        check(backup is not None, "CREATED_BACKUP_MISSING")
        check(audit.same_backup(baseline, latest(api, directory)), "LATEST_CHANGED_AFTER_CREATE")
    except BackupFailure as failure:
        raise BackupFailure("WRITE_UNCERTAIN_" + str(failure)) from None
    except (audit.AuditFailure, AttributeError, TypeError, KeyError, ValueError, UnicodeError, OSError):
        raise BackupFailure("WRITE_UNCERTAIN_READBACK_FAILED") from None
    return dict(first, status="VERIFIED", previousStatus="VERIFIED", backup=backup, mutationPerformed=True)


def context(controller):
    check(os.environ.get("GITHUB_REPOSITORY") == REPO and
          os.environ.get("GITHUB_REF") == "refs/heads/" + audit.BRANCH and
          os.environ.get("GITHUB_EVENT_NAME") in {"push", "workflow_dispatch"}, "TRUSTED_CONTROLLER_REQUIRED")
    sha = os.environ.get("GITHUB_SHA", "")
    check(audit.SHA.fullmatch(sha) is not None and audit.git(["rev-parse", "HEAD"], controller) == sha,
          "CONTROLLER_SOURCE_MISMATCH")


def emit(directory, value):
    value.update(controllerCommitSha=os.environ.get("GITHUB_SHA"),
                 runId=os.environ.get("GITHUB_RUN_ID"), runAttempt=os.environ.get("GITHUB_RUN_ATTEMPT"))
    data = audit.encoded(value)
    check(len(data) <= 8_000, "PLAN_EXCEEDS_BUDGET")
    (Path(directory) / "backup-plan.json").write_bytes(data)
    print("JARVIS_BACKUP_PLAN_SHA256=" + hashlib.sha256(data).hexdigest())
    print("JARVIS_BACKUP_PLAN=" + data.decode("ascii"))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=("audit", "preserve"))
    parser.add_argument("--controller", required=True)
    parser.add_argument("--directory", required=True)
    args = parser.parse_args()
    directory = Path(args.directory)
    directory.mkdir(parents=True, exist_ok=True)
    writer = None
    try:
        context(args.controller)
        token = os.environ["GH_TOKEN"]
        api = FixedRead(token, args.controller)
        if args.action == "audit":
            result = plan(api, directory)
        else:
            writer = FixedWriter(token)
            result = preserve(api, writer, directory,
                              os.environ.get("APPROVED_SOURCE_SHA", ""), os.environ.get("APPROVED_APK_SHA256", ""),
                              os.environ.get("APPROVE_BACKUP") == "true")
        emit(directory, result)
        return 0
    except BackupFailure as failure:
        result = dict(schemaVersion=1, status="BLOCKED", reason=str(failure), mutationPerformed="UNCERTAIN" if str(failure).startswith("WRITE_UNCERTAIN_") else False)
    except (audit.AuditFailure, KeyError, ValueError, UnicodeError, OSError, AttributeError, TypeError):
        uncertain = writer is not None and writer.create_attempted
        result = dict(schemaVersion=1, status="BLOCKED",
                      reason="WRITE_UNCERTAIN_FIXED_BACKUP_VALIDATION_FAILED" if uncertain else "FIXED_BACKUP_VALIDATION_FAILED",
                      mutationPerformed="UNCERTAIN" if uncertain else False)
    emit(directory, result)
    return 1


if __name__ == "__main__":
    sys.exit(main())
