"""Publish verified update assets, then Git metadata/legacy pointers on main only.

Invoked only by the signed release workflow. This script uses an isolated Git
worktree: generated outputs and the checked-out build source are never reset.
"""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

from stage_update_channels import REPOSITORY, file_digest


def command(*args, cwd=None):
    return subprocess.run(args, cwd=cwd, capture_output=True, text=True, check=True).stdout.strip()


def source_is_current(source_commit):
    command("git", "fetch", "origin", "main")
    changed = command("git", "diff", "--name-only", source_commit, "origin/main").splitlines()
    return all(path.startswith(("artifacts/", "updates/")) for path in changed)


def validate_stage(stage, source_commit):
    receipt = json.loads((stage / "receipt.json").read_text())
    if receipt.get("sourceCommit") != source_commit or receipt.get("channels") not in (["jarvis"], ["jarvis", "beta"]):
        raise ValueError("Staged source/channels do not match the authorized build")
    recorded = receipt.get("files", {})
    actual = {str(path.relative_to(stage)) for path in stage.rglob("*")
              if path.is_file() and path.name != "receipt.json"}
    if set(recorded) != actual:
        raise ValueError("Staged files are missing or changed after verification")
    for relative, digest in recorded.items():
        path = stage / relative
        if path.resolve().is_relative_to(stage.resolve()) is False or file_digest(path) != digest:
            raise ValueError("Staged file integrity verification failed")
    for channel in receipt["channels"]:
        manifest = json.loads((stage / "pointers" / "updates" / f"{channel}.json").read_text())
        prefix = "Jarvis" if channel == "jarvis" else "Beta"
        if manifest.get("sourceCommit") != source_commit or manifest.get("versionCode") != receipt["versionCode"]:
            raise ValueError("Staged metadata disagrees with the verified build")
        for artifact in manifest["artifacts"]:
            path = stage / "releases" / channel / f"{prefix}-{artifact['abi']}.apk"
            if path.stat().st_size != artifact["sizeBytes"] or file_digest(path) != artifact["sha256"]:
                raise ValueError("Staged metadata does not describe the release bytes")
    return receipt


def ensure_release(tag, source, title, beta=False):
    try:
        command("gh", "api", f"repos/{REPOSITORY}/releases/tags/{tag}")
    except subprocess.CalledProcessError as error:
        if "404" not in error.stderr:
            raise
        args = ["gh", "release", "create", tag, "--repo", REPOSITORY,
                "--target", source, "--title", title, "--notes",
                "Signed APKs; Android prompts and per-channel verification apply."]
        if beta:
            args.append("--prerelease")
        command(*args)


def upload_and_verify(stage, channel, tag, temporary):
    assets = sorted((stage / "releases" / channel).glob("*.apk"))
    command("gh", "release", "upload", tag, *(str(path) for path in assets),
            "--repo", REPOSITORY, "--clobber")
    downloaded = temporary / tag
    downloaded.mkdir()
    command("gh", "release", "download", tag, "--repo", REPOSITORY,
            "--dir", str(downloaded), "--pattern", "*.apk")
    for asset in assets:
        fetched = downloaded / asset.name
        if not fetched.is_file() or file_digest(fetched) != file_digest(asset):
            raise ValueError("Published release asset did not match its verified APK")
    shutil.rmtree(downloaded)


def publish(stage, source):
    if os.getenv("GITHUB_REF") != "refs/heads/main" or os.getenv("GITHUB_REPOSITORY") != REPOSITORY:
        raise ValueError("Public APK updates may only be published from this repository's main branch")
    receipt = validate_stage(stage, source)
    if not source_is_current(source):
        print("Superseded source: no APK assets, channel metadata or legacy pointers published")
        return
    with tempfile.TemporaryDirectory(prefix="jarvis-publish-", dir=os.getenv("RUNNER_TEMP")) as temporary_name:
        temporary = Path(temporary_name)
        # A versioned release retains the exact build for recovery and inspection.
        for channel in receipt["channels"]:
            tag = f"{channel}-v{receipt['versionCode']}"
            ensure_release(tag, source, f"J.A.R.V.I.S. {channel} {receipt['versionCode']}", channel == "beta")
            upload_and_verify(stage, channel, tag, temporary)
        if not source_is_current(source):
            print("Source advanced while uploading: current channel pointers preserved")
            return
        for channel in receipt["channels"]:
            tag = f"{channel}-latest"
            ensure_release(tag, source, f"J.A.R.V.I.S. {channel}", channel == "beta")
            upload_and_verify(stage, channel, tag, temporary)
        if not source_is_current(source):
            raise ValueError("Source advanced during channel publication; metadata was not advanced; start a new main workflow run with a newer version code")
        worktree = temporary / "pointers"
        command("git", "worktree", "add", "--detach", str(worktree), "origin/main")
        try:
            for folder in ("updates", "artifacts"):
                destination = worktree / folder
                destination.mkdir(exist_ok=True)
                for pointer in (stage / "pointers" / folder).iterdir():
                    shutil.copyfile(pointer, destination / pointer.name)
            command("git", "config", "user.name", "github-actions[bot]", cwd=worktree)
            command("git", "config", "user.email", "41898282+github-actions[bot]@users.noreply.github.com", cwd=worktree)
            command("git", "add", "updates", "artifacts", cwd=worktree)
            command("git", "commit", "-m", f"Publish verified Jarvis update channels {receipt['versionCode']} [skip ci]", cwd=worktree)
            # Fast-forward push fails on a racing main change instead of overwriting it.
            command("git", "push", "origin", "HEAD:main", cwd=worktree)
        finally:
            command("git", "worktree", "remove", "--force", str(worktree))
    print("Published verified channel assets, metadata and legacy upgrade aliases")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stage", required=True, type=Path)
    parser.add_argument("--source-commit", required=True)
    args = parser.parse_args()
    try:
        publish(args.stage, args.source_commit)
    except (ValueError, OSError, KeyError, subprocess.CalledProcessError) as error:
        raise SystemExit("Update publication failed: " + str(error))
