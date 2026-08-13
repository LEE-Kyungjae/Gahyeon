#!/usr/bin/env python3

from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from verify_piper_training_identity import (
    verify_local_completion,
    verify_remote_completion,
    verify_submission,
)


class PiperTrainingIdentityTest(unittest.TestCase):
    digest = "a" * 64

    def ready(self) -> dict:
        return {"package": {"archive_sha256": self.digest}, "sourceIdentity": {"completed": 5000}}

    def submitted(self) -> dict:
        return {
            "sha256": self.digest,
            "remoteRoot": f"/home/ubuntu/piper-voice/voicebox-diverse5000-{self.digest[:12]}",
        }

    def test_accepts_one_continuous_training_identity(self) -> None:
        ready = self.ready()
        submitted = self.submitted()
        remote = {"datasetSha256": self.digest}
        complete = {"remote": remote, "sourceIdentity": ready["sourceIdentity"]}
        self.assertEqual(verify_submission(ready, submitted), self.digest)
        verify_remote_completion(submitted, remote)
        verify_local_completion(ready, submitted, complete)

    def test_rejects_stale_submission_remote_root_and_completions(self) -> None:
        with self.assertRaisesRegex(ValueError, "submission identity"):
            verify_submission(self.ready(), {**self.submitted(), "sha256": "b" * 64})
        with self.assertRaisesRegex(ValueError, "remote root"):
            verify_submission(self.ready(), {**self.submitted(), "remoteRoot": "/tmp/wrong"})
        with self.assertRaisesRegex(ValueError, "remote Piper completion"):
            verify_remote_completion(self.submitted(), {"datasetSha256": "b" * 64})
        with self.assertRaisesRegex(ValueError, "source identity"):
            verify_local_completion(
                self.ready(), self.submitted(),
                {"remote": {"datasetSha256": self.digest}, "sourceIdentity": {"completed": 4000}},
            )


if __name__ == "__main__":
    unittest.main()
