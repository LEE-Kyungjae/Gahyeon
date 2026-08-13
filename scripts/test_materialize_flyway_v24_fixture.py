#!/usr/bin/env python3

import tempfile
import unittest
from pathlib import Path

import materialize_flyway_v24_fixture as fixture


class FlywayV24FixtureTest(unittest.TestCase):
    def test_checked_in_fixture_matches_verified_gitops_commit(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "fixture"
            manifest = fixture.materialize(output)
            self.assertEqual("12ebae244fd3efcdbf241dc5215428327552800f", manifest.source_commit)
            self.assertEqual(24, len(list(output.glob("V*__*.sql"))))
            self.assertTrue((output / "V7__Add_weather_rag_chunks_with_pgvector.sql").is_file())
            self.assertTrue((output / "V24__Persist_world_emotion_intensity.sql").is_file())

    def test_tampered_current_migration_fails_closed(self) -> None:
        manifest = fixture.load_manifest(fixture.DEFAULT_MANIFEST)
        with tempfile.TemporaryDirectory() as directory:
            checkout = Path(directory) / "checkout"
            for _, relative in manifest.entries:
                destination = checkout / relative
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes((fixture.ROOT / relative).read_bytes())
            first_relative = manifest.entries[0][1]
            (checkout / first_relative).write_text("SELECT 'tampered';\n", encoding="utf-8")

            def authoritative(_root: Path, _commit: str, relative: str) -> bytes:
                return (fixture.ROOT / relative).read_bytes()

            with self.assertRaisesRegex(fixture.FixtureError, "current applied migration changed"):
                fixture.verify_fixture_sources(manifest, checkout, authoritative)

    def test_manifest_must_cover_every_version_exactly_once(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            manifest = Path(directory) / "fixture.sha256"
            manifest.write_text(
                "# source-commit: " + "a" * 40 + "\n"
                + "0" * 64
                + "  src/main/resources/db/migration/V1__Only.sql\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(fixture.FixtureError, "versions 1 through 24"):
                fixture.load_manifest(manifest)


if __name__ == "__main__":
    unittest.main()
