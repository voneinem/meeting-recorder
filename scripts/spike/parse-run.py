#!/usr/bin/env python3
"""Parses one whisper-cli run (spike tool; Python 3 standard library only; run with `python3 -I`).

Usage: parse-run.py STDERR_FILE AUDIO_SECONDS
Prints `rtf=<x> language=<code> probability=<p> variant=<name>`. The real-time factor is
(sample + mel + encode + decode + batchd + prompt) milliseconds from the engine's timings block divided by the
audio length, so loading the model is excluded.
"""
import re
import sys


def parse(text: str, audio_seconds: float) -> dict:
    total_ms = 0.0
    for name in ("sample", "mel", "encode", "decode", "batchd", "prompt"):
        match = re.search(rf"{name} time\s*=\s*([0-9.]+) ms", text)
        if match:
            total_ms += float(match.group(1))
    language = re.search(r"auto-detected language:\s*(\w+)\s*\(p\s*=\s*([0-9.]+)\)", text)
    variant = re.search(r"(ggml-cpu-android_[a-z0-9]+(?:\.[0-9]+)?_[0-9]+)", text)
    return {
        "rtf": f"{total_ms / 1000.0 / audio_seconds:.3f}" if audio_seconds > 0 else "",
        "language": language.group(1) if language else "",
        "probability": language.group(2) if language else "",
        "variant": variant.group(1) if variant else "",
    }


def selftest() -> None:
    sample = (
        "whisper_print_timings:   sample time =   10.00 ms /   5 runs\n"
        "whisper_print_timings:      mel time =   20.00 ms\n"
        "whisper_print_timings:   encode time = 3000.00 ms /   2 runs\n"
        "whisper_print_timings:   decode time =  500.00 ms /  50 runs\n"
        "whisper_print_timings:   batchd time =   70.00 ms\n"
        "whisper_print_timings:   prompt time =  400.00 ms\n"
        "whisper_full_with_state: auto-detected language: de (p = 0.973)\n"
        "load_backend: loaded CPU backend from /data/local/tmp/spike/libggml-cpu-android_armv8.6_1.so\n"
    )
    result = parse(sample, 20.0)
    assert result["rtf"] == "0.200", result  # 4000 ms of work for 20 s of audio
    assert result["language"] == "de" and result["probability"] == "0.973", result
    assert result["variant"] == "ggml-cpu-android_armv8.6_1", result
    print("selftest ok")


if __name__ == "__main__":
    if sys.argv[1:] == ["--selftest"]:
        selftest()
        sys.exit(0)
    if len(sys.argv) != 3:
        print(__doc__, file=sys.stderr)
        sys.exit(2)
    with open(sys.argv[1], encoding="utf-8", errors="replace") as handle:
        r = parse(handle.read(), float(sys.argv[2]))
    print(f"rtf={r['rtf']} language={r['language']} probability={r['probability']} variant={r['variant']}")
