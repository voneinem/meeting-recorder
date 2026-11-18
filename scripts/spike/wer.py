#!/usr/bin/env python3
"""Word error rate of a hypothesis against a reference (spike tool; Python 3 standard library only).

Run it as `python3 -I scripts/spike/wer.py REFERENCE.txt HYPOTHESIS.txt`. Both texts are lower-cased and stripped of
punctuation, then compared by word-level edit distance. Prints `WER <percent>`; `--selftest` checks known pairs.
"""
import re
import sys


def words(text: str) -> list[str]:
    return re.sub(r"[^\w\s]", " ", text.lower(), flags=re.UNICODE).split()


def edit_distance(reference: list[str], hypothesis: list[str]) -> int:
    previous = list(range(len(hypothesis) + 1))
    for i, r in enumerate(reference, start=1):
        current = [i]
        for j, h in enumerate(hypothesis, start=1):
            current.append(min(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + (r != h)))
        previous = current
    return previous[-1]


def wer(reference: str, hypothesis: str) -> float:
    ref = words(reference)
    if not ref:
        raise ValueError("the reference has no words")
    return 100.0 * edit_distance(ref, words(hypothesis)) / len(ref)


def selftest() -> None:
    assert wer("a b c d", "a b c d") == 0.0
    assert wer("Ich gehe heute nach Hause.", "ich gehe heute nach hause") == 0.0  # case and punctuation are ignored
    assert wer("a b c d", "a x c d") == 25.0  # one substitution in four words
    assert wer("a b c d", "a c d") == 25.0  # one deletion
    assert wer("a b", "a b c d") == 100.0  # two insertions in two words
    print("selftest ok")


def main(argv: list[str]) -> int:
    if argv[1:] == ["--selftest"]:
        selftest()
        return 0
    if len(argv) != 3:
        print(__doc__, file=sys.stderr)
        return 2
    with open(argv[1], encoding="utf-8") as ref, open(argv[2], encoding="utf-8") as hyp:
        print(f"WER {wer(ref.read(), hyp.read()):.2f}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
