#!/usr/bin/env python3
"""Dose's reminder sounds, made from scratch (no samples, nothing to license): soft, short tones.

    python3 tools/make_sounds.py

Writes data/sounds/{chime,bell,soft}.wav (the iPhone app uses these) and
android/app/src/main/res/raw/dose_{chime,bell,soft}.ogg (needs ffmpeg)."""
import math, os, struct, subprocess, wave

RATE = 44100
ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")


def note(freq, start, length, gain, partials=((1, 1.0), (2, 0.25), (3, 0.08)), decay=3.0):
    return (freq, start, length, gain, partials, decay)


SOUNDS = {
    # two notes going up, like a doorbell but gentle
    "chime": [note(784.0, 0.00, 1.2, 0.55), note(1046.5, 0.18, 1.4, 0.5)],
    # one bell: a fundamental with inharmonic partials, long tail
    "bell": [note(880.0, 0.0, 2.0, 0.6, ((1, 1.0), (2.76, 0.35), (5.4, 0.12), (8.9, 0.05)), decay=2.2)],
    # three soft rising notes, marimba-like (quick decay)
    "soft": [note(523.25, 0.00, 0.6, 0.5, ((1, 1.0), (4, 0.1)), 7.0),
             note(659.25, 0.14, 0.6, 0.5, ((1, 1.0), (4, 0.1)), 7.0),
             note(783.99, 0.28, 0.9, 0.55, ((1, 1.0), (4, 0.1)), 6.0)],
}


def render(notes):
    total = max(s + l for _f, s, l, _g, _p, _d in notes) + 0.05
    out = [0.0] * int(total * RATE)
    for freq, start, length, gain, partials, decay in notes:
        i0 = int(start * RATE)
        for i in range(int(length * RATE)):
            t = i / RATE
            attack = min(1.0, t / 0.006)                # no click at the start
            env = attack * math.exp(-decay * t)
            if t > length - 0.05:                       # fade the very end
                env *= max(0.0, (length - t) / 0.05)
            v = sum(a * math.sin(2 * math.pi * freq * m * t) for m, a in partials)
            out[i0 + i] += gain * env * v
    peak = max(abs(x) for x in out) or 1
    return [x / peak * 0.8 for x in out]


def main():
    raw = os.path.join(ROOT, "android", "app", "src", "main", "res", "raw")
    os.makedirs(raw, exist_ok=True)
    for name, notes in SOUNDS.items():
        wav = os.path.join(ROOT, "data", "sounds", name + ".wav")
        samples = render(notes)
        with wave.open(wav, "wb") as w:
            w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE)
            w.writeframes(b"".join(struct.pack("<h", int(x * 32767)) for x in samples))
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", wav, "-c:a", "libvorbis", "-q:a", "4",
                        os.path.join(raw, "dose_%s.ogg" % name)], check=True)
        print(name, "%.2f s" % (len(samples) / RATE))


if __name__ == "__main__":
    main()
