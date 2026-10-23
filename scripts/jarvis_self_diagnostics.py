#!/usr/bin/env python3
"""Bounded read-only diagnostics. Repository text and logs are data, never commands."""
import sys

# Isolated mode excludes repository-local modules and PYTHONPATH before any
# non-built-in import can run. Importing this API is for a trusted host only.
if __name__ == '__main__' and not sys.flags.isolated:
    raise SystemExit('Run this read-only tool with python3 -I to exclude untrusted import paths.')

import argparse
import ast
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import time

SUFFIXES = {'.kt', '.kts', '.java', '.py', '.gradle', '.xml'}
SKIP = {'.git', '.gradle', 'build', '.idea', 'node_modules', 'dist', '.venv', 'venv', 'artifacts', '__pycache__'}
MAX_BYTES, MAX_FILES, MAX_ENTRIES, MAX_TOTAL_BYTES = 1024 * 1024, 5000, 20000, 16 * 1024 * 1024
MAX_FINDINGS, MAX_DEPTH, MAX_SECONDS = 300, 20, 10
RULES = {
    'credential-literal': (1, 'high', 'security', 'Potential embedded credential; inspect locally without copying its value.', 'Remove a confirmed secret and rotate it through the owner.', 'Heuristic; placeholders and fixtures can look like credentials.', 'medium'),
    'unsafe-shell': (1, 'medium', 'security', 'A shell-enabled subprocess needs a trust-boundary review.', 'Use structured arguments where feasible.', 'A static match does not prove exploitable input.', 'medium'),
    'compile-failure': (2, 'high', 'broken-functionality', 'The supplied build log contains a compiler failure.', 'Reproduce the failing compilation and repair the cited source.', 'The first observed compiler error may be a downstream symptom.', 'medium'),
    'test-failure': (2, 'high', 'broken-functionality', 'The supplied build log reports failing tests.', 'Reproduce the failing behavior while preserving existing assertions.', 'Log evidence alone does not identify the root cause.', 'medium'),
    'static-analysis-failure': (2, 'high', 'broken-functionality', 'The supplied build log reports static-analysis errors.', 'Review the cited analysis finding against the unchanged baseline before proposing a repair.', 'Existing analysis debt is not evidence that the current change introduced a regression.', 'medium'),
    'crash': (2, 'high', 'reliability', 'The supplied application log reports an Android crash.', 'Reproduce the crash and inspect its exception path.', 'A synthetic or stale log may not describe the installed version.', 'medium'),
    'memory-pressure': (2, 'high', 'performance', 'The supplied log reports memory exhaustion.', 'Inspect allocation lifetime and reproduce under a bounded workload.', 'A log marker is evidence of a report, not a proven allocation cause.', 'high'),
    'application-not-responding': (2, 'high', 'reliability', 'The supplied log reports an unresponsive application.', 'Inspect main-thread blocking and reproduce the stall.', 'System load or a dependency may contribute; timing needs device evidence.', 'high'),
    'incomplete-feature': (6, 'low', 'intelligence', 'An explicit unimplemented-code marker needs review.', 'Identify whether a reachable supported feature is incomplete.', 'Intentional abstract or test code may be valid.', 'medium'),
    'duplicate-function': (5, 'low', 'maintainability', 'Multiple Python functions have the same parsed statement body.', 'Review whether shared behavior belongs in one tested helper.', 'Identical fixtures or independent contracts may intentionally repeat code.', 'medium'),
    'large-source': (5, 'medium', 'maintainability', 'This source exceeds 1200 lines.', 'Review separation of responsibilities without replacing working systems.', 'File size is an architectural review signal, not a defect.', 'high'),
    'todo-marker': (6, 'low', 'technical-debt', 'An outstanding TODO or FIXME marker needs review.', 'Triage the marked debt against actual supported behavior.', 'Comments can be stale; no incomplete feature is inferred automatically.', 'low'),
    'large-file': (7, 'info', 'coverage', 'Source exceeds the per-file read limit.', 'Inspect this file separately with an appropriately bounded tool.', 'This file was not analyzed.', 'low'),
    'unreadable': (7, 'info', 'coverage', 'Source could not be read as regular UTF-8 data.', 'Check access and encoding through an authorized review.', 'No conclusions were drawn from unreadable content.', 'low'),
}
LOG_RULES = {
    'compile-failure': re.compile(r'(?:(?:^|\s)e: |error:|Compilation failed|Execution failed for task .*compile)', re.I),
    'test-failure': re.compile(r'(?:FAILURES!!!|[1-9][0-9]* tests? (?:completed, .*failed|failed)|FAILED \(|There (?:was|were) .*failure)', re.I),
    'static-analysis-failure': re.compile(r'(?:Lint found [1-9][0-9]* errors|: Error:.*\[[A-Za-z]+\])', re.I),
    'crash': re.compile(r'(?:FATAL EXCEPTION:|Fatal signal [0-9]+)', re.I),
    'memory-pressure': re.compile(r'(?:OutOfMemoryError|out of memory|java heap space)', re.I),
    'application-not-responding': re.compile(r'(?:ANR in |Application Not Responding)', re.I),
}


