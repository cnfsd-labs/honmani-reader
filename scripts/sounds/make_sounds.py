#!/usr/bin/env python3
"""Makes Honmani's four soft interface sounds (app/src/main/res/raw/ui_*.wav).

Own work, no samples: sine tones with a fast attack and an exponential decay, 16-bit mono WAV.
Usage: python3 -I scripts/sounds/make_sounds.py [output directory]
"""
import math
import struct
import sys
import wave

RATE = 22050


def tone(freq, length, gain, partials=((1.0, 1.0),), decay=7.0, attack=0.006):
    """One note: [(frequency multiple, level)] partials, [attack] s in, e^-decay*t/length out."""
    samples = []
    for i in range(int(RATE * length)):
        t = i / RATE
        envelope = min(1.0, t / attack) * math.exp(-decay * t / length)
        value = sum(level * math.sin(2 * math.pi * freq * mult * t) for mult, level in partials)
        samples.append(gain * envelope * value)
    return samples


def mix(*notes):
    """Notes as (start seconds, samples), added into one clip."""
    end = max(int(start * RATE) + len(samples) for start, samples in notes)
    out = [0.0] * end
    for start, samples in notes:
        offset = int(start * RATE)
        for i, value in enumerate(samples):
            out[offset + i] += value
    return out


def save(path, samples):
    peak = max(1.0, max(abs(v) for v in samples))
    with wave.open(path, "wb") as f:
        f.setnchannels(1)
        f.setsampwidth(2)
        f.setframerate(RATE)
        f.writeframes(b"".join(struct.pack("<h", int(v / peak * 32000)) for v in samples))


def main(out):
    soft = ((1.0, 1.0), (2.0, 0.15))
    # Got it: two rising notes.
    save(f"{out}/ui_got_it.wav", mix((0.0, tone(659.25, 0.16, 0.35, soft)), (0.09, tone(880.0, 0.24, 0.35, soft))))
    # Forgot: one low, quieter, falling note; no jingle.
    save(f"{out}/ui_forgot.wav", mix((0.0, tone(392.0, 0.18, 0.22, soft)), (0.07, tone(329.63, 0.26, 0.2, soft))))
    # Session finished: a rising arpeggio.
    notes = [(i * 0.1, tone(f, 0.45 - i * 0.04, 0.3, soft)) for i, f in enumerate((523.25, 659.25, 783.99, 1046.5))]
    save(f"{out}/ui_session_done.wav", mix(*notes))
    # Milestone: a bell, a note with inharmonic overtones and a long decay.
    bell = ((1.0, 1.0), (2.76, 0.35), (5.4, 0.12))
    save(f"{out}/ui_milestone.wav", mix((0.0, tone(784.0, 0.9, 0.3, bell, decay=5.0))))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "app/src/main/res/raw")
