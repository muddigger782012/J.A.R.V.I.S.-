"""Developer release provenance and finalization. Never builds, signs or installs.

Production writes require the existing explicit canonical-branch dispatch. APK
inspection reuses the job artifact contract; no credentials or source excerpts
are copied into public evidence. Test/device success is deliberately unattested.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import urllib.request

from job_apk_evidence import collect

REPO = 'muddigger782012/J.A.R.V.I.S.-'
BRANCH = 'refs/heads/feature/jarvis-developer'
TAG = 'developer-latest'
APK = 'Jarvis-Developer.apk'
SIGNER = '93a2f2031692f947440f4cfd874a166609cd2735eeda28e60904da64e8245790'
SHA = re.compile(r'[a-f0-9]{40}\Z')
HASH = re.compile(r'[a-f0-9]{64}\Z')
MAX_APK = 64 * 1024 * 1024
MANIFEST_KEYS = {'schemaVersion', 'scope', 'target', 'sourceCommit', 'buildRunId', 'buildRunAttempt',
                 'packageName', 'versionCode', 'versionName', 'apkBytes', 'apkSha256', 'signerSha256',
                 'artifactVerification', 'tests', 'deviceInstallation'}


class ReleaseFailure(Exception):
    """Only fixed diagnostics; never a reflected GitHub/SDK response."""


def require(ok, message):
    if not ok:
        raise ReleaseFailure(message)


def command(args):
    try:
        result = subprocess.run([str(value) for value in args], text=True, capture_output=True, timeout=120)
    except (OSError, subprocess.TimeoutExpired):
        raise ReleaseFailure('Release tool execution did not complete') from None
    require(result.returncode == 0, 'Release tool execution failed')
    require(len(result.stdout.encode()) <= 2_000_000, 'Release tool output exceeds its budget')
    return result.stdout.strip()


def sdk_output(args, text=True):
    require(text is True, 'Text SDK output required')
    result = command(args)
    require(len(result.encode()) <= 128_000, 'SDK metadata exceeds its budget')
    return result


def digest(path):
    require(path.is_file() and not path.is_symlink(), 'Expected a regular evidence file')
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def unique_object(pairs):
    value = {}
    for key, item in pairs:
        require(key not in value, 'Duplicate JSON field')
        value[key] = item
    return value


def read_json(path):
    require(path.is_file() and not path.is_symlink() and path.stat().st_size <= 16_000,
            'Invalid bounded evidence file')
    return json.loads(path.read_text(encoding='utf-8'), object_pairs_hook=unique_object)


def write_json(path, data):
    require(not path.is_symlink(), 'Evidence destination is a symlink')
    encoded = json.dumps(data, indent=2, sort_keys=True) + '\n'
    require(len(encoded.encode()) <= 16_000, 'Evidence exceeds its budget')
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(mode='w', encoding='utf-8', dir=path.parent, delete=False) as stream:
        stream.write(encoded)
        temporary = stream.name
    os.replace(temporary, path)


def inspect(folder, source, run, attempt, sdk, version=None, sdk_command=sdk_output):
    require(__debug__, 'APK inspection requires Python assertions enabled')
    require(isinstance(source, str) and SHA.fullmatch(source), 'Expected immutable source SHA')
    require(type(run) is int and run > 0 and type(attempt) is int and attempt > 0, 'Invalid workflow identity')
    require(folder.is_dir() and not folder.is_symlink(), 'Expected a regular APK directory')
    require(sorted(path.name for path in folder.glob('*.apk')) == [APK], 'Expected exactly the fixed Developer APK')
    require((folder / APK).is_file() and not (folder / APK).is_symlink(), 'Expected a regular Developer APK')
    require(0 < (folder / APK).stat().st_size <= MAX_APK, 'APK exceeds its size budget')
    try:
        receipt = collect(folder, 'DEVELOPER', 'production', source, run, attempt, sdk, sdk_command)
    except Exception:
        raise ReleaseFailure('Independent APK inspection failed') from None
    record = receipt['apks'][0]
    require(record['signerSha256'] == SIGNER, 'Production signing identity changed')
    require(record['versionName'] == '0.1.' + str(record['versionCode']), 'Unexpected Developer version name')
    require(version is None or record['versionCode'] == version, 'APK version differs from workflow run number')
    require(0 < record['versionCode'] < 2100000000, 'Invalid Android version code')
    return receipt


def make_manifest(folder, source, run, attempt, version, sdk, sdk_command=sdk_output):
    require(type(version) is int and version > 0, 'Invalid workflow version')
    require(folder.is_dir() and not folder.is_symlink() and
            {path.name for path in folder.iterdir()} == {APK, 'development-artifact.json'},
            'Unexpected files in the existing candidate artifact contract')
    receipt = inspect(folder, source, run, attempt, sdk, version, sdk_command)
    require(read_json(folder / 'development-artifact.json') == receipt, 'Build receipt does not match APK/source/run')
    apk = receipt['apks'][0]
    manifest = dict(schemaVersion=1, scope='APK_PROVENANCE_ONLY', target='DEVELOPER',
                    sourceCommit=source, buildRunId=run, buildRunAttempt=attempt,
                    packageName=apk['packageName'], versionCode=version, versionName=apk['versionName'],
                    apkBytes=apk['bytes'], apkSha256=apk['sha256'], signerSha256=apk['signerSha256'],
                    artifactVerification='PASSED', tests='PENDING', deviceInstallation='PENDING')
    return validate_manifest(manifest)


def validate_manifest(value):
    require(isinstance(value, dict) and set(value) == MANIFEST_KEYS, 'Unexpected manifest fields')
    require(type(value['schemaVersion']) is int and value['schemaVersion'] == 1 and
            value['scope'] == 'APK_PROVENANCE_ONLY' and value['target'] == 'DEVELOPER', 'Invalid manifest scope')
    require(value['packageName'] == 'com.jarvis.developer' and value['signerSha256'] == SIGNER,
            'Manifest belongs to a different application or signer')
    require(isinstance(value['sourceCommit'], str) and SHA.fullmatch(value['sourceCommit']) and
            isinstance(value['apkSha256'], str) and HASH.fullmatch(value['apkSha256']), 'Invalid manifest hashes')
    for name, limit in [('buildRunId', 2**63), ('buildRunAttempt', 1_000_001),
                        ('versionCode', 2100000000), ('apkBytes', MAX_APK + 1)]:
        require(type(value[name]) is int and 0 < value[name] < limit, 'Invalid manifest numeric identity')
    require(value['versionName'] == '0.1.' + str(value['versionCode']), 'Invalid manifest version')
    require(value['artifactVerification'] == 'PASSED' and value['tests'] == 'PENDING' and
            value['deviceInstallation'] == 'PENDING', 'APK metadata cannot attest tests or installation')
    return value


def filenames(manifest):
    validate_manifest(manifest)
    stem = 'Developer-' + manifest['versionName']
    return stem + '-verification.json', stem + '-SHA256SUMS.txt'


def write_manifest(output, manifest):
    names = filenames(manifest)
    require(not output.is_symlink(), 'Evidence directory is a symlink')
    output.mkdir(parents=True, exist_ok=True)
    write_json(output / names[0], manifest)
    target = output / names[1]
    require(not target.is_symlink(), 'Checksum destination is a symlink')
    target.write_text(manifest['apkSha256'] + '  ' + APK + '\n', encoding='utf-8')
    return [output / name for name in names]


class GitHub:
    """Fixed repository transport. Never logs remote bodies or credential-bearing errors."""
    def api(self, path, method='GET', body=None, missing=False):
        require(not path.startswith('/') and '..' not in path and '\n' not in path, 'Invalid GitHub operation')
        args = ['gh', 'api', '--method', method, 'repos/' + REPO + '/' + path]
        if body is not None:
            args += ['--input', '-']
        try:
            result = subprocess.run(args, input=json.dumps(body) if body is not None else None,
                                    text=True, capture_output=True, timeout=60)
        except (OSError, subprocess.TimeoutExpired):
            raise ReleaseFailure('GitHub release operation did not complete; inspect state before retrying writes') from None
        if result.returncode:
            if missing and re.search(r'\bHTTP 404\b', result.stderr):
                return None
            raise ReleaseFailure('GitHub release operation failed')
        require(len(result.stdout.encode()) <= 2_000_000, 'GitHub metadata exceeds its budget')
        return json.loads(result.stdout, object_pairs_hook=unique_object) if result.stdout.strip() else None

    def upload(self, tag, files):
        recovery = re.fullmatch(r'developer-recovery-[a-f0-9]{40}(?:-[a-f0-9]{64})?', tag)
        fixture = re.fullmatch(r'developer-validation-[1-9][0-9]*-[1-9][0-9]*', tag)
        require(tag == TAG or recovery or fixture, 'Unexpected upload channel')
        require(files and all(path.is_file() and not path.is_symlink() for path in files), 'Unsafe upload files')
        if recovery:
            require([path.name for path in files] == [APK], 'Recovery uploads only preserve the fixed APK')
        elif fixture:
            require({path.name for path in files} == {'fixture-verification.json', 'fixture-SHA256SUMS.txt'},
                    'Unexpected validation assets')
        else:
            require(len(files) == 2 and all(re.fullmatch(r'Developer-0\.1\.[1-9][0-9]*-(?:verification\.json|SHA256SUMS\.txt)',
                    path.name) and path.stat().st_size <= 16_000 for path in files), 'Unexpected public evidence files')
        args = ['gh', 'release', 'upload', tag, '--repo', REPO, *files]
        if not recovery:
            args.append('--clobber')
        command(args)

    def download(self, tag, name, directory):
        require(tag == TAG or re.fullmatch(r'developer-recovery-[a-f0-9]{40}(?:-[a-f0-9]{64})?', tag) or
                re.fullmatch(r'developer-validation-[1-9][0-9]*-[1-9][0-9]*', tag), 'Unexpected download channel')
        require(name == APK or name in {'fixture-verification.json', 'fixture-SHA256SUMS.txt'}, 'Unexpected asset')
        if tag.startswith('developer-validation-'):
            command(['gh', 'release', 'download', tag, '--repo', REPO, '--pattern', name,
                     '--dir', directory, '--clobber'])
            return
        target = directory / name
        require(not target.is_symlink(), 'Unsafe download destination')
        url = 'https://github.com/' + REPO + '/releases/download/' + tag + '/' + name
        try:
            size = 0
            with urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': 'JarvisDeveloper'}),
                                        timeout=90) as response, target.open('wb') as stream:
                require(response.status == 200 and response.geturl().startswith('https://'), 'Public APK unavailable')
                while True:
                    chunk = response.read(65536)
                    if not chunk:
                        break
                    size += len(chunk)
                    require(size <= MAX_APK, 'Downloaded APK exceeds budget')
                    stream.write(chunk)
        except ReleaseFailure:
            raise
        except Exception:
            raise ReleaseFailure('Public APK download failed') from None

    def verify_public(self, expected):
        """Same unauthenticated metadata/download access used by the installed updater."""
        url = 'https://api.github.com/repos/' + REPO + '/releases/tags/' + TAG
        request = urllib.request.Request(url, headers={'User-Agent': 'JarvisDeveloper',
                                                       'Accept': 'application/vnd.github+json'})
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                require(response.status == 200, 'Public updater metadata unavailable')
                payload = response.read(2_000_001)
            require(len(payload) <= 2_000_000, 'Public metadata exceeds budget')
            meta = json.loads(payload, object_pairs_hook=unique_object)
            remote = asset(meta, APK)
            require(meta.get('tag_name') == TAG and meta.get('target_commitish') == expected['sourceCommit'] and
                    meta.get('name') == 'Jarvis Developer ' + expected['versionName'] and
                    meta.get('draft') is False and meta.get('prerelease') is True and
                    remote['size'] == expected['apkBytes'] and remote['digest'] == 'sha256:' + expected['apkSha256'],
                    'Public updater differs from approved artifact')
            download = 'https://github.com/' + REPO + '/releases/download/' + TAG + '/' + APK
            require(remote.get('browser_download_url') == download, 'Unexpected public APK URL')
            calculated = hashlib.sha256()
            count = 0
            with urllib.request.urlopen(urllib.request.Request(download, headers={'User-Agent': 'JarvisDeveloper'}),
                                        timeout=90) as response:
                require(response.status == 200 and response.geturl().startswith('https://'), 'Public APK unavailable')
                while True:
                    chunk = response.read(65536)
                    if not chunk:
                        break
                    count += len(chunk)
                    require(count <= expected['apkBytes'] and count <= MAX_APK, 'Public APK exceeds expected size')
                    calculated.update(chunk)
            require(count == expected['apkBytes'] and calculated.hexdigest() == expected['apkSha256'],
                    'Public APK checksum differs')
        except ReleaseFailure:
            raise
        except Exception:
            raise ReleaseFailure('Unauthenticated updater verification failed') from None


def tag_target(client, tag=TAG):
    require(tag == TAG or re.fullmatch(r'developer-source-tag-[a-f0-9]{40}', tag) or
            re.fullmatch(r'developer-recovery-[a-f0-9]{40}(?:-[a-f0-9]{64})?', tag), 'Unexpected source tag')
    value = client.api('git/ref/tags/' + tag, missing=True)
    if value is None:
        return None
    require(value.get('ref') == 'refs/tags/' + tag, 'Unexpected tag identity')
    target = value.get('object', {})
    require(target.get('type') == 'commit' and isinstance(target.get('sha'), str) and SHA.fullmatch(target['sha']),
            'Mutable release tag must point directly to a commit')
    return target['sha']


def release_metadata(client, tag=TAG, missing=False):
    require(tag == TAG or re.fullmatch(r'developer-recovery-[a-f0-9]{40}(?:-[a-f0-9]{64})?', tag),
            'Unexpected release metadata channel')
    value = client.api('releases/tags/' + tag, missing=missing)
    if value is None:
        return None
    require(isinstance(value, dict) and value.get('tag_name') == tag and value.get('draft') is False and
            value.get('prerelease') is True and type(value.get('id')) is int and value['id'] > 0,
            'Unexpected Developer release identity')
    require(isinstance(value.get('target_commitish'), str) and SHA.fullmatch(value['target_commitish']),
            'Release lacks immutable source provenance')
    return value


def asset(release, name):
    matches = [item for item in release.get('assets', []) if item.get('name') == name]
    require(len(matches) == 1, 'Expected one fixed release asset')
    item = matches[0]
    require(type(item.get('size')) is int and item['size'] > 0 and
            isinstance(item.get('digest'), str) and re.fullmatch(r'sha256:[a-f0-9]{64}', item['digest']),
            'Release asset lacks a valid size/digest')
    return item


def baseline(client, sdk, sdk_command=sdk_output):
    meta = release_metadata(client, missing=True)
    if meta is None:
        return None
    commit = client.api('git/commits/' + meta['target_commitish'])
    require(isinstance(commit, dict) and commit.get('sha') == meta['target_commitish'],
            'Recovery source commit is not available in this repository')
    remote = asset(meta, APK)
    with tempfile.TemporaryDirectory(prefix='developer-baseline-') as temporary:
        folder = Path(temporary)
        client.download(TAG, APK, folder)
        checked = inspect(folder, meta['target_commitish'], 1, 1, sdk, sdk_command=sdk_command)['apks'][0]
        require(checked['bytes'] == remote['size'] and 'sha256:' + checked['sha256'] == remote['digest'],
                'Published baseline bytes do not match GitHub metadata')
        require(meta.get('name') == 'Jarvis Developer ' + checked['versionName'],
                'Published version metadata is inconsistent; inspect the interrupted promotion before recovery writes')
    return dict(source=meta['target_commitish'], version=checked['versionCode'],
                sha256=checked['sha256'], bytes=checked['bytes'])


def ancestry(older, newer):
    require(SHA.fullmatch(older or '') and SHA.fullmatch(newer or ''), 'Invalid ancestry inputs')
    try:
        result = subprocess.run(['git', 'merge-base', '--is-ancestor', older, newer], capture_output=True, timeout=30)
    except (OSError, subprocess.TimeoutExpired):
        raise ReleaseFailure('Source ancestry check did not complete') from None
    require(result.returncode in (0, 1), 'Source ancestry could not be verified')
    return result.returncode == 0


def tag_mode(previous, source, recovery, ancestor=ancestry):
    require(previous is None or SHA.fullmatch(previous), 'Invalid prior tag SHA')
    require(SHA.fullmatch(source or '') and type(recovery) is bool, 'Invalid tag proposal')
    if previous is None:
        return 'create'
    if previous == source:
        return 'unchanged'
    if ancestor(previous, source):
        return 'fast-forward'
    require(recovery, 'Ordinary publication cannot rewind or replace divergent source')
    return 'approved-recovery'


def preflight(client, manifest, sdk, recovery=False, ancestor=ancestry, sdk_command=sdk_output):
    validate_manifest(manifest)
    current = baseline(client, sdk, sdk_command)
    if current:
        require(manifest['versionCode'] >= current['version'], 'Refusing an older updater version')
        if manifest['versionCode'] == current['version']:
            require(manifest['apkSha256'] == current['sha256'] and manifest['sourceCommit'] == current['source'],
                    'Same version identifies different APK bytes or source')
        require(current['source'] == manifest['sourceCommit'] or recovery or
                ancestor(current['source'], manifest['sourceCommit']),
                'Ordinary publication cannot replace newer or divergent published source')
    previous = tag_target(client)
    return dict(schemaVersion=1, manifest=manifest, baseline=current, previousTag=previous,
                tagMode=tag_mode(previous, manifest['sourceCommit'], recovery, ancestor), recoveryRequested=recovery)


def recovery_selection(client, current):
    """Select one immutable destination without modifying legacy recovery channels."""
    legacy = 'developer-recovery-' + current['source']
    existing = release_metadata(client, legacy, missing=True)
    tag = legacy
    if existing:
        require(existing['target_commitish'] == current['source'] and
                tag_target(client, legacy) == current['source'], 'Historical recovery source differs')
        previous = asset(existing, APK)
        if previous['digest'] != 'sha256:' + current['sha256']:
            tag += '-' + current['sha256']
            existing = release_metadata(client, tag, missing=True)
    target = tag_target(client, tag)
    require(target is None or target == current['source'], 'Existing recovery tag has another source')
    if existing:
        remote = asset(existing, APK)
        require(target == current['source'] and existing['target_commitish'] == current['source'] and
                remote['digest'] == 'sha256:' + current['sha256'] and remote['size'] == current['bytes'],
                'Immutable recovery snapshot mismatch; never overwrite it')
    return tag, existing


def verify_recovery(client, current, tag, folder):
    snapshot = release_metadata(client, tag)
    remote = asset(snapshot, APK)
    require(tag_target(client, tag) == current['source'] and snapshot['target_commitish'] == current['source'] and
            remote['digest'] == 'sha256:' + current['sha256'] and remote['size'] == current['bytes'],
            'Immutable recovery snapshot mismatch; never overwrite it')
    client.download(tag, APK, folder)
    require(digest(folder / APK) == current['sha256'] and (folder / APK).stat().st_size == current['bytes'],
            'Recovery readback bytes differ')
    fresh = release_metadata(client, tag)
    fresh_asset = asset(fresh, APK)
    require(fresh['id'] == snapshot['id'] and fresh['target_commitish'] == snapshot['target_commitish'] and
            all(fresh_asset.get(key) == remote.get(key) for key in ('id', 'name', 'size', 'digest')) and
            tag_target(client, tag) == current['source'],
            'Recovery identity changed during readback')


def recovery_plan(client, sdk, sdk_command=sdk_output):
    """Read-only evidence for one exact published APK, never promotion authority."""
    current = baseline(client, sdk, sdk_command)
    require(current is not None, 'Published Developer baseline is missing')
    tag, existing = recovery_selection(client, current)
    if existing:
        with tempfile.TemporaryDirectory(prefix='developer-recovery-plan-') as temporary:
            verify_recovery(client, current, tag, Path(temporary))
    require(baseline(client, sdk, sdk_command) == current, 'Published baseline changed during recovery audit')
    return dict(schemaVersion=1, scope='CURRENT_RELEASE_RECOVERY_ONLY',
                status='VERIFIED' if existing else 'APPROVAL_REQUIRED', baseline=current,
                packageName='com.jarvis.developer', signerSha256=SIGNER, destinationTag=tag,
                mutationPerformed=False, promotionEligibility='NOT_ASSESSED',
                signingCredentialsUsed=False, deviceInstallation='PENDING',
                approvalRequires=['approve_snapshot=true', 'approved_source_sha', 'approved_apk_sha256'])


def preserve_recovery(client, sdk, sdk_command=sdk_output, *, approved_baseline=None):
    """Reuse publication's preservation path, with optional exact standalone approval."""
    current = baseline(client, sdk, sdk_command)
    if approved_baseline is not None:
        require(current == approved_baseline, 'Approved published baseline changed; request fresh approval')
    if current is None:
        return dict(status='NOT_NEEDED', mutationPerformed=False)
    tag, existing = recovery_selection(client, current)
    attempted = False
    with tempfile.TemporaryDirectory(prefix='developer-snapshot-') as temporary:
        folder = Path(temporary)
        client.download(TAG, APK, folder)
        require(digest(folder / APK) == current['sha256'], 'Baseline changed before recovery preservation')
        if existing is None:
            require(baseline(client, sdk, sdk_command) == current, 'Published baseline changed before recovery creation')
            fresh_tag, fresh_existing = recovery_selection(client, current)
            require(fresh_tag == tag and fresh_existing is None, 'Recovery destination changed; audit again before writes')
        try:
            if existing is None:
                # No retries, clobber, deletion or cleanup after an uncertain write.
                attempted = True
                created = client.api('releases', 'POST', dict(tag_name=tag, target_commitish=current['source'], draft=False,
                           prerelease=True, make_latest='false', name='Jarvis Developer recovery 0.1.' + str(current['version']),
                           body='Immutable signed APK recovery. APK SHA-256 ' + current['sha256']))
                require(isinstance(created, dict) and type(created.get('id')) is int and created['id'] > 0 and
                        created.get('tag_name') == tag and created.get('target_commitish') == current['source'] and
                        created.get('draft') is False and created.get('prerelease') is True and
                        tag_target(client, tag) == current['source'], 'Created recovery identity differs')
                client.upload(tag, [folder / APK])
            verify_recovery(client, current, tag, folder)
            require(baseline(client, sdk, sdk_command) == current, 'Published baseline changed during preservation')
        except Exception:
            if attempted:
                raise ReleaseFailure('Recovery write outcome is uncertain; retain state and run a fresh read-only audit before any retry') from None
            raise
    return dict(status='PASSED', tag=tag, baseline=current, mutationPerformed=existing is None)


