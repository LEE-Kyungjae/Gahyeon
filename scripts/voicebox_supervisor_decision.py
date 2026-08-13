#!/usr/bin/env python3
"""Pure decision contract for the resumable Voicebox→QC supervisor."""

import argparse


def decide(completed: int, target: int, generator_active: bool) -> str:
    if target <= 0 or completed < 0 or completed > target:
        raise ValueError("invalid Voicebox progress")
    if generator_active:
        return "wait"
    if completed == target:
        return "finalize"
    return "generate"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--completed", type=int, required=True)
    parser.add_argument("--target", type=int, required=True)
    parser.add_argument("--generator-active", choices=("true", "false"), required=True)
    args = parser.parse_args()
    print(decide(args.completed, args.target, args.generator_active == "true"))


if __name__ == "__main__":
    main()
