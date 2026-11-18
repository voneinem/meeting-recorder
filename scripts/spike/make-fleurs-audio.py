#!/usr/bin/env python3
"""Spike tool (never shipped): builds the German test audio of task T008 from the FLEURS dev split.

FLEURS (google/fleurs, CC-BY 4.0) is read speech with an exact transcript per sentence, so the accuracy case has a
true reference text. One recording per sentence id (the corpus repeats sentences with other speakers), joined with
0.6 s of silence like pauses between speakers. The audio is only a local test input and is never committed.

Usage: python3 -I scripts/spike/make-fleurs-audio.py SRC_DIR OUT_DIR
  SRC_DIR holds dev.tsv and wav/dev/*.wav (from data/de_de of the dataset); OUT_DIR is spike/audio.
Writes de-10min.wav (+ .ref.txt), de-3min.wav (the first sentences of the same sequence, + .ref.txt),
silence-first.wav (30 s of silence, then de-3min) and de-3min-quiet.wav (-20 dB).
"""
import csv
import subprocess
import sys
import wave
from pathlib import Path

RATE = 16000
PAUSE_S = 0.6
TARGET_LONG_S = 600
TARGET_SHORT_S = 180


def read_pcm(path):
    # The corpus files are 32-bit float WAVs: let ffmpeg convert them to 16 kHz mono 16 bit samples.
    done = subprocess.run(
        ["ffmpeg", "-loglevel", "error", "-i", str(path), "-f", "s16le", "-ar", str(RATE), "-ac", "1", "-"],
        check=True, capture_output=True,
    )
    return done.stdout


def write_wav(path, pcm):
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(pcm)


def sentences(src):
    seen = set()
    with open(src / "dev.tsv", encoding="utf-8", newline="") as f:
        for row in csv.reader(f, delimiter="\t"):
            sentence_id, name, _raw, normalized = row[0], row[1], row[2], row[3]
            wav = src / "wav" / "dev" / name
            if sentence_id in seen or not wav.is_file():
                continue
            seen.add(sentence_id)
            yield normalized.strip(), read_pcm(wav)


def build(src, target_s):
    silence = b"\x00\x00" * int(RATE * PAUSE_S)
    pcm, texts = b"", []
    for text, audio in sentences(src):
        pcm += audio + silence
        texts.append(text)
        if len(pcm) / 2 / RATE >= target_s:
            break
    return pcm, texts


def main():
    src, out = Path(sys.argv[1]), Path(sys.argv[2])
    out.mkdir(parents=True, exist_ok=True)
    long_pcm, long_texts = build(src, TARGET_LONG_S)
    short_pcm, short_texts = build(src, TARGET_SHORT_S)
    assert long_pcm.startswith(short_pcm), "the short excerpt must be the start of the long sequence"
    write_wav(out / "de-10min.wav", long_pcm)
    (out / "de-10min.ref.txt").write_text(" ".join(long_texts) + "\n", encoding="utf-8")
    write_wav(out / "de-3min.wav", short_pcm)
    (out / "de-3min.ref.txt").write_text(" ".join(short_texts) + "\n", encoding="utf-8")
    write_wav(out / "silence-first.wav", b"\x00\x00" * (RATE * 30) + short_pcm)
    subprocess.run(
        ["ffmpeg", "-y", "-loglevel", "error", "-i", str(out / "de-3min.wav"), "-af", "volume=-20dB",
         "-ar", str(RATE), "-ac", "1", "-c:a", "pcm_s16le", str(out / "de-3min-quiet.wav")],
        check=True,
    )
    print(f"de-10min.wav {len(long_pcm) / 2 / RATE:.1f} s, {len(long_texts)} sentences")
    print(f"de-3min.wav {len(short_pcm) / 2 / RATE:.1f} s, {len(short_texts)} sentences")


if __name__ == "__main__":
    main()