def recovery_context(write=False):
    """Read-only PR audits are allowed; writes require the actual canonical checkout."""
    controller = os.environ.get('GITHUB_SHA', '')
    require(os.environ.get('GITHUB_REPOSITORY') == REPO and SHA.fullmatch(controller) and
            command(['git', 'rev-parse', 'HEAD']) == controller, 'Recovery controller identity differs')
    if write:
        require(os.environ.get('GITHUB_REF') == BRANCH and os.environ.get('GITHUB_EVENT_NAME') == 'workflow_dispatch' and
                not os.environ.get('JARVIS_JOB_RECOVERY_SOURCE') and not os.environ.get('JARVIS_ARTIFACT_SOURCE'),
                'Explicit canonical recovery-only dispatch required')
    return controller


def preserve_approved_recovery(client, sdk, source, apk_hash, approve=False, sdk_command=sdk_output):
    require(approve is True and isinstance(source, str) and SHA.fullmatch(source) and
            isinstance(apk_hash, str) and HASH.fullmatch(apk_hash), 'Exact explicit snapshot approval required')
    controller = recovery_context(write=True)
    plan = recovery_plan(client, sdk, sdk_command)
    current = plan['baseline']
    require(current['source'] == source and current['sha256'] == apk_hash,
            'Approved source or APK changed; request fresh approval')
    require(ancestry(source, controller), 'Published source is outside canonical Developer history')
    result = preserve_recovery(client, sdk, sdk_command, approved_baseline=current)
    return dict(plan, status='VERIFIED', destinationTag=result['tag'], mutationPerformed=result['mutationPerformed'])


