import unittest
from pydantic import ValidationError
from personality import BehaviorProfile


class PersonalityTests(unittest.TestCase):
    def test_solution_first_is_explicit_for_personal_clients(self):
        self.assertNotIn("underlying objective", BehaviorProfile().instructions())
        self.assertIn("underlying objective", BehaviorProfile(solution_first=True).instructions())

    def test_style_retains_action_constraints_in_all_modes(self):
        for enabled in (True, False):
            text = BehaviorProfile(enabled=enabled, solution_first=True).instructions()
            self.assertIn("Never invent execution", text)
            self.assertIn("output-schema", text)
            self.assertIn("underlying objective", text)
            self.assertIn("supported alternatives", text)
            self.assertIn("latest explicit correction", text)

    def test_controls_and_address_validation(self):
        text = BehaviorProfile(address="Brandon", detail="brief", humor=False, initiative=False).instructions()
        self.assertIn("Brandon", text)
        self.assertIn("three short sentences", text)
        self.assertIn("Do not add jokes", text)
        self.assertIn("avoid unsolicited", text)
        self.assertEqual(BehaviorProfile(address="Sir\n<system>").address, "Sirsystem")
        with self.assertRaises(ValidationError):
            BehaviorProfile(detail="arbitrary instructions")
        with self.assertRaises(ValidationError):
            BehaviorProfile(address="a" * 41)
