import tempfile
import unittest
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
from verify_readme_i18n import verify


class ReadmeI18nContractTest(unittest.TestCase):
    def fixture(self, third_link: str = "docs/status.md"):
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        (root / "docs").mkdir()
        (root / "docs/status.md").write_text("status", encoding="utf-8")
        readmes = tuple(root / name for name in ("README.md", "README.en.md", "README.ja.md"))
        for index, readme in enumerate(readmes):
            link = third_link if index == 2 else "docs/status.md"
            readme.write_text(f"switch\n# title\n[status]({link})\n", encoding="utf-8")
        return temporary, root, readmes

    def test_aligned_links_and_headings_pass(self) -> None:
        temporary, root, readmes = self.fixture()
        with temporary:
            self.assertEqual(1, verify(readmes, root, (), "switch"))

    def test_locale_link_drift_fails(self) -> None:
        temporary, root, readmes = self.fixture("docs/other.md")
        with temporary:
            (root / "docs/other.md").write_text("other", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "link sets differ"):
                verify(readmes, root, (), "switch")

    def test_broken_link_fails_before_parity_can_mask_it(self) -> None:
        temporary, root, readmes = self.fixture("docs/missing.md")
        with temporary:
            with self.assertRaisesRegex(ValueError, "broken local link"):
                verify(readmes, root, (), "switch")


if __name__ == "__main__":
    unittest.main()
