#!/usr/bin/env python3
import argparse
import json
import os
import time
from pathlib import Path

import soundfile as sf
import torch
from qwen_tts import Qwen3TTSModel

REF_TEXT = (
    "인도네시아 정부가 팜유와 석탄, 니켈 등 전략 원자재 수출을 국영기업을 통해 관리하는 "
    "수출 일원화 정책을 발표했다. 이후 현지 팜열매 가격이 급락하면서 시장에서는 팜유 수출이 "
    "제한되는 것 아니냐는 우려가 확대됐다."
)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--catalog", type=Path, required=True)
    ap.add_argument("--reference", type=Path, required=True)
    ap.add_argument("--output", type=Path, required=True)
    ap.add_argument("--limit", type=int, default=4000)
    ap.add_argument("--start", type=int, default=1)
    ap.add_argument("--dtype", choices=("float16", "float32"), default="float16")
    args = ap.parse_args()

    args.output.mkdir(parents=True, exist_ok=True)
    os.environ.setdefault("PYTORCH_CUDA_ALLOC_CONF", "expandable_segments:True")
    model_path = (
        "/home/ubuntu/voicebox-land/cache/huggingface/hub/"
        "models--Qwen--Qwen3-TTS-12Hz-0.6B-Base/snapshots/"
        "5d83992436eae1d760afd27aff78a71d676296fc"
    )
    model_dtype = torch.float32 if args.dtype == "float32" else torch.float16
    model = Qwen3TTSModel.from_pretrained(
        model_path,
        device_map="cuda",
        torch_dtype=model_dtype,
        attn_implementation="eager",
    )
    prompt = model.create_voice_clone_prompt(
        ref_audio=str(args.reference),
        ref_text=f"{REF_TEXT} {REF_TEXT}",
        x_vector_only_mode=False,
    )
    manifest = args.output / "manifest.jsonl"
    with args.catalog.open(encoding="utf-8") as src:
        rows = [json.loads(line) for line in src if line.strip()]

    for row in rows:
        idx = int(row["index"])
        if idx < args.start or idx > args.limit:
            continue
        out = args.output / f"teacher_{idx:04d}.wav"
        if out.exists() and out.stat().st_size > 4096:
            continue
        started = time.time()
        torch.manual_seed(20260809 + idx)
        wavs, sample_rate = model.generate_voice_clone(
            text=row["text"],
            voice_clone_prompt=prompt,
            language="Korean",
        )
        tmp = out.with_suffix(".tmp.wav")
        sf.write(tmp, wavs[0], sample_rate, subtype="PCM_16")
        tmp.replace(out)
        record = {
            "index": idx,
            "text": row["text"],
            "audio": out.name,
            "seconds": round(len(wavs[0]) / sample_rate, 3),
            "elapsed": round(time.time() - started, 3),
            "model": "Qwen/Qwen3-TTS-12Hz-0.6B-Base",
            "dtype": args.dtype,
        }
        with manifest.open("a", encoding="utf-8") as dst:
            dst.write(json.dumps(record, ensure_ascii=False) + "\n")
            dst.flush()
            os.fsync(dst.fileno())
        print(json.dumps(record, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
