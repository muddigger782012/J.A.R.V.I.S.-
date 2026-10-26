from pathlib import Path
import sys
import unittest
import xml.etree.ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from ui_scroll_navigation import tap_scrolling


class UiScrollNavigationTests(unittest.TestCase):
    def navigate(self, start, target):
        page = [start]
        calls = []

        def hierarchy():
            root = ET.Element('hierarchy')
            view = ET.SubElement(root, 'node', {'resource-id': 'com.assistant.core:id/appearanceScroll',
                'class': 'android.widget.ScrollView', 'scrollable': 'true', 'bounds': '[42,496][1038,1752]'})
            for name in (('Default', 'Stark'), ('Stark', 'Cybertron'), ('Glacier', 'Emerald'))[page[0]]:
                ET.SubElement(view, 'node', {'resource-id': 'com.assistant.core:id/btnTheme'+name,
                    'text': name, 'bounds': '[132,730][948,867]'})
            return root

        def adb(*args):
            calls.append(args)
            if args[2] == 'swipe':
                delta = -1 if int(args[4]) < int(args[6]) else 1
                page[0] = min(2, max(0, page[0] + delta))

        tap_scrolling(target, hierarchy, adb, lambda _: None)
        return calls

    def test_default_above_a_preserved_theme_position_is_reached_by_scrolling_up(self):
        calls = self.navigate(1, 'btnThemeDefault')
        self.assertEqual('swipe', calls[0][2])
        self.assertLess(int(calls[0][4]), int(calls[0][6]))
        self.assertEqual('tap', calls[-1][2])

    def test_after_observing_the_top_it_searches_forward_for_later_controls(self):
        calls = self.navigate(0, 'btnThemeCybertron')
        swipes = [c for c in calls if c[2] == 'swipe']
        self.assertTrue(any(int(c[4]) > int(c[6]) for c in swipes))
        self.assertEqual('tap', calls[-1][2])

    def test_a_missing_control_still_fails_at_the_original_bound(self):
        with self.assertRaisesRegex(AssertionError, 'missing after scrolling'):
            self.navigate(1, 'btnMissingControl')
