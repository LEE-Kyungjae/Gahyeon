#!/usr/bin/env python3

import unittest

from voicebox_supervisor_decision import decide


class VoiceboxSupervisorDecisionTest(unittest.TestCase):
    def test_old_4000_boundary_continues_to_5000(self) -> None:
        self.assertEqual("generate", decide(4000, 5000, False))

    def test_active_generator_is_never_duplicated(self) -> None:
        self.assertEqual("wait", decide(3714, 5000, True))
        self.assertEqual("wait", decide(4000, 5000, True))

    def test_exact_target_waits_until_generator_has_exited(self) -> None:
        self.assertEqual("wait", decide(5000, 5000, True))
        self.assertEqual("finalize", decide(5000, 5000, False))

    def test_one_short_of_target_restarts_generation(self) -> None:
        self.assertEqual("generate", decide(4999, 5000, False))

    def test_invalid_progress_is_rejected(self) -> None:
        for completed, target in ((-1, 5000), (5001, 5000), (0, 0)):
            with self.subTest(completed=completed, target=target), self.assertRaises(ValueError):
                decide(completed, target, False)


if __name__ == "__main__":
    unittest.main()
