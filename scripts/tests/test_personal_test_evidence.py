from pathlib import Path
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
import json

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from personal_test_evidence import collect


class PersonalTestEvidenceTests(unittest.TestCase):
    def test_the_full_supported_inventory_fits_the_private_summary_budget(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            suite = ET.Element('testsuite', dict(name='PrivateLargeSuite', tests='2000', failures='0', errors='0', skipped='0'))
            for index in range(2000):
                ET.SubElement(suite, 'testcase', dict(name=f'case-{index}'))
            (root/'TEST-many.xml').write_bytes(ET.tostring(suite))
            result = collect(root, 'a'*40)
            self.assertEqual(2000, result['totals']['tests'])
            self.assertLess(len(json.dumps(result, separators=(',', ':')).encode()), 300000)

    def write(self, root, filename, suite='PrivateSuite', name='owner-private-example', outcome=None):
        xml = ET.Element('testsuite', dict(name=suite, tests='1', failures='1' if outcome == 'failure' else '0',
            errors='1' if outcome == 'error' else '0', skipped='1' if outcome == 'skipped' else '0'))
        case = ET.SubElement(xml, 'testcase', dict(name=name))
        if outcome:
            ET.SubElement(case, outcome).text = 'private diagnostic or credential must not be copied'
        ET.SubElement(xml, 'system-out').text = 'private stdout must not be copied'
        path = root/filename
        path.write_bytes(ET.tostring(xml))
        return path

    def test_actual_cases_and_failures_are_counted_without_names_paths_or_output(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            self.write(root, 'TEST-a.xml')
            self.write(root, 'TEST-b.xml', name='private-second', outcome='failure')
            result = collect(root, 'a'*40)
            self.assertEqual(dict(tests=2, failures=1, errors=0, skipped=0), result['totals'])
            encoded = json.dumps(result)
            for private in ('owner-private', 'private-second', 'PrivateSuite', folder, 'private stdout', 'credential'):
                self.assertNotIn(private, encoded)
            self.assertEqual(64, len(result['cases'][0]['name']))

    def test_duplicates_bad_counts_and_declarations_cannot_produce_a_green_record(self):
        for mutation in ('duplicate', 'count', 'declaration'):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as folder:
                root = Path(folder); first = self.write(root, 'TEST-a.xml')
                if mutation == 'duplicate':
                    self.write(root, 'TEST-b.xml')
                elif mutation == 'count':
                    first.write_text(first.read_text().replace('tests="1"', 'tests="2"'))
                else:
                    first.write_text('<!DOCTYPE testsuite>' + first.read_text())
                with self.assertRaises(ValueError):
                    collect(root, 'a'*40)

    def test_missing_symlinked_or_unknown_source_evidence_fails_closed(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            with self.assertRaises(ValueError):
                collect(root, 'a'*40)
            real = self.write(root, 'regular.xml')
            (root/'TEST-link.xml').symlink_to(real)
            with self.assertRaises(ValueError):
                collect(root, 'a'*40)
            with self.assertRaises(ValueError):
                collect(root, 'main')
