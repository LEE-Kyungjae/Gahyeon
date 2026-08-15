#!/usr/bin/env python3
"""Verify that a Piper listening review keeps candidate identity out of the UI."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path


class BlindReviewError(ValueError):
    pass


def verify(review: Path) -> int:
    page_path = review / "index.html"
    key_path = review / "review-key.json"
    if not page_path.is_file() or not key_path.is_file():
        raise BlindReviewError("review must contain index.html and review-key.json")
    page = page_path.read_text(encoding="utf-8")
    key = json.loads(key_path.read_text(encoding="utf-8"))
    candidates = key.get("candidates")
    if not isinstance(candidates, list) or len(candidates) < 2:
        raise BlindReviewError("review requires at least two candidates")
    labels = [item.get("label") for item in candidates]
    if len(set(labels)) != len(labels) or any(not re.fullmatch(r"[A-Z]", str(label)) for label in labels):
        raise BlindReviewError("candidate labels must be unique single letters")
    digests = [item.get("modelSha256") for item in candidates]
    if len(set(digests)) != len(digests) or any(not re.fullmatch(r"[0-9a-f]{64}", str(value)) for value in digests):
        raise BlindReviewError("candidate model digests must be unique SHA-256 values")
    forbidden = [str(item.get("step")) for item in candidates if item.get("step") is not None]
    forbidden.extend(digests)
    leaked = [value for value in forbidden if value in page]
    if leaked:
        raise BlindReviewError("candidate identity leaked into listening page")
    for label in labels:
        media = review / "media" / label
        if not media.is_dir() or len(list(media.glob("case-*.wav"))) != 5:
            raise BlindReviewError(f"candidate {label} must contain exactly five WAV cases")
    return len(candidates)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--review", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps({"status": "valid", "candidates": verify(args.review.resolve())}))


if __name__ == "__main__":
    main()
