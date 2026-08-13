#!/usr/bin/env python3
"""Deterministic duplicate and coverage gate for the frozen 5k teacher catalog."""

from __future__ import annotations

import argparse
import collections
import json
import os
import re
import tempfile
import unicodedata
from dataclasses import dataclass
from pathlib import Path


def normalize(text: str) -> str:
    return re.sub(r"[^0-9a-z가-힣]", "", unicodedata.normalize("NFKC", text).lower())


def grams(text: str, size: int = 4) -> set[str]:
    value = normalize(text)
    return {value[index:index + size] for index in range(max(1, len(value) - size + 1))}


def percentile(values: list[int], proportion: float) -> int:
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, max(0, int((len(ordered) - 1) * proportion)))]


def analyze(rows: list[dict]) -> dict:
    texts = [str(row["text"]).strip() for row in rows]
    normalized = [normalize(text) for text in texts]
    normalized_counts = collections.Counter(normalized)
    exact_duplicates = sum(count - 1 for count in normalized_counts.values() if count > 1)
    gram_sets: list[set[str]] = []
    postings: dict[str, list[int]] = collections.defaultdict(list)
    above_064 = above_080 = above_090 = 0
    maximum = 0.0
    maximum_pair: list[int] | None = None
    for index, text in enumerate(texts):
        candidate = grams(text)
        possible: set[int] = set()
        for gram in candidate:
            possible.update(postings[gram])
        for previous in possible:
            existing = gram_sets[previous]
            similarity = len(candidate & existing) / max(1, len(candidate | existing))
            if similarity > maximum:
                maximum, maximum_pair = similarity, [previous + 1, index + 1]
            above_064 += similarity > 0.64
            above_080 += similarity > 0.80
            above_090 += similarity > 0.90
        for gram in candidate:
            postings[gram].append(index)
        gram_sets.append(candidate)
    lengths = [len(text) for text in texts]
    tokens = [
        token for text in texts
        for token in re.findall(r"[0-9A-Za-z가-힣]+", text.lower())
    ]
    suffixes = collections.Counter(value[-12:] for value in normalized if value)
    return {
        "rows": len(rows),
        "contiguousIndices": [row.get("index") for row in rows] == list(range(1, len(rows) + 1)),
        "normalizedUnique": len(normalized_counts),
        "normalizedExactDuplicates": exact_duplicates,
        "nearDuplicatePairs": {
            "jaccardAbove064": above_064,
            "jaccardAbove080": above_080,
            "jaccardAbove090": above_090,
            "maximum": round(maximum, 6),
            "maximumPair": maximum_pair,
        },
        "length": {
            "minimum": min(lengths), "p10": percentile(lengths, 0.10),
            "median": percentile(lengths, 0.50), "p90": percentile(lengths, 0.90),
            "maximum": max(lengths),
        },
        "coverage": {
            "questions": sum("?" in text for text in texts),
            "exclamations": sum("!" in text for text in texts),
            "asciiDigits": sum(bool(re.search(r"\d", text)) for text in texts),
            "latin": sum(bool(re.search(r"[A-Za-z]", text)) for text in texts),
            "multiSentence": sum(len(re.findall(r"[.?!]", text)) >= 2 for text in texts),
            "commas": sum("," in text for text in texts),
            "uniqueWhitespaceTokens": len(set(tokens)),
        },
        "template": {
            "maxNormalizedSuffix12Count": max(suffixes.values()),
            "maxNormalizedSuffix12Rate": round(max(suffixes.values()) / len(rows), 6),
        },
    }


@dataclass(frozen=True)
class Policy:
    require_count: int = 5000
    max_pairs_above_080: int = 40
    max_jaccard: float = 0.90
    min_questions: int = 400
    min_exclamations: int = 100
    min_ascii_digits: int = 20
    min_latin: int = 250
    min_multi_sentence: int = 250
    min_unique_tokens: int = 15_000
    min_p10_length: int = 20
    max_p90_length: int = 90
    max_suffix_rate: float = 0.03


def violations(report: dict, policy: Policy) -> list[str]:
    coverage, near = report["coverage"], report["nearDuplicatePairs"]
    checks = {
        "row_count": report["rows"] == policy.require_count,
        "contiguous_indices": report["contiguousIndices"],
        "normalized_exact_duplicates": report["normalizedExactDuplicates"] == 0,
        "near_duplicate_pairs": near["jaccardAbove080"] <= policy.max_pairs_above_080,
        "maximum_jaccard": near["maximum"] <= policy.max_jaccard,
        "questions": coverage["questions"] >= policy.min_questions,
        "exclamations": coverage["exclamations"] >= policy.min_exclamations,
        "ascii_digits": coverage["asciiDigits"] >= policy.min_ascii_digits,
        "latin": coverage["latin"] >= policy.min_latin,
        "multi_sentence": coverage["multiSentence"] >= policy.min_multi_sentence,
        "unique_tokens": coverage["uniqueWhitespaceTokens"] >= policy.min_unique_tokens,
        "p10_length": report["length"]["p10"] >= policy.min_p10_length,
        "p90_length": report["length"]["p90"] <= policy.max_p90_length,
        "suffix_concentration": report["template"]["maxNormalizedSuffix12Rate"] <= policy.max_suffix_rate,
    }
    return [name for name, passed in checks.items() if not passed]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--catalog", type=Path, required=True)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    rows = [json.loads(line) for line in args.catalog.read_text(encoding="utf-8").splitlines() if line]
    report = analyze(rows)
    report["violations"] = violations(report, Policy())
    report["ready"] = not report["violations"]
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        descriptor, temporary_name = tempfile.mkstemp(dir=args.output.parent, text=True)
        with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
            json.dump(report, handle, ensure_ascii=False, indent=2)
            handle.flush(); os.fsync(handle.fileno())
        os.replace(temporary_name, args.output)
    print(json.dumps(report, ensure_ascii=False))
    if not report["ready"]:
        raise SystemExit(2)


if __name__ == "__main__":
    main()
