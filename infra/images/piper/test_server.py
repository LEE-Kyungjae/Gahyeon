from __future__ import annotations

import io
import unittest
import wave

import server


class PiperServerTest(unittest.TestCase):
    def test_transliterates_english_and_preserves_korean(self) -> None:
        korean, korean_mode = server.prepare_synthesis_text("안녕하세요")
        english, english_mode = server.prepare_synthesis_text("Hello from GitHub API")
        self.assertEqual(korean, "안녕하세요")
        self.assertEqual(korean_mode, "original")
        self.assertEqual(english_mode, "english-transliterated")
        self.assertNotRegex(english, r"[A-Za-z]")
        self.assertIn("깃허브", english)
        self.assertIn("에이피아이", english)

    def test_rejects_header_only_wav(self) -> None:
        output = io.BytesIO()
        with wave.open(output, "wb") as wav_file:
            wav_file.setnchannels(1)
            wav_file.setsampwidth(2)
            wav_file.setframerate(22050)
        with self.assertRaisesRegex(ValueError, "empty|invalid"):
            server.validate_wav(output.getvalue())

    def test_strips_markup_before_transliteration(self) -> None:
        prepared, mode = server.prepare_synthesis_text("<thought></thought>")
        self.assertEqual(prepared, "")
        self.assertEqual(mode, "markup-stripped")


if __name__ == "__main__":
    unittest.main()
