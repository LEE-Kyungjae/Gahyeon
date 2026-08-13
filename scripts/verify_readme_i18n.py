#!/usr/bin/env python3
"""Validate structural parity and local links across the three root READMEs."""

import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
READMES = (ROOT / "README.md", ROOT / "README.en.md", ROOT / "README.ja.md")
LANGUAGE_SWITCH = "[한국어](README.md) · [English](README.en.md) · [日本語](README.ja.md)"
REQUIRED_TOKENS = (
    "./gradlew test",
    "python3 scripts/verify_core_platform_boundaries.py",
    "./scripts/test_unreal_runtime_core.sh",
    "./scripts/verify_unreal_stage_scaffold.sh",
    "./scripts/verify_unreal_protocol_contract.sh",
    "./scripts/test_run_unreal_engine_gate.sh",
    "./scripts/test_smoke_headless_core.sh",
    "./scripts/smoke_headless_core.sh",
    "GAHYEON_HEADLESS_SMOKE_MODE=jar",
    "./scripts/run_unreal_engine_gate.sh",
    "docs/GAHYEON_G1_MODELING_HANDOFF.md",
    "docs/unreal/ACCEPTANCE_STATUS.md",
    "RT-13",
    "GAHYEON_UNREAL_VISEME_ALIGNER_*",
    "com.gahyeonbot",
)


def local_links(source: str) -> set[str]:
    links = set()
    for target in re.findall(r"(?<!!)\[[^]]+\]\(([^)]+)\)", source):
        target = target.strip().split("#", 1)[0]
        if target and "://" not in target and not target.startswith("mailto:"):
            links.add(target)
    return links


def verify(
        readmes: tuple[Path, ...] = READMES,
        root: Path = ROOT,
        required_tokens: tuple[str, ...] = REQUIRED_TOKENS,
        language_switch: str = LANGUAGE_SWITCH) -> int:
    sources = {path: path.read_text(encoding="utf-8") for path in readmes}
    heading_shapes = []
    link_sets = []
    for path, source in sources.items():
        if language_switch not in source:
            raise ValueError(f"{path.name}: language switch is missing or inconsistent")
        missing = [token for token in required_tokens if token not in source]
        if missing:
            raise ValueError(f"{path.name}: missing shared README tokens: {missing}")
        if source.count("```") % 2:
            raise ValueError(f"{path.name}: unbalanced fenced code block")
        headings = [len(match.group(1)) for match in re.finditer(r"^(#{1,6})\s+", source, re.MULTILINE)]
        heading_shapes.append(headings)
        links = local_links(source)
        link_sets.append(links)
        for target in links:
            if not (root / target).exists():
                raise ValueError(f"{path.name}: broken local link: {target}")
    if not all(shape == heading_shapes[0] for shape in heading_shapes[1:]):
        raise ValueError(f"README heading structures differ: {heading_shapes}")
    if not all(links == link_sets[0] for links in link_sets[1:]):
        raise ValueError("README local-link sets differ between locales")
    return len(heading_shapes[0])


def main() -> None:
    try:
        headings = verify()
    except ValueError as error:
        raise SystemExit(str(error)) from error
    print(f"README i18n contract passed: {headings} aligned headings, {len(READMES)} locales")


if __name__ == "__main__":
    main()
