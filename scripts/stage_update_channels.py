"""Verify signed APKs and stage channel metadata/legacy pointers before publication.

No network or repository mutation occurs here. apksigner validates complete APK
signatures; aapt reads signed identities. The release workflow supplies the prior
public APKs so signer and increasing-version checks are based on actual files.
"""
import argparse
from dataclasses import dataclass
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess

ABIS = ("arm64-v8a", "armeabi-v7a", "x86_64")
REPOSITORY = "muddigger782012/J.A.R.V.I.S.-"
PACKAGES = {"jarvis": "com.assistant.core", "beta": "com.assistant.core.beta"}
# Existing permanent signer, verified in publication run 391 and current APKs.
PERMANENT_SIGNER = "93a2f2031692f947440f4cfd874a166609cd2735eeda28e60904da64e8245790"
# Stay below GitHub's Git-file limit, with a conservative decimal-byte bound.
MAX_LEGACY_BYTES = 100_000_000


def file_digest(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


@dataclass(frozen=True)
class ApkInfo:
    path: Path
    package: str
    version_code: int
    version_name: str
    min_sdk: int
    abis: tuple
    signer: str
    size: int
    sha256: str
    label: str


def run_tool(command):
    result = subprocess.run(command, check=True, capture_output=True, text=True)
    return result.stdout


def inspect_apk(path, aapt, apksigner):
    path = Path(path)
    signatures = run_tool([apksigner, "verify", "--print-certs", str(path)])
    signers = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F:]+)", signatures)
    if len(signers) != 1:
        raise ValueError("Updater APK must have one verified signing certificate")
    signer = signers[0].replace(":", "").lower()
    if not re.fullmatch(r"[0-9a-f]{64}", signer):
        raise ValueError("APK signer digest is invalid")
    badging = run_tool([aapt, "dump", "badging", str(path)])
    identity = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", badging, re.M)
    sdk = re.search(r"^sdkVersion:'(\d+)'", badging, re.M)
    native = re.search(r"^native-code:(.*)$", badging, re.M)
    label = re.search(r"^application-label:'([^']*)'", badging, re.M)
    if identity is None or sdk is None or native is None or label is None:
        raise ValueError("APK identity/SDK/native ABI/label metadata is incomplete")
    digest = file_digest(path)
    return ApkInfo(path, identity[1], int(identity[2]), identity[3], int(sdk[1]),
                   tuple(re.findall(r"'([^']+)'", native[1])), signer,
                   path.stat().st_size, digest, label[1])


def validate_identity(apk, channel, version_code=None, abis=None):
    if apk.package != PACKAGES[channel]:
        raise ValueError("APK belongs to the wrong update channel")
    if not 0 < apk.version_code < 2_100_000_000:
        raise ValueError("APK version code is outside Android's supported range")
    if version_code is not None and apk.version_code != version_code:
        raise ValueError("APK version code does not match this workflow run")
    if apk.signer != PERMANENT_SIGNER:
        raise ValueError("APK does not use the existing permanent release signer")
    if apk.min_sdk < 26 or not apk.abis or set(apk.abis) - set(ABIS):
        raise ValueError("APK SDK/native ABI configuration is unsupported")
    if abis is not None and set(apk.abis) != set(abis):
        raise ValueError("APK native libraries do not match its advertised ABI coverage")
    if apk.size < 100_000 or not re.fullmatch(r"[0-9a-f]{64}", apk.sha256):
        raise ValueError("APK integrity metadata is invalid")
    if channel == "beta" and apk.label != "Beta":
        raise ValueError("Beta APK lost its existing application label")


def validate_upgrade(new, previous, channel, version_code, abis=None):
    validate_identity(new, channel, version_code, abis)
    validate_identity(previous, channel)
    if new.signer != previous.signer or new.version_code <= previous.version_code:
        raise ValueError("APK cannot upgrade the existing signed installation")


def build_manifest(channel, source_commit, artifacts):
    if channel not in PACKAGES or not re.fullmatch(r"[0-9a-f]{40}", source_commit):
        raise ValueError("Channel/source revision is invalid")
    if len(artifacts) != len(ABIS) or {a.abis for a in artifacts} != {(abi,) for abi in ABIS}:
        raise ValueError("Channel must publish exactly one APK per supported ABI")
    first = artifacts[0]
    prefix = "Jarvis" if channel == "jarvis" else "Beta"
    entries = []
    for apk in sorted(artifacts, key=lambda item: ABIS.index(item.abis[0])):
        validate_identity(apk, channel, first.version_code, (apk.abis[0],))
        if apk.version_name != first.version_name or apk.signer != first.signer:
            raise ValueError("Channel APKs are from inconsistent builds")
        abi = apk.abis[0]
        entries.append({"abi": abi,
                        "url": f"https://github.com/{REPOSITORY}/releases/download/{channel}-latest/{prefix}-{abi}.apk",
                        "sizeBytes": apk.size, "sha256": apk.sha256,
                        "signingCertificateSha256": apk.signer, "minSdk": apk.min_sdk})
    return {"schemaVersion": 1, "channel": channel, "applicationId": first.package,
            "versionCode": first.version_code, "versionName": first.version_name,
            "sourceCommit": source_commit, "artifacts": entries}