def require_publication(manifest, client):
    meta = release_metadata(client)
    remote = asset(meta, APK)
    require(meta['target_commitish'] == manifest['sourceCommit'] and
            meta.get('name') == 'Jarvis Developer ' + manifest['versionName'] and
            remote['size'] == manifest['apkBytes'] and remote['digest'] == 'sha256:' + manifest['apkSha256'],
            'Published APK/source/version differs from the verified build')
    return meta


def finalize(client, folder, manifest, plan, recovery=False, ancestor=ancestry):
    validate_manifest(manifest)
    require(isinstance(plan, dict) and set(plan) == {'schemaVersion', 'manifest', 'baseline', 'previousTag',
            'tagMode', 'recoveryRequested'} and type(plan['schemaVersion']) is int and
            plan['schemaVersion'] == 1 and plan['manifest'] == manifest and
            type(plan['recoveryRequested']) is bool and plan['recoveryRequested'] == recovery,
            'Preflight no longer matches this approved build')
    mode = tag_mode(plan['previousTag'], manifest['sourceCommit'], recovery, ancestor)
    require(mode == plan['tagMode'], 'Tag authorization changed')
    require_publication(manifest, client)
    current = tag_target(client)
    require(current in (plan['previousTag'], manifest['sourceCommit']), 'Latest source tag changed after preflight')
    files = [folder / name for name in filenames(manifest)]
    require(read_json(files[0]) == manifest and files[1].is_file() and not files[1].is_symlink() and
            files[1].read_text() == manifest['apkSha256'] + '  ' + APK + '\n', 'Prepared evidence changed')
    client.upload(TAG, files)
    meta = require_publication(manifest, client)
    for path in files:
        remote = asset(meta, path.name)
        require(remote['size'] == path.stat().st_size and remote['digest'] == 'sha256:' + digest(path),
                'Uploaded verification evidence differs')
    current = tag_target(client)
    require(current in (plan['previousTag'], manifest['sourceCommit']), 'Latest tag moved during upload')
    if current != manifest['sourceCommit']:
        if current is not None:
            backup = 'developer-source-tag-' + current
            existing = tag_target(client, backup)
            require(existing is None or existing == current, 'Immutable source-tag backup differs')
            if existing is None:
                client.api('git/refs', 'POST', dict(ref='refs/tags/' + backup, sha=current))
        require(tag_target(client) == current, 'Latest tag moved before alignment')
        if current is None:
            client.api('git/refs', 'POST', dict(ref='refs/tags/' + TAG, sha=manifest['sourceCommit']))
        else:
            client.api('git/refs/tags/' + TAG, 'PATCH',
                       dict(sha=manifest['sourceCommit'], force=mode == 'approved-recovery'))
    require(tag_target(client) == manifest['sourceCommit'], 'Latest source tag was not verified')
    require_publication(manifest, client)
    client.verify_public(manifest)
    return dict(schemaVersion=1, status='PASSED', scope='PUBLISHED_APK_PROVENANCE',
                sourceCommit=manifest['sourceCommit'], apkSha256=manifest['apkSha256'],
                sourceTagVerified=True, verificationAssetsVerified=True, publicUpdaterDownloadVerified=True,
                tests='PENDING', deviceInstallation='PENDING')


