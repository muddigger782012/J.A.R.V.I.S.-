"""Bounded, private JUnit adapter for the existing Developer build-feedback parser."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET


def collect(results: Path, source: str) -> dict:
    if not re.fullmatch(r'[a-f0-9]{40}', source) or results.is_symlink():
        raise ValueError('Invalid evidence identity')
    files = sorted(results.glob('TEST-*.xml'))
    if not 0 < len(files) <= 256 or sum(p.stat().st_size for p in files) > 3_000_000:
        raise ValueError('JUnit evidence is absent or exceeds its budget')
    totals = dict.fromkeys(('tests', 'failures', 'errors', 'skipped'), 0)
    cases, identities, digests = [], set(), []
    received = 0
    for path in files:
        if not path.is_file() or path.is_symlink():
            raise ValueError('Regular JUnit evidence required')
        with path.open('rb') as stream:
            raw = stream.read(3_000_001 - received)
        received += len(raw)
        if received > 3_000_000:
            raise ValueError('JUnit input exceeds budget')
        if b'\0' in raw or b'<!DOCTYPE' in raw.upper() or b'<!ENTITY' in raw.upper():
            raise ValueError('JUnit declarations are forbidden')
        root = ET.fromstring(raw)
        if root.tag != 'testsuite' or not root.get('name'):
            raise ValueError('JUnit suite identity is missing')
        counts = {key: int(root.attrib[key]) for key in totals}
        actual = dict.fromkeys(totals, 0)
        for case in root.findall('testcase'):
            name = case.get('name')
            if not name or len(name) > 4096:
                raise ValueError('Invalid JUnit case identity')
            identity = hashlib.sha256((root.attrib['name'] + '\0' + name).encode()).hexdigest()
            if identity in identities:
                raise ValueError('Duplicate JUnit case identity')
            identities.add(identity)
            outcomes = [key for key in ('failure', 'error', 'skipped') if case.find(key) is not None]
            if len(outcomes) > 1:
                raise ValueError('Ambiguous JUnit outcome')
            result = outcomes[0] if outcomes else 'passed'
            actual['tests'] += 1
            if result != 'passed':
                actual[dict(failure='failures', error='errors', skipped='skipped')[result]] += 1
            cases.append(dict(suite='PersonalJUnitEvidence', name=identity, result=result))
        if counts != actual:
            raise ValueError('JUnit counts do not match actual cases')
        for key in totals:
            totals[key] += counts[key]
        digests.append(hashlib.sha256(raw).hexdigest())
    if not 0 < totals['tests'] <= 2000:
        raise ValueError('JUnit case inventory exceeds budget')
    # One explicitly aggregated module; every case identifier hashes its real suite and name.
    # Raw names, source paths, stdout, stack traces and error messages never enter this record.
    digest = hashlib.sha256('\n'.join(digests).encode()).hexdigest()
    record = dict(sourceCommit=source, totals=totals,
        suites=[dict(name='PersonalJUnitEvidence', sha256=digest, **totals)], cases=cases)
    if len(json.dumps(record, separators=(',', ':')).encode()) > 299_900:
        raise ValueError('JUnit summary exceeds log budget')
    return record


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--source', required=True)
    parser.add_argument('--results', type=Path, default=Path('app/build/test-results/testDebugUnitTest'))
    args = parser.parse_args()
    try:
        if subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip() != args.source:
            raise ValueError('Checkout identity changed')
        record = collect(args.results, args.source)
    except Exception:
        raise SystemExit('Private Personal test evidence could not be verified') from None
    print('JARVIS_JUNIT_SUMMARY=' + json.dumps(record, separators=(',', ':')))


if __name__ == '__main__':
    main()