def safe_path(value):
    """Do not export control characters, arbitrary paths or credential-like filenames."""
    if len(value) > 240 or not re.fullmatch(r'[A-Za-z0-9_./-]+', value):
        return None
    if any(p in {'', '.', '..'} for p in value.split('/')) or value.startswith('/'):
        return None
    if re.search(r'(?:AIza[A-Za-z0-9_-]{20,}|gh[pousr]_[A-Za-z0-9]{20,}|sk-[A-Za-z0-9_-]{20,})', value):
        return None
    return value


def scan(root, *, build_log=None, crash_log=None, revision=None, repository='local-authorized-repository'):
    root = Path(root).resolve(strict=True)
    if not root.is_dir():
        raise ValueError('root must be an existing authorized directory')
    if revision is not None and not re.fullmatch(r'[a-f0-9]{40}', revision):
        raise ValueError('revision must be a full lowercase commit SHA')
    if not re.fullmatch(r'[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)?', repository) or safe_path(repository) is None or len(repository) > 120:
        raise ValueError('invalid repository identity')
    findings, inventory, limits, duplicates = [], [], set(), {}
    counts = {'scanned_files': 0, 'visited_entries': 0, 'read_bytes': 0, 'skipped_files': 0}
    deadline = time.monotonic() + MAX_SECONDS

    def add(rule, path='.', line=None, digest=None, kind='source', log_line=None):
        if len(findings) >= MAX_FINDINGS:
            limits.add('findings'); return
        priority, severity, category, explanation, benefit, risk, difficulty = RULES[rule]
        evidence = {'kind': kind}
        if digest: evidence['sha256'] = digest
        if line: evidence['line'] = line
        if log_line: evidence['log_line'] = log_line
        identifier = hashlib.sha256(json.dumps([rule, path, line, digest, kind]).encode()).hexdigest()
        if any(f['id'] == identifier for f in findings): return
        findings.append(dict(id=identifier, rule=rule, priority=priority, severity=severity, category=category,
            path=path, line=line, explanation=explanation, expected_benefit=benefit, risk=risk,
            difficulty=difficulty, confidence='observed-marker' if kind != 'source' else 'static-review-signal', evidence=evidence))

    def stopped():
        if time.monotonic() >= deadline: limits.add('time'); return True
        return bool(limits & {'entries', 'files', 'bytes', 'findings'})

    def inspect(fd, name, relative):
        if counts['scanned_files'] + counts['skipped_files'] >= MAX_FILES:
            limits.add('files'); return
        counts['skipped_files'] += 1
        descriptor = None
        try:
            descriptor = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=fd)
            info = os.fstat(descriptor)
            if not stat.S_ISREG(info.st_mode): limits.add('source-type-changed'); return
            if info.st_size > MAX_BYTES: limits.add('file-size'); add('large-file', relative); return
            allowance = min(MAX_BYTES + 1, MAX_TOTAL_BYTES - counts['read_bytes'] + 1)
            with os.fdopen(descriptor, 'rb') as source:
                descriptor = None
                data = source.read(allowance)
            counts['read_bytes'] += len(data)
            if counts['read_bytes'] > MAX_TOTAL_BYTES: limits.add('bytes'); return
            if len(data) > MAX_BYTES: limits.add('file-size'); add('large-file', relative); return
            text = data.decode('utf-8')
        except (OSError, UnicodeError):
            limits.add('unreadable-source'); add('unreadable', relative); return
        finally:
            if descriptor is not None: os.close(descriptor)
        counts['skipped_files'] -= 1; counts['scanned_files'] += 1
        digest = hashlib.sha256(data).hexdigest(); inventory.append((relative, digest))
        lines = text.splitlines()
        if len(lines) > 1200: add('large-source', relative, digest=digest)
        for number, line in enumerate(lines, 1):
            if stopped(): return
            if re.search(r'(?i)(?:api[_-]?key|access[_-]?token|password|secret)\s*[:=]\s*[\"\'][^\"\'\n]{12,}[\"\']', line) or re.search(r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----', line):
                add('credential-literal', relative, number, digest)
            if re.search(r'\bshell\s*=\s*True\b', line): add('unsafe-shell', relative, number, digest)
            if 'TODO' in line or 'FIXME' in line: add('todo-marker', relative, number, digest)
            if 'NotImplementedError' in line or re.search(r'\bTODO\s*\(', line): add('incomplete-feature', relative, number, digest)
        if relative.endswith('.py') and len(data) <= 200_000 and not stopped():
            try:
                tree = ast.parse(text)
                for node in ast.walk(tree):
                    if stopped(): break
                    if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and len(node.body) >= 4 and getattr(node, 'end_lineno', 0) - node.lineno >= 8:
                        key = hashlib.sha256(ast.dump(ast.Module(body=node.body, type_ignores=[]), include_attributes=False).encode()).hexdigest()
                        old = duplicates.get(key)
                        if old and old[:2] != (relative, node.lineno):
                            add('duplicate-function', old[0], old[1], old[2]); add('duplicate-function', relative, node.lineno, digest)
                        elif len(duplicates) < 1000: duplicates[key] = (relative, node.lineno, digest)
            except (SyntaxError, RecursionError): pass

    def walk(fd, prefix='', depth=0):
        try:
            with os.scandir(fd) as entries:
                for entry in entries:
                    if stopped(): break
                    if counts['visited_entries'] >= MAX_ENTRIES: limits.add('entries'); break
                    counts['visited_entries'] += 1
                    name = entry.name
                    if name in SKIP: continue
                    relative = name if not prefix else prefix + '/' + name
                    if safe_path(relative) is None:
                        limits.add('unsafe-path'); continue
                    info = os.stat(name, dir_fd=fd, follow_symlinks=False)
                    if stat.S_ISLNK(info.st_mode): continue
                    if stat.S_ISDIR(info.st_mode):
                        if depth >= MAX_DEPTH: limits.add('depth'); continue
                        child = None
                        try:
                            child = os.open(name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=fd)
                            walk(child, relative, depth + 1)
                        except OSError: limits.add('unreadable-directory')
                        finally:
                            if child is not None: os.close(child)
                    elif stat.S_ISREG(info.st_mode) and Path(name).suffix in SUFFIXES:
                        inspect(fd, name, relative)
        except OSError: limits.add('unreadable-directory')

    # Directory-relative descriptors and O_NOFOLLOW prevent symlink replacement
    # from escaping the authorized root. Unsupported platforms fail closed.
    if not hasattr(os, 'O_NOFOLLOW') or not hasattr(os, 'O_DIRECTORY'):
        raise ValueError('safe descriptor-relative scanning is unavailable on this platform')
    fd = os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try: walk(fd)
    finally: os.close(fd)
    for kind, path in (('build-log', build_log), ('crash-log', crash_log)):
        if path is None: continue
        # Separate explicit file arguments authorize only these local log inputs.
        descriptor = None
        try:
            descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
            info = os.fstat(descriptor)
            if not stat.S_ISREG(info.st_mode) or info.st_size > MAX_BYTES:
                limits.add('log-size-or-type'); continue
            with os.fdopen(descriptor, 'rb') as source:
                descriptor = None; data = source.read(MAX_BYTES + 1)
            if len(data) > MAX_BYTES: limits.add('log-size-or-type'); continue
            log_text = data.decode('utf-8', errors='replace')
            lint_failure = bool(re.search(r'Lint found [1-9][0-9]* errors', log_text))
            for number, line in enumerate(log_text.splitlines(), 1):
                if stopped(): break
                for rule, pattern in LOG_RULES.items():
                    if rule == 'compile-failure' and lint_failure and not re.search(r'(?:\be: |Compilation failed|Execution failed for task .*compile)', line, re.I): continue
                    if rule == 'static-analysis-failure' and not lint_failure: continue
                    existing = next((f for f in findings if f['rule'] == rule and f['evidence']['kind'] == kind), None)
                    if pattern.search(line) and (existing is None or existing['path'] == '.'):
                        # No raw log text, log names, credentials or personal paths leave this process.
                        source_path, source_line, source_digest = '.', number, None
                        for known, digest in inventory:
                            match = re.search(r'(?<![A-Za-z0-9_.-])' + re.escape(known) + r':([1-9][0-9]{0,5})(?:\D|$)', line)
                            if match:
                                source_path, source_line, source_digest = known, int(match[1]), digest
                                break
                        if existing:
                            if source_path == '.': continue
                            findings.remove(existing)
                        add(rule, source_path, source_line, source_digest, kind, log_line=number)
        except OSError: limits.add('unreadable-log')
        finally:
            if descriptor is not None: os.close(descriptor)
    ordered = sorted(findings, key=lambda f: (f['priority'], f['rule'], f['path'], f['line'] or 0))
    snapshot = hashlib.sha256(json.dumps(sorted(inventory), separators=(',', ':')).encode()).hexdigest()
    report = dict(schema_version=2, mode='read-only', repository=repository, revision=revision,
        revision_verified=False, snapshot=snapshot, complete=not limits, limitations=sorted(limits), **counts, findings=ordered,
        tests={'state': 'not-run'}, authorization={'source_writes': False, 'merge': False, 'publish': False, 'install': False})
    report['report_id'] = hashlib.sha256(json.dumps(report, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path('.'), help='Explicit authorized source directory')
    parser.add_argument('--json', action='store_true')
    parser.add_argument('--build-log', type=Path, help='Explicit local build/Actions log; never executed or echoed')
    parser.add_argument('--crash-log', type=Path, help='Explicit local Android log; never executed or echoed')
    parser.add_argument('--revision'); parser.add_argument('--repository', default='local-authorized-repository')
    args = parser.parse_args()
    try: report = scan(args.root, build_log=args.build_log, crash_log=args.crash_log, revision=args.revision, repository=args.repository)
    except (ValueError, OSError): parser.error('authorized inputs could not be safely inspected')
    if args.json: print(json.dumps(report, indent=2))
    else:
        print(f"Read-only scan: {report['scanned_files']} sources, {len(report['findings'])} review signals; complete={report['complete']}")
        for item in report['findings'][:25]: print(f"P{item['priority']} {item['path']}:{item['line'] or '-'} {item['rule']} — {item['explanation']}")
        print('No source changes, commands, builds, approvals or publication were performed.')


if __name__ == '__main__':
    main()
