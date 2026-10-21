"""Verify and publish Personal Jarvis without preparing artifacts by hand.

Only the main-branch publisher writes the updater. Older reviewed releases may be
published as downloads, but can never replace a newer installed-update candidate.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time
import urllib.request
import zipfile

ABIS = ('arm64-v8a', 'armeabi-v7a', 'x86_64')
APP_INPUTS = ('app/src', 'app/build.gradle', 'app/proguard-rules.pro',
              'app/voice-test-host.pro', 'build.gradle', 'settings.gradle',
              'gradle.properties', 'gradle', 'gradlew', 'gradlew.bat')
CHECK_INPUTS = (*APP_INPUTS, 'cloud-backend', 'scripts',
                '.github/workflows/local-knowledge-checks.yml',
                '.github/workflows/ui-theme-check.yml')
MAX_APK = 100 * 1024 * 1024
MODEL = 'assets/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01/'


def require(ok, message):
    if not ok:
        raise ValueError(message)


def command(*args):
    return subprocess.check_output([str(a) for a in args], text=True).strip()


def digest(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def version_code(version):
    require(isinstance(version, str) and re.fullmatch(r'3\.(?:5|6)\.[1-9][0-9]*', version),
            'Expected a Personal Jarvis version with a positive build code')
    code = int(version.rsplit('.', 1)[1])
    require(code < 2100000000, 'Android version code is out of range')
    return code


def sdk_tool(name):
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    require(sdk, 'Android SDK is required for independent signature verification')
    path = Path(sdk) / 'build-tools/34.0.0' / name
    require(path.is_file(), 'Android build tools 34 are required')
    return path


def identity(path):
    output = command(sdk_tool('aapt'), 'dump', 'badging', path)
    require(output, 'APK package identity is missing')
    package = dict(re.findall(r"([A-Za-z]+)='([^']*)'", output.splitlines()[0]))
    require(package.get('name') == 'com.assistant.core', 'Not a Personal Jarvis APK')
    require(re.fullmatch(r'[1-9][0-9]*', package.get('versionCode', '')), 'Invalid APK version code')
    return package, output


def signers(path):
    output = command(sdk_tool('apksigner'), 'verify', '--print-certs', path)
    result = re.findall(r'Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-f]{64})', output)
    require(result and len(result) == len(set(result)), 'APK signature could not be verified')
    return sorted(result)


def verify_archive(path, abi):
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), 'Duplicate APK archive entries')
        require(archive.testzip() is None, 'Corrupt APK archive')
        require(f'lib/{abi}/libsherpa-onnx-jni.so' in names, 'Offline runtime is missing')
        for name in ('tokens.txt', 'jarvis-keywords.txt'):
            require(archive.getinfo(MODEL + name).file_size > 0, 'Wake-word vocabulary is empty')
        for prefix in ('encoder-', 'decoder-', 'joiner-'):
            require(any(n.startswith(MODEL + prefix) and n.endswith('.onnx') and
                        archive.getinfo(n).file_size > 0 for n in names), 'Wake-word model is missing')
        require(not any(n.startswith('assets/voice-packs/') and n.endswith('.tar.bz2') for n in names),
                'Personal APK contains the separate Beta voice archive')
        dex = b''.join(archive.read(n) for n in names if re.fullmatch(r'classes[0-9]*\.dex', n))
        require(not any(marker in dex for marker in
                        (b'private-fixture-password', b'Screen control fixture', b'ScreenControlProbeService')),
                'Acceptance fixture is present in production')


def verify_apks(directory, baseline, version, run_id, commit, allow_older=False):
    code = version_code(version)
    require(isinstance(run_id, int) and not isinstance(run_id, bool) and run_id > 0, 'Invalid build run')
    require(re.fullmatch(r'[0-9a-f]{40}', commit), 'Invalid source commit')
    base, _ = identity(baseline)
    trusted_signers = signers(baseline)
    require(allow_older or code >= int(base['versionCode']), 'Refusing an updater downgrade')
    expected = {f'app-{abi}-release.apk' for abi in ABIS}
    require({p.name for p in directory.iterdir()} == expected, 'Expected exactly the three Personal APKs')
    rows = []
    for abi in ABIS:
        path = directory / f'app-{abi}-release.apk'
        require(path.is_file() and not path.is_symlink() and 0 < path.stat().st_size < MAX_APK,
                'APK is absent, linked or too large')
        info, badging = identity(path)
        require(info.get('versionName') == version and info['versionCode'] == str(code), 'APK version differs from build')
        native = re.search(r'^native-code: (.+)$', badging, re.MULTILINE)
        require(native and native.group(1).strip() == f"'{abi}'", 'APK contains an unexpected native ABI')
        require(signers(path) == trusted_signers, 'APK signer differs from the distributed production identity')
        verify_archive(path, abi)
        tree = command(sdk_tool('aapt'), 'dump', 'xmltree', path, 'AndroidManifest.xml')
        require(not any(s in tree for s in ('ScreenControlFixtureActivity', 'ScreenControlProbeService', ':screenprobe')),
                'Test-only components are present in the production manifest')
        rows.append(dict(artifact=path.name, abi=abi, versionCode=code, versionName=version,
                         bytes=path.stat().st_size, sha256=digest(path),
                         productionSignerMatchesBaseline=True, wakeModelsAndRuntimePresent=True))
    return dict(version=version, buildRunId=run_id, buildCommit=commit, apks=rows)


def write_manifest(manifest, output):
    output.mkdir(parents=True, exist_ok=True)
    (output / f'personal-{manifest["version"]}.json').write_text(json.dumps(manifest, indent=2) + '\n')
    (output / 'SHA256SUMS.txt').write_text(''.join(row['sha256'] + '  ' + row['artifact'] + '\n'
                                                  for row in manifest['apks']))


def validate_manifest(manifest, version):
    code = version_code(version)
    require(manifest.get('version') == version, 'Manifest version mismatch')
    require(isinstance(manifest.get('buildRunId'), int) and not isinstance(manifest['buildRunId'], bool)
            and manifest['buildRunId'] > 0, 'Invalid manifest build run')
    require(re.fullmatch(r'[0-9a-f]{40}', manifest.get('buildCommit', '')), 'Invalid manifest source')
    rows = manifest.get('apks', [])
    require(len(rows) == 3 and {r.get('abi') for r in rows} == set(ABIS), 'Manifest must contain all three ABIs')
    for row in rows:
        require(row.get('artifact') == f'app-{row["abi"]}-release.apk', 'Unsafe artifact name')
        require(row.get('versionName') == version and row.get('versionCode') == code, 'Manifest APK version mismatch')
        require(isinstance(row.get('bytes'), int) and not isinstance(row['bytes'], bool)
                and 0 < row['bytes'] < MAX_APK, 'Invalid APK size')
        require(re.fullmatch(r'[0-9a-f]{64}', row.get('sha256', '')), 'Invalid APK checksum')
        require(row.get('productionSignerMatchesBaseline') is True and
                row.get('wakeModelsAndRuntimePresent') is True, 'Incomplete production verification')
    return manifest


def prepare_downloads(manifest, apks, destination):
    validate_manifest(manifest, manifest['version'])
    destination.mkdir(parents=True, exist_ok=True)
    require(not list(destination.iterdir()), 'Release directory must be empty')
    version = manifest['version']
    for row in manifest['apks']:
        source = apks / row['artifact']
        require(source.stat().st_size == row['bytes'] and digest(source) == row['sha256'],
                'APK differs from independently verified manifest')
        shutil.copyfile(source, destination / f'Jarvis-{version}-{row["abi"]}.apk')
    arm = destination / f'Jarvis-{version}-arm64-v8a.apk'
    archive_path = destination / f'Jarvis-{version}.zip'
    with zipfile.ZipFile(archive_path, 'w', zipfile.ZIP_DEFLATED) as archive:
        entry = zipfile.ZipInfo(f'Jarvis-{version}.apk', date_time=(1980, 1, 1, 0, 0, 0))
        entry.compress_type = zipfile.ZIP_DEFLATED
        entry.external_attr = 0o100644 << 16
        archive.writestr(entry, arm.read_bytes())
    with zipfile.ZipFile(archive_path) as archive:
        require(archive.testzip() is None and hashlib.sha256(archive.read(f'Jarvis-{version}.apk')).hexdigest() == digest(arm),
                'Release ZIP differs from ARM64 APK')
    files = sorted(p for p in destination.iterdir() if p.suffix in ('.apk', '.zip'))
    (destination / 'SHA256SUMS.txt').write_text(''.join(digest(p) + '  ' + p.name + '\n' for p in files))


def api(repo, path):
    return json.loads(command('gh', 'api', f'repos/{repo}/{path}'))


def same_inputs(left, right, paths):
    return subprocess.run(['git', 'diff', '--quiet', left, right, '--', *paths]).returncode == 0


def gate_checks(repo, source, timeout_seconds):
    deadline = time.monotonic() + timeout_seconds
    expected = {'local-knowledge-checks.yml': ('test', 'gateway', 'voice-playback'),
                'ui-theme-check.yml': ('theme',)}
    evidence = {}
    while True:
        pending = []
        for workflow, jobs in expected.items():
            runs = api(repo, f'actions/workflows/{workflow}/runs?per_page=100')['workflow_runs']
            matching = []
            for run in runs:
                sha = run['head_sha']
                # Only repository-owned runs with code and tests identical to the candidate qualify.
                if run.get('head_repository', {}).get('full_name') != repo:
                    continue
                if subprocess.run(['git', 'cat-file', '-e', sha + '^{commit}'], stderr=subprocess.DEVNULL).returncode:
                    continue
                if same_inputs(source, sha, CHECK_INPUTS):
                    matching.append(run)
            if not matching:
                pending.append(workflow)
                continue
            run = matching[0]
            if run['status'] != 'completed':
                pending.append(workflow)
                continue
            require(run['conclusion'] == 'success', 'Required source-equivalent checks did not pass')
            observed = api(repo, f'actions/runs/{run["id"]}/jobs?per_page=100')['jobs']
            require(all(any(j['name'] == name and j['conclusion'] == 'success' for j in observed) for name in jobs),
                    'Required unit, gateway, real Accessibility or theme job is missing/skipped')
            evidence[workflow] = run['id']
        if not pending:
            return evidence
        require(time.monotonic() < deadline, 'Required acceptance checks are not available; updater was not changed')
        time.sleep(min(20, max(0, deadline - time.monotonic())))


def updater_decision(candidate, current_code, current_hash, reviewed_older=False):
    arm = next(r for r in candidate['apks'] if r['abi'] == 'arm64-v8a')
    code = arm['versionCode']
    if code < current_code:
        require(reviewed_older, 'Refusing to overwrite a newer updater')
        return 'keep-newer'
    if code == current_code:
        require(arm['sha256'] == current_hash, 'Same version code contains different APK bytes')
        return 'unchanged'
    return 'update'


def download_verified(url, destination, size, expected_hash):
    require(url.startswith('https://'), 'Downloads must use HTTPS')
    with urllib.request.urlopen(urllib.request.Request(url, headers={'Cache-Control': 'no-cache'}), timeout=90) as response:
        with destination.open('wb') as output:
            total = 0
            while True:
                data = response.read(1024 * 1024)
                if not data:
                    break
                total += len(data)
                require(total <= size, 'Published download exceeds expected size')
                output.write(data)
    require(destination.stat().st_size == size and digest(destination) == expected_hash,
            'Published bytes differ from verified APK or checksums')


def publish(args):
    repo = os.environ['GITHUB_REPOSITORY']
    require(re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repo), 'Invalid repository')
    require(os.environ.get('GITHUB_REF') == 'refs/heads/main', 'Publication is restricted to main')
    reviewed = None
    if args.version:
        version_code(args.version)
        reviewed = validate_manifest(json.loads(Path(f'artifacts/personal-{args.version}.json').read_text()), args.version)
        require(not args.run or args.run == reviewed['buildRunId'], 'Conflicting build selections')
        args.run = reviewed['buildRunId']
    require(args.run and args.run > 0, 'Select a completed build or reviewed version')
    build = api(repo, f'actions/runs/{args.run}')
    require(build['status'] == 'completed' and build['conclusion'] == 'success', 'Signed build is not successful')
    require(build['path'] == '.github/workflows/build-jarvis.yml' and
            build.get('head_repository', {}).get('full_name') == repo, 'Untrusted build provenance')
    source = build['head_sha']
    require(re.fullmatch(r'[0-9a-f]{40}', source), 'Invalid build source')
    command('git', 'fetch', 'origin', 'main')
    current = command('git', 'rev-parse', 'origin/main')
    require(command('git', 'rev-parse', 'HEAD') == current, 'Main advanced before publication; retry on current main')
    subprocess.run(['git', 'merge-base', '--is-ancestor', source, current], check=True)
    require(same_inputs(source, current, APP_INPUTS), 'Application source changed after this build')
    checks = gate_checks(repo, source, args.check_timeout)
    with tempfile.TemporaryDirectory(prefix='personal-release-') as temp:
        work = Path(temp)
        apks = work / 'apks'
        command('gh', 'run', 'download', args.run, '--repo', repo, '--name', 'Jarvis-release-APK', '--dir', apks)
        arm_info, _ = identity(apks / 'app-arm64-v8a-release.apk')
        version = arm_info['versionName']
        require(version_code(version) == build['run_number'], 'Version code does not match signed build run')
        if reviewed:
            require(version == reviewed['version'], 'Reviewed version does not match APK')
        baseline = Path('artifacts/app-debug.apk')
        base_info, _ = identity(baseline)
        base_hash = digest(baseline)
        manifest = verify_apks(apks, baseline, version, args.run, source, allow_older=reviewed is not None)
        if reviewed:
            require(manifest == reviewed, 'Signed artifacts do not match reviewed manifest')
        decision = updater_decision(manifest, int(base_info['versionCode']), base_hash, reviewed is not None)
        assets = work / 'assets'
        prepare_downloads(manifest, apks, assets)
        tag = f'jarvis-{version}'
        # Never modify a previously published release. Retries only verify it.
        existing = subprocess.run(['gh', 'release', 'view', tag, '--repo', repo, '--json', 'isDraft'],
                                  capture_output=True, text=True)
        published = existing.returncode == 0 and not json.loads(existing.stdout)['isDraft']
        if not published:
            command('git', 'config', 'user.name', 'github-actions[bot]')
            command('git', 'config', 'user.email', '41898282+github-actions[bot]@users.noreply.github.com')
            manifest_path = Path(f'artifacts/personal-{version}.json')
            if manifest_path.exists():
                require(json.loads(manifest_path.read_text()) == manifest, 'Existing provenance differs')
            else:
                manifest_path.write_text(json.dumps(manifest, indent=2) + '\n')
            if decision == 'update':
                shutil.copyfile(apks / 'app-arm64-v8a-release.apk', baseline)
            command('git', 'add', manifest_path, baseline)
            if subprocess.run(['git', 'diff', '--cached', '--quiet']).returncode:
                command('git', 'commit', '-m', f'Publish verified Personal Jarvis {version} [skip ci]')
                # Non-force push fails if another task changed main. No stale reset or overwrite.
                command('git', 'push', 'origin', 'HEAD:main')
            publication = command('git', 'rev-parse', 'HEAD')
            notes = work / 'notes.md'
            notes.write_text(release_notes(version, decision, source, checks))
            if existing.returncode != 0:
                command('gh', 'release', 'create', tag, '--repo', repo, '--draft', '--target', publication,
                        '--title', f'Personal Jarvis {version}', '--notes-file', notes)
            else:
                command('gh', 'release', 'edit', tag, '--repo', repo, '--notes-file', notes)
            command('gh', 'release', 'upload', tag, '--repo', repo,
                    *sorted(assets.iterdir()), '--clobber')
            # Verify every uploaded byte before exposing the draft as a stable release.
            verify_public_release(repo, tag, assets, work / 'draft-check')
            command('gh', 'release', 'edit', tag, '--repo', repo, '--draft=false', '--latest=false')
        verify_public_release(repo, tag, assets, work / 'public-check')
        # Current updater may have advanced, but it must never become older than this publication.
        meta = api(repo, 'contents/artifacts/app-debug.apk?ref=main')
        current_apk = work / 'public-updater.apk'
        # Git blob is an independent bound on the actual raw download, including a newer updater.
        require(0 < meta['size'] < MAX_APK, 'Unexpected updater size')
        with urllib.request.urlopen(f'https://raw.githubusercontent.com/{repo}/main/artifacts/app-debug.apk', timeout=90) as response:
            payload = response.read(MAX_APK + 1)
        require(len(payload) == meta['size'] and
                hashlib.sha1(b'blob ' + str(len(payload)).encode() + b'\0' + payload).hexdigest() == meta['sha'],
                'Public updater differs from current Git object')
        current_apk.write_bytes(payload)
        current_info, _ = identity(current_apk)
        require(signers(current_apk) == signers(baseline), 'Public updater signing identity changed')
        require(int(current_info['versionCode']) >= version_code(version), 'Public updater is older than this release')
        updater_decision(manifest, int(current_info['versionCode']), digest(current_apk), reviewed_older=True)
        receipt = dict(version=version, buildRunId=args.run, buildCommit=source, checks=checks,
                       updaterDecision=decision, updaterVersion=current_info['versionName'],
                       releaseVerified=True, publicUpdaterVerified=True, physicalDevice='pending')
        Path('personal-publication-receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
        with open(os.environ.get('GITHUB_STEP_SUMMARY', os.devnull), 'a') as summary:
            summary.write(f'Personal Jarvis {version}: signed APKs, checksum file, ZIP payload and updater verified.\n\n'
                          f'Updater: {current_info["versionName"]}. Physical-device acceptance remains pending.\n')
    print('PASS: production signing, acceptance gates, release downloads and monotonic updater verified.')


def release_notes(version, decision, source, checks):
    curated = Path('docs/PERSONAL_RELEASE_NOTES.md')
    text = curated.read_text() if curated.exists() else 'Reviewed voice screen controls, private Accessibility bridging and existing Personal Jarvis capabilities.\n'
    if decision == 'keep-newer':
        text += '\nThis historical download does not replace the newer APK already served by the in-app updater.\n'
    return (f'Personal Jarvis {version}\n\n' + text +
            '\nAll three production-signed architectures, ARM64 ZIP and SHA256SUMS are attached. '
            'Unit, gateway, theme and real Accessibility emulator acceptance gates passed for the same source and tests. '
            'Physical-phone voice/HUD, OEM behavior and installed-data upgrade acceptance remain pending.\n')


def verify_public_release(repo, tag, assets, output):
    output.mkdir()
    release = release_metadata(repo, tag)
    expected = {p.name: p for p in assets.iterdir()}
    observed = {row['name']: row for row in release['assets']}
    require(set(observed) == set(expected), 'Published release asset set differs')
    for name, path in expected.items():
        row = observed[name]
        require(row['size'] == path.stat().st_size and row.get('digest') == 'sha256:' + digest(path),
                'Published asset metadata differs from verified file')
        if release['draft']:
            # Draft URLs need authenticated gh; do not expose a release until bytes are rechecked.
            command('gh', 'release', 'download', tag, '--repo', repo, '--pattern', name, '--dir', output)
            require(digest(output / name) == digest(path), 'Uploaded draft bytes differ')
        else:
            download_verified(row['browser_download_url'], output / name, row['size'], digest(path))


def release_metadata(repo, tag):
    # Draft tags need not exist yet. gh resolves the draft and supplies its stable
    # API record URL; bind that record back to this repository and expected tag.
    view = json.loads(command('gh', 'release', 'view', tag, '--repo', repo, '--json', 'apiUrl'))
    prefix = f'https://api.github.com/repos/{repo}/'
    url = view.get('apiUrl', '')
    require(url.startswith(prefix) and re.fullmatch(r'releases/[1-9][0-9]*', url[len(prefix):]),
            'Release record belongs to an unexpected repository or API resource')
    result = api(repo, url[len(prefix):])
    require(result.get('tag_name') == tag and result.get('prerelease') is False,
            'Release record does not match the expected stable Personal tag')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='mode', required=True)
    prepare = commands.add_parser('prepare')
    prepare.add_argument('--apks', type=Path, required=True)
    prepare.add_argument('--baseline', type=Path, required=True)
    prepare.add_argument('--version', required=True)
    prepare.add_argument('--run', type=int, required=True)
    prepare.add_argument('--commit', required=True)
    prepare.add_argument('--output', type=Path, required=True)
    publisher = commands.add_parser('publish')
    publisher.add_argument('--run', type=int)
    publisher.add_argument('--version')
    publisher.add_argument('--check-timeout', type=int, default=1800)
    args = parser.parse_args()
    if args.mode == 'prepare':
        manifest = verify_apks(args.apks, args.baseline, args.version, args.run, args.commit)
        write_manifest(manifest, args.output)
        print('PASS: manifest and checksums generated from all independently verified production APKs.')
    else:
        publish(args)


if __name__ == '__main__':
    main()