def audit_published(client, sdk, sdk_command=sdk_output):
    current = baseline(client, sdk, sdk_command)
    require(current is not None, 'No published Developer APK')
    client.verify_public(dict(sourceCommit=current['source'], versionName='0.1.' + str(current['version']),
                              apkBytes=current['bytes'], apkSha256=current['sha256']))
    require(tag_target(client) == current['source'], 'Published source tag is stale')
    return dict(schemaVersion=1, status='PASSED', scope='READ_ONLY_PUBLIC_UPDATER_AUDIT',
                publishedSource=current['source'], versionCode=current['version'], signerSha256=SIGNER,
                apkSha256=current['sha256'], publicUpdaterDownloadVerified=True,
                mutationPerformed=False, tests='PENDING', deviceInstallation='PENDING')


def exercise_draft_upload(client, directory):
    require(os.environ.get('GITHUB_REPOSITORY') == REPO and
            os.environ.get('GITHUB_EVENT_NAME') == 'workflow_dispatch' and
            os.environ.get('JARVIS_EXERCISE_DRAFT_UPLOAD') == 'true', 'Explicit draft validation dispatch required')
    source = os.environ.get('GITHUB_SHA', '')
    require(SHA.fullmatch(source), 'Invalid validation source')
    run, attempt = (int(os.environ[name]) for name in ('GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT'))
    require(run > 0 and attempt > 0, 'Invalid validation run')
    tag = f'developer-validation-{run}-{attempt}'
    directory.mkdir(parents=True, exist_ok=True)
    fixture = directory / 'fixture-verification.json'
    write_json(fixture, dict(scope='TEST_FIXTURE_ONLY', apkBuilt=False, signingCredentialsUsed=False))
    checksum = directory / 'fixture-SHA256SUMS.txt'
    checksum.write_text(digest(fixture) + '  ' + fixture.name + '\n')
    created = client.api('releases', 'POST', dict(tag_name=tag, target_commitish=source, draft=True,
                         prerelease=True, make_latest='false', name='Developer metadata upload validation',
                         body='Unpublished metadata-only test fixture. No APK, signing or production update.'))
    require(type(created.get('id')) is int and created['id'] > 0, 'Draft identity missing')
    release_id = created['id']
    try:
        require(created.get('draft') is True and created.get('tag_name') == tag and
                created.get('target_commitish') == source, 'Created draft identity differs')
        client.upload(tag, [fixture, checksum])
        meta = client.api('releases/' + str(release_id))
        require(meta.get('draft') is True and meta.get('tag_name') == tag and
                meta.get('target_commitish') == source, 'Validation draft changed')
        for path in [fixture, checksum]:
            remote = asset(meta, path.name)
            require(remote['size'] == path.stat().st_size and remote['digest'] == 'sha256:' + digest(path),
                    'Draft asset readback differs')
    finally:
        current = client.api('releases/' + str(release_id))
        require(current.get('draft') is True and current.get('tag_name') == tag and
                current.get('target_commitish') == source, 'Changed draft retained for review; no cleanup')
        client.api('releases/' + str(release_id), 'DELETE')
    return dict(schemaVersion=1, status='PASSED', scope='UNPUBLISHED_METADATA_UPLOAD_TEST',
                sourceCommit=source, productionChannelModified=False, apkBuilt=False,
                signingCredentialsUsed=False, draftCreatedAndRemoved=True)


