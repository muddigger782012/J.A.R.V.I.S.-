"""Bind already-built APKs to the actual CI checkout; never signs or installs."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import re
import subprocess


def collect(folder, target, signing, source, run, attempt, sdk, command=subprocess.check_output):
    package = {'DEVELOPER': 'com.jarvis.developer', 'PERSONAL': 'com.assistant.core'}[target]
    assert signing in ('debug', 'production') and re.fullmatch('[a-f0-9]{40}', source)
    assert 0 < run < 2**63 and 0 < attempt <= 1000000
    apks = sorted(folder.glob('*.apk'))
    assert 1 <= len(apks) <= 3
    records = []
    for apk in apks:
        assert not apk.is_symlink() and apk.is_file() and 0 < apk.stat().st_size <= 128*1024*1024
        assert re.fullmatch('[A-Za-z0-9._-]{1,120}\\.apk', apk.name)
        badging = command([str(sdk/'aapt'), 'dump', 'badging', str(apk)], text=True)
        identity = dict(re.findall(r"([A-Za-z]+)='([^']*)'", badging.splitlines()[0]))
        assert identity['name'] == package
        assert re.fullmatch('[1-9][0-9]*', identity['versionCode'])
        assert 0 < int(identity['versionCode']) < 2100000000
        assert 0 < len(identity['versionName']) <= 120 and not any(ord(c) < 32 for c in identity['versionName'])
        result = command([str(sdk/'apksigner'), 'verify', '--print-certs', str(apk)], text=True)
        signers = re.findall(r'^Signer #[0-9]+ certificate SHA-256 digest: ([a-f0-9]{64})$', result, re.M)
        assert len(signers) == 1
        abis = re.search(r"^native-code: (.+)$", badging, re.M)
        native = re.findall(r"'([^']+)'", abis[1]) if abis else []
        with apk.open('rb') as stream:
            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        records.append(dict(file=apk.name, bytes=apk.stat().st_size,
                            sha256=digest,
                            packageName=package, versionCode=int(identity['versionCode']),
                            versionName=identity['versionName'], signerSha256=signers[0], abis=native))
    return dict(schemaVersion=1, sourceCommit=source, runId=run, runAttempt=attempt,
                target=target, signing=signing, apks=records)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--directory', type=Path, required=True)
    parser.add_argument('--target', choices=['PERSONAL', 'DEVELOPER'], required=True)
    parser.add_argument('--signing', choices=['debug', 'production'], required=True)
    args = parser.parse_args()
    try:
        actual = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
        # Recovery builds deliberately use a different checkout; record its actual SHA.
        expected = os.environ.get('JARVIS_ARTIFACT_SOURCE', os.environ['GITHUB_SHA'])
        assert actual == expected
        result = collect(args.directory, args.target, args.signing, actual,
                         int(os.environ['GITHUB_RUN_ID']), int(os.environ['GITHUB_RUN_ATTEMPT']),
                         Path(os.environ['ANDROID_HOME'])/'build-tools/34.0.0')
        (args.directory/'development-artifact.json').write_text(json.dumps(result, separators=(',', ':'))+'\n')
    except Exception:
        raise SystemExit('APK provenance could not be verified; no artifact receipt was produced.') from None


if __name__ == '__main__':
    main()
