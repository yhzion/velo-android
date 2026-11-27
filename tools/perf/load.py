#!/usr/bin/env python3
"""Terminal output for the Velo Perfetto scenes. Every mode is seeded: runs print the same.

  load.py ko SECONDS LINES_PER_S COLS       random common Hangul (KS X 1001's 2350 syllables)
  load.py ascii SECONDS LINES_PER_S COLS    random printable ASCII (the atlas has it all at once)
  load.py prose SECONDS LINES_PER_S COLS [DISTINCT]
                                            Korean-like text: words of DISTINCT syllables (380),
                                            the common ones often, as in a document
  load.py lines SEED COUNT COLS [DISTINCT]  COUNT lines of that text at once; another SEED is
                                            another part of the same document
  load.py file PATH SECONDS LINES_PER_S     the lines of a text file, looped
  load.py fill COLS ROWS                    one screen of distinct Hangul, then stop
  load.py title SECONDS HZ                  OSC 0 title changes and nothing else
"""
import random
import sys
import time

random.seed(7)
HANGUL = []
for cp in range(0xAC00, 0xD7A4):
    ch = chr(cp)
    try:
        # KS X 1001 proper: two bytes. The codec writes the other syllables as 8-byte sequences.
        if len(ch.encode("euc-kr")) == 2:
            HANGUL.append(ch)
    except UnicodeEncodeError:
        pass
ASCII = [chr(c) for c in range(33, 127)]


def paced(lines, seconds, rate):
    """Write what `lines()` gives, `rate` lines a second, for `seconds`."""
    out = sys.stdout
    end = time.monotonic() + seconds
    nxt = time.monotonic()
    n = 0
    while time.monotonic() < end:
        out.write(lines() + "\n")
        out.flush()
        n += 1
        nxt += 1.0 / rate
        delay = nxt - time.monotonic()
        if delay > 0:
            time.sleep(delay)
    out.write(f"\n[done {n} lines]\n")


class Prose:
    """Lines of words made of `distinct` syllables, the n-th most common one 1/n as often."""

    def __init__(self, distinct, seed):
        pool = HANGUL[:]
        random.Random(11).shuffle(pool)
        self.vocab = pool[:distinct]
        self.weights = [1.0 / (i + 1) for i in range(distinct)]
        self.rng = random.Random(seed)

    def line(self, cols):
        words, used = [], 0
        while True:
            n = self.rng.choice((1, 2, 2, 3, 3, 4))
            word = "".join(self.rng.choices(self.vocab, self.weights, k=n))
            if self.rng.random() < 0.1:
                word += self.rng.choice(".,")
            width = 2 * n + (len(word) - n) + (1 if words else 0)
            if used + width > cols - 1:
                return " ".join(words)
            words.append(word)
            used += width


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else ""
    if mode in ("ko", "ascii"):
        seconds, rate, cols = float(sys.argv[2]), float(sys.argv[3]), int(sys.argv[4])
        alphabet, width = (HANGUL, max(1, cols // 2 - 1)) if mode == "ko" else (ASCII, max(1, cols - 1))
        paced(lambda: "".join(random.choice(alphabet) for _ in range(width)), seconds, rate)
    elif mode == "prose":
        seconds, rate, cols = float(sys.argv[2]), float(sys.argv[3]), int(sys.argv[4])
        prose = Prose(int(sys.argv[5]) if len(sys.argv) > 5 else 380, 7)
        paced(lambda: prose.line(cols), seconds, rate)
    elif mode == "lines":
        seed, count, cols = int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4])
        prose = Prose(int(sys.argv[5]) if len(sys.argv) > 5 else 380, seed)
        sys.stdout.write("\n".join(prose.line(cols) for _ in range(count)) + "\n")
    elif mode == "file":
        path, seconds, rate = sys.argv[2], float(sys.argv[3]), float(sys.argv[4])
        lines = [l.rstrip("\n") for l in open(path, encoding="utf-8") if l.strip()]
        it = iter(range(1 << 62))
        paced(lambda: lines[next(it) % len(lines)], seconds, rate)
    elif mode == "fill":
        cols, rows = int(sys.argv[2]), int(sys.argv[3])
        pool = HANGUL[:]
        random.shuffle(pool)
        per = max(1, cols // 2 - 1)
        sys.stdout.write("\n".join("".join(pool[(r * per + i) % len(pool)] for i in range(per)) for r in range(rows)))
    elif mode == "title":
        seconds, hz = float(sys.argv[2]), float(sys.argv[3])
        end = time.monotonic() + seconds
        n = 0
        while time.monotonic() < end:
            sys.stdout.write(f"\x1b]0;perf title {n}\x07")
            sys.stdout.flush()
            n += 1
            time.sleep(1.0 / hz)
        sys.stdout.write(f"\n[done {n} titles]\n")
    else:
        raise SystemExit(__doc__)
    sys.stdout.flush()


main()
