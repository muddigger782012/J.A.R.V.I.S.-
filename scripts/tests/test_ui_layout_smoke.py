from pathlib import Path
import sys
import unittest
import xml.etree.ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from ui_layout_smoke import bounds, choose_apk, find_target


class AndroidHierarchyTests(unittest.TestCase):
    def test_android_bounds_parse_digits_and_reject_hidden_or_malformed_targets(self):
        self.assertEqual((12, 34, 567, 890), bounds(ET.Element("node", bounds="[12,34][567,890]")))
        self.assertEqual((-10, 0, 20, 40), bounds(ET.Element("node", bounds="[-10,0][20,40]")))
        for raw in ("", "[0,0][0,0]", "[40,20][10,30]", "[1,2,3,4]", "[1,2][3,4]extra"):
            self.assertIsNone(bounds(ET.Element("node", bounds=raw)), raw)

    def test_theme_row_uses_accessibility_description_without_matching_alternative(self):
        root = ET.fromstring('''<hierarchy>
            <node package="com.assistant.core" clickable="true" content-desc="Default Alternative. Apply this theme" bounds="[0,0][200,60]" />
            <node package="com.assistant.core" clickable="true" content-desc="Default. Active theme" bounds="[0,60][200,120]" />
        </hierarchy>''')
        self.assertEqual("Default. Active theme", find_target(root, label="Default").get("content-desc"))

    def test_disabled_hidden_and_external_nodes_cannot_be_selected(self):
        root = ET.fromstring('''<hierarchy>
            <node package="com.android.settings" clickable="true" text="Settings" bounds="[0,0][200,60]" />
            <node package="com.assistant.core" enabled="false" clickable="true" content-desc="Settings" bounds="[0,60][200,120]" />
            <node package="com.assistant.core" clickable="true" content-desc="Settings" bounds="[0,0][0,0]" />
            <node package="com.assistant.core" clickable="true" content-desc="Settings" bounds="[0,500][200,560]" />
            <node package="com.assistant.core" clickable="true" content-desc="Settings" bounds="[0,120][200,180]" />
        </hierarchy>''')
        self.assertEqual("[0,120][200,180]", find_target(root, label="Settings", viewport=(200, 240)).get("bounds"))

    def test_ordinary_buttons_match_text_and_controls_match_resource_id(self):
        root = ET.fromstring('''<hierarchy>
            <node package="com.assistant.core" clickable="true" text="CANCEL" resource-id="android:id/button2" bounds="[0,0][100,60]" />
            <node package="com.assistant.core" resource-id="com.assistant.core:id/etCommandInput" bounds="[0,60][200,120]" />
        </hierarchy>''')
        self.assertEqual("android:id/button2", find_target(root, label="Cancel").get("resource-id"))
        self.assertIsNotNone(find_target(root, identifier="etCommandInput"))

    def test_apk_selection_requires_exact_abi_or_universal_fallback(self):
        arm = Path("app-arm64-v8a-debug.apk")
        x64 = Path("app-x86_64-debug.apk")
        universal = Path("app-universal-debug.apk")
        self.assertEqual(x64, choose_apk([arm, x64, universal], "x86_64"))
        self.assertEqual(arm, choose_apk([arm, x64, universal], "arm64-v8a"))
        self.assertEqual(universal, choose_apk([x64, universal], "x86"))
        with self.assertRaisesRegex(AssertionError, "No APK for emulator ABI x86"):
            choose_apk([x64], "x86")


if __name__ == "__main__":
    unittest.main()