def context(write=False):
    require(os.environ.get('GITHUB_REPOSITORY') == REPO and os.environ.get('GITHUB_REF') == BRANCH,
            'Trusted canonical Developer workflow required')
    if write:
        require(os.environ.get('GITHUB_EVENT_NAME') == 'workflow_dispatch', 'Explicit publication dispatch required')
    source = os.environ.get('JARVIS_ARTIFACT_SOURCE') or os.environ.get('GITHUB_SHA', '')
    controller = os.environ.get('GITHUB_SHA', '')
    require(SHA.fullmatch(source) and SHA.fullmatch(controller), 'Invalid workflow source')
    require(command(['git', 'rev-parse', 'HEAD']) == source, 'Checkout source differs from approved build')
    selected = os.environ.get('JARVIS_JOB_RECOVERY_SOURCE', '')
    require(not selected or selected == source, 'Recovery approval differs from APK source')
    require(selected or source == controller, 'Historical source needs explicit recovery selection')
    require(ancestry(source, controller), 'Source is outside approved Developer history')
    return source, bool(selected)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=['prepare', 'preflight', 'preserve-recovery', 'finalize',
                                             'audit-published', 'exercise-draft-upload',
                                             'audit-recovery-plan', 'preserve-approved-recovery'])
    parser.add_argument('--apks', type=Path)
    parser.add_argument('--evidence', type=Path, required=True)
    args = parser.parse_args()
    snapshot_mutation = False
    try:
        if args.operation in {'audit-recovery-plan', 'preserve-approved-recovery'}:
            controller = recovery_context(write=args.operation == 'preserve-approved-recovery')
            sdk = Path(os.environ['ANDROID_HOME']) / 'build-tools/34.0.0'
            client = GitHub()
            if args.operation == 'audit-recovery-plan':
                result = recovery_plan(client, sdk)
            else:
                result = preserve_approved_recovery(client, sdk, os.environ.get('APPROVED_SOURCE_SHA'),
                    os.environ.get('APPROVED_APK_SHA256'), os.environ.get('APPROVE_SNAPSHOT') == 'true')
                snapshot_mutation = result['mutationPerformed']
            result.update(controllerCommitSha=controller, runId=os.environ.get('GITHUB_RUN_ID'),
                          runAttempt=os.environ.get('GITHUB_RUN_ATTEMPT'))
            write_json(args.evidence / 'recovery-snapshot-plan.json', result)
            print('PASS: current-release recovery evidence; no candidate publication, signing or installation.')
            return 0
        if args.operation == 'exercise-draft-upload':
            result = exercise_draft_upload(GitHub(), args.evidence / 'fixture')
            write_json(args.evidence / 'draft-upload-verification.json', result)
            print('PASS: unpublished metadata upload and guarded draft cleanup; no APK or production change.')
            return 0
        if args.operation == 'audit-published':
            sdk = Path(os.environ['ANDROID_HOME']) / 'build-tools/34.0.0'
            write_json(args.evidence / 'public-updater-audit.json', audit_published(GitHub(), sdk))
            print('PASS: read-only public Developer updater audit; no build, signing or installation.')
            return 0
        require(args.apks is not None, 'APK directory required')
        source, recovery = context(write=args.operation != 'prepare')
        sdk = Path(os.environ['ANDROID_HOME']) / 'build-tools/34.0.0'
        run, attempt, version = (int(os.environ[name]) for name in ('GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT', 'GITHUB_RUN_NUMBER'))
        manifest = make_manifest(args.apks, source, run, attempt, version, sdk)
        if args.operation == 'prepare':
            write_manifest(args.evidence, manifest)
        elif args.operation == 'preserve-recovery':
            write_json(args.evidence / 'recovery-preservation.json', preserve_recovery(GitHub(), sdk))
        elif args.operation == 'preflight':
            write_json(args.evidence / 'tag-preflight.json', preflight(GitHub(), manifest, sdk, recovery))
        else:
            result = finalize(GitHub(), args.evidence, manifest, read_json(args.evidence / 'tag-preflight.json'), recovery)
            write_json(args.evidence / 'publication-verification.json', result)
        print('PASS: Developer ' + args.operation + '; tests and device installation are not attested by APK provenance.')
        return 0
    except Exception as failure:
        message = str(failure) if isinstance(failure, ReleaseFailure) else 'Developer release verification failed'
        if args.operation in {'audit-recovery-plan', 'preserve-approved-recovery'}:
            # Failure is evidence, never a restored approval or invented no-write claim.
            try:
                write_json(args.evidence / 'recovery-snapshot-plan.json', dict(schemaVersion=1,
                    scope='CURRENT_RELEASE_RECOVERY_ONLY', status='BLOCKED',
                    mutationPerformed='UNKNOWN' if 'outcome is uncertain' in message else snapshot_mutation,
                    promotionEligibility='NOT_ASSESSED', signingCredentialsUsed=False,
                    deviceInstallation='PENDING', requiresFreshAudit=True))
            except Exception:
                pass  # Preserve the primary failure even if local evidence cannot be written.
        print(message)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