def validate_previous_metadata(manifest, previous_path):
    if not previous_path.exists():
        return
    previous = json.loads(previous_path.read_text())
    if (previous.get("schemaVersion") != 1 or previous.get("channel") != manifest["channel"]
            or previous.get("applicationId") != manifest["applicationId"]
            or type(previous.get("versionCode")) is not int
            or manifest["versionCode"] <= previous["versionCode"]):
        raise ValueError("Channel metadata would regress or overwrite a published version")
    if any(item.get("signingCertificateSha256") != PERMANENT_SIGNER
           for item in previous.get("artifacts", [])) or not previous.get("artifacts"):
        raise ValueError("Previous channel metadata has an unexpected signing identity")


def validate_legacy_alias(apk):
    if apk.size >= MAX_LEGACY_BYTES or set(apk.abis) != set(ABIS):
        raise ValueError("Legacy raw alias must be universal and below the Git file-size limit")


def find_outputs(directory, inspector):
    files = list(Path(directory).glob("*.apk"))
    records = [inspector(path) for path in files]
    splits = [apk for apk in records if len(apk.abis) == 1]
    universal = [apk for apk in records if set(apk.abis) == set(ABIS)]
    if len(records) != 4 or len(splits) != 3 or len(universal) != 1:
        raise ValueError("Release output must contain three ABI splits and one universal APK")
    return splits, universal[0]


def stage_channel(output, channel, source_commit, splits, alias, previous_metadata):
    manifest = build_manifest(channel, source_commit, splits)
    if (alias.version_code != manifest["versionCode"] or alias.version_name != manifest["versionName"]
            or alias.package != manifest["applicationId"]
            or any(apk.min_sdk != alias.min_sdk for apk in splits)):
        raise ValueError("Universal alias and channel splits are from inconsistent builds")
    validate_previous_metadata(manifest, previous_metadata)
    release_dir = output / "releases" / channel
    release_dir.mkdir(parents=True)
    prefix = "Jarvis" if channel == "jarvis" else "Beta"
    for apk in splits:
        shutil.copyfile(apk.path, release_dir / f"{prefix}-{apk.abis[0]}.apk")
    shutil.copyfile(alias.path, release_dir / f"{prefix}.apk")
    pointer_dir = output / "pointers" / "updates"
    pointer_dir.mkdir(parents=True, exist_ok=True)
    (pointer_dir / f"{channel}.json").write_text(json.dumps(manifest, indent=2) + "\n")
    return manifest


def stage(args):
    output = Path(args.output)
    if output.exists():
        raise ValueError("Staging directory already exists; never reuse stale build files")
    inspector = lambda path: inspect_apk(path, args.aapt, args.apksigner)
    original_splits, original_alias = find_outputs(args.original_dir, inspector)
    previous_original = inspector(args.previous_original)
    for apk in original_splits + [original_alias]:
        validate_upgrade(apk, previous_original, "jarvis", args.version_code)
    validate_legacy_alias(original_alias)
    manifests = [stage_channel(output, "jarvis", args.source_commit, original_splits,
                               original_alias, Path(args.previous_metadata_dir) / "jarvis.json")]
    legacy_dir = output / "pointers" / "artifacts"
    legacy_dir.mkdir(parents=True)
    shutil.copyfile(original_alias.path, legacy_dir / "app-debug.apk")
    if args.beta_dir:
        if not all((args.bridge_dir, args.previous_beta_raw, args.previous_beta_full)):
            raise ValueError("Beta needs full and historical bridge upgrade baselines")
        beta_splits, beta_alias = find_outputs(args.beta_dir, inspector)
        bridge_splits, bridge_alias = find_outputs(args.bridge_dir, inspector)
        previous_beta = inspector(args.previous_beta_full)
        previous_bridge = inspector(args.previous_beta_raw)
        for apk in beta_splits + [beta_alias]:
            validate_upgrade(apk, previous_beta, "beta", args.version_code)
        for apk in bridge_splits + [bridge_alias]:
            validate_upgrade(apk, previous_bridge, "beta", args.version_code - 1)
        validate_legacy_alias(bridge_alias)
        if bridge_alias.version_code >= beta_alias.version_code:
            raise ValueError("Beta bridge must be older than the full Beta it offers")
        manifests.append(stage_channel(output, "beta", args.source_commit, beta_splits,
                                       beta_alias, Path(args.previous_metadata_dir) / "beta.json"))
        shutil.copyfile(bridge_alias.path, legacy_dir / "beta.apk")
    receipt = {"sourceCommit": args.source_commit, "versionCode": args.version_code,
               "channels": [manifest["channel"] for manifest in manifests],
               "files": {str(path.relative_to(output)): file_digest(path)
                         for path in output.rglob("*") if path.is_file()}}
    (output / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print("Staged verified signed channels and compatible legacy aliases: " + ", ".join(receipt["channels"]))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--original-dir", required=True)
    parser.add_argument("--previous-original", required=True)
    parser.add_argument("--beta-dir")
    parser.add_argument("--bridge-dir")
    parser.add_argument("--previous-beta-raw")
    parser.add_argument("--previous-beta-full")
    parser.add_argument("--previous-metadata-dir", required=True)
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--version-code", required=True, type=int)
    parser.add_argument("--output", required=True)
    parser.add_argument("--aapt", required=True)
    parser.add_argument("--apksigner", required=True)
    try:
        stage(parser.parse_args())
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        raise SystemExit("Update staging failed: " + str(error))
