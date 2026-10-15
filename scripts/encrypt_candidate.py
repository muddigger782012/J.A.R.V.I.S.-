"""Encrypt verified universal acceptance APKs; never upload plaintext candidates."""
import argparse
import io
import json
from pathlib import Path
import subprocess
import tarfile
import tempfile

from publish_update_channels import validate_stage
from stage_update_channels import PERMANENT_SIGNER, file_digest


def encrypt(stage, source_commit, recipient, output):
    stage, recipient, output = Path(stage).resolve(), Path(recipient), Path(output).resolve()
    if output.is_relative_to(stage) or output.exists():
        raise ValueError('Candidate output must be a new file outside staging')
    if any(path.is_symlink() for path in stage.rglob('*')):
        raise ValueError('Candidate staging must not contain symbolic links')
    receipt = validate_stage(stage, source_commit)
    if receipt['channels'] != ['jarvis', 'beta']:
        raise ValueError('Acceptance candidates require both existing API configurations')
    files = {
        'Jarvis.apk': stage / 'releases/jarvis/Jarvis.apk',
        'Beta.apk': stage / 'releases/beta/Beta.apk',
        'Beta-bridge.apk': stage / 'pointers/artifacts/beta.apk',
        'updates/jarvis.json': stage / 'pointers/updates/jarvis.json',
        'updates/beta.json': stage / 'pointers/updates/beta.json',
        'source-stage-receipt.json': stage / 'receipt.json',
    }
    manifest = {
        'schemaVersion': 1, 'purpose': 'manual-signed-upgrade-acceptance',
        'sourceCommit': source_commit, 'versionCode': receipt['versionCode'],
        'bridgeVersionCode': receipt['versionCode'] - 1,
        'signingCertificateSha256': PERMANENT_SIGNER,
        'metadataUsesPublicUrls': True,
        'files': {name: {'sizeBytes': path.stat().st_size, 'sha256': file_digest(path)}
                  for name, path in files.items()},
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    partial = output.with_name(output.name + '.part')
    if partial.exists():
        raise ValueError('Refusing to overwrite a previous partial candidate')
    try:
        # TemporaryDirectory is private; the archive disappears on every exit path.
        with tempfile.TemporaryDirectory(prefix='jarvis-candidate-') as directory:
            archive_path = Path(directory) / 'candidate.tar'
            with tarfile.open(archive_path, 'w') as archive:
                for name, path in files.items():
                    archive.add(path, arcname=name, recursive=False)
                data = (json.dumps(manifest, indent=2) + '\n').encode()
                info = tarfile.TarInfo('candidate.json')
                info.size, info.mode = len(data), 0o600
                archive.addfile(info, io.BytesIO(data))
            subprocess.run(['openssl', 'cms', '-encrypt', '-binary', '-aes-256-gcm',
                            '-in', str(archive_path), '-outform', 'DER', '-out', str(partial),
                            str(recipient)], check=True, capture_output=True)
            partial.chmod(0o600)
            partial.replace(output)
    finally:
        partial.unlink(missing_ok=True)
    print('Encrypted verified acceptance bundle; no plaintext APK or credential values uploaded')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--stage', required=True)
    parser.add_argument('--source-commit', required=True)
    parser.add_argument('--recipient', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    try:
        encrypt(args.stage, args.source_commit, args.recipient, args.output)
    except (ValueError, OSError, KeyError, subprocess.CalledProcessError):
        raise SystemExit('Candidate encryption failed; no plaintext artifacts published')
