#!/usr/bin/env python3
"""Spike tool (never shipped): evaluates the text results that run-spike.sh keeps in spike/out/texts/.

  python3 -I scripts/spike/analyze.py vad    OUT_DIR MODEL     criteria (b) and (c) of research R-7
  python3 -I scripts/spike/analyze.py prompt OUT_DIR MODEL     case S-4: carried prompt, word error rate and repeats

Python 3 standard library only. OUT_DIR is spike/out. MODEL is the model file name without `.bin`.
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import wer as werlib  # noqa: E402  (the module sits next to this script)

SEGMENT = re.compile(r"^\[(\d+):(\d+):(\d+)\.(\d+) --> (\d+):(\d+):(\d+)\.(\d+)\]\s*(.*)$")


def seconds(h, m, s, ms):
    return int(h) * 3600 + int(m) * 60 + int(s) + int(ms) / 1000


def segments(path):
    """The `(start, end, text)` segments of a timestamped whisper-cli output."""
    result = []
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        match = SEGMENT.match(line.strip())
        if match:
            g = match.groups()
            result.append((seconds(*g[0:4]), seconds(*g[4:8]), g[8].strip()))
    return result


def text_file(out, case, model, audio, vad):
    return out / "texts" / f"{case}__{model}__{audio}__t2-faon-vad{vad}-pn-none.txt"


def vad_report(out, model):
    plain = segments(text_file(out, "S-2ts", model, "de-3min", "no"))
    with_vad = segments(text_file(out, "S-2ts", model, "de-3min", "yes"))
    # (b) How many segment starts of the run without VAD have a start within 0.5 s in the run with VAD, and back.
    def share(source, other):
        if not source:
            return 0.0
        return 100.0 * sum(1 for s in source if any(abs(s[0] - o[0]) <= 0.5 for o in other)) / len(source)

    print(f"segments: without VAD {len(plain)}, with VAD {len(with_vad)}")
    print(f"(b) starts of the run without VAD matched within 0.5 s by the VAD run: {share(plain, with_vad):.1f}%")
    print(f"    starts of the VAD run matched within 0.5 s by the run without VAD: {share(with_vad, plain):.1f}%")
    # (c) Segments that start inside the 30 s of silence at the start of silence-first.wav.
    for vad in ("no", "yes"):
        silent = [s for s in segments(text_file(out, "S-2ts", model, "silence-first", vad)) if s[0] < 30.0]
        print(f"(c) vad={vad}: {len(silent)} segment(s) start inside the first 30 s of silence: {[s[2] for s in silent][:4]}")
    for vad in ("no", "yes"):
        path = out / "texts" / f"S-2wer__{model}__de-3min__t2-faon-vad{vad}-pn-none.txt"
        print(f"    word count of de-3min, vad={vad}: {len(werlib.words(path.read_text(encoding='utf-8')))}")


def repeats(text):
    """Number of sentences that equal the sentence before them (a decoder loop)."""
    sentences = [s.strip().lower() for s in re.split(r"(?<=[.!?])\s+", text) if s.strip()]
    return sum(1 for a, b in zip(sentences, sentences[1:]) if a == b)


def prompt_report(out, model):
    reference = (out.parent / "audio" / "de-10min.ref.txt").read_text(encoding="utf-8")
    for mode in ("plain", "prompted"):
        parts = sorted((out / "texts").glob(f"S-4__{model}__excerpt-{mode}-excerpt-*__t2-faon-vadno-p*-none.txt"))
        # the very short tail excerpt (under 5 s) is dropped by the engine's own minimum; keep it if present
        text = " ".join(re.sub(r"(?m)^\[[^\]]*\]\s*", "", p.read_text(encoding="utf-8")).strip() for p in parts)
        print(f"{mode}: {len(parts)} excerpts, {len(werlib.words(text))} words, WER {werlib.wer(reference, text):.2f}%, repeated sentences {repeats(text)}")


if __name__ == "__main__":
    command, out_dir, model_name = sys.argv[1], Path(sys.argv[2]), sys.argv[3]
    {"vad": vad_report, "prompt": prompt_report}[command](out_dir, model_name)
