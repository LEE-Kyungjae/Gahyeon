#!/usr/bin/env python3

from __future__ import annotations

import json
import subprocess
import tempfile
import threading
import unittest
import wave
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


SCRIPT = Path(__file__).with_name("stt_qc_voicebox_teacher.py")


class Handler(BaseHTTPRequestHandler):
    calls = 0

    def do_POST(self) -> None:  # noqa: N802
        Handler.calls += 1
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length)
        text = "정상 문장" if b"clean.wav" in body else "전혀 다른 발화"
        payload = json.dumps({"text": text}, ensure_ascii=False).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, *_args: object) -> None:
        pass


class SttQcTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        for name in ("clean.wav", "wrong.wav"):
            with wave.open(str(self.root / name), "wb") as wav:
                wav.setnchannels(1)
                wav.setsampwidth(2)
                wav.setframerate(16_000)
                wav.writeframes(b"\0\0" * 16_000)
        rows = []
        for index, name in enumerate(("clean.wav", "wrong.wav"), 1):
            path = self.root / name
            stat = path.stat()
            rows.append({
                "index": index,
                "text": "정상 문장",
                "audio": str(path),
                "size": stat.st_size,
                "mtimeNs": stat.st_mtime_ns,
                "duration": 1.0,
                "accepted": True,
            })
        (self.root / "acoustic_qc.jsonl").write_text(
            "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows), encoding="utf-8"
        )
        Handler.calls = 0
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.temp.cleanup()

    def run_qc(self, limit: int | None = None) -> dict:
        command = [
            "python3", str(SCRIPT), "--root", str(self.root),
            "--base-url", f"http://127.0.0.1:{self.server.server_port}",
            "--require-count", "2",
        ]
        if limit is not None:
            command.extend(("--limit", str(limit)))
        completed = subprocess.run(
            command,
            text=True,
            capture_output=True,
        )
        self.assertEqual(completed.returncode, 0, completed.stderr)
        return json.loads(completed.stdout.splitlines()[-1])

    def test_scores_transcripts_and_reuses_checkpoint(self) -> None:
        partial = self.run_qc(limit=1)
        self.assertEqual((partial["accepted"], partial["analyzed"], partial["ready"]), (1, 1, False))
        self.assertEqual(Handler.calls, 1)
        first = self.run_qc()
        self.assertEqual((first["accepted"], first["rejected"], first["analyzed"]), (1, 1, 1))
        self.assertTrue(first["ready"])
        self.assertEqual(Handler.calls, 2)
        self.assertEqual(
            (self.root / "metadata_selected.csv").read_text(encoding="utf-8"),
            "clean.wav|정상 문장\n",
        )
        second = self.run_qc()
        self.assertEqual((second["analyzed"], second["reused"]), (0, 2))
        self.assertEqual(Handler.calls, 2)


if __name__ == "__main__":
    unittest.main()
