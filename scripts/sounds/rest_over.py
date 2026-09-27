#!/usr/bin/env python3
"""Render the "rest is over" chime for both apps.

    pip install numpy scipy soundfile
    python3 scripts/sounds/rest_over.py            # writes both files below
    python3 scripts/sounds/rest_over.py --preview  # also writes a spectrogram next to this script

Writes
    NoTomorrow/Resources/Sounds/rest_over.caf     iOS: 16-bit PCM in CAF (notification sounds must be PCM)
    android/app/src/main/res/raw/rest_over.ogg    Android: Ogg Vorbis

The sound, about 1.9 s:

    0.00  an airy swell (band-passed noise sweeping up) that pulls you in;
    0.14  the hit: a soft sub drop for weight, a mallet tick, and a rising
          A-E-C#-E arpeggio (A major add9 feel) voiced as FM tines -- a sine
          fundamental plus a modulator whose brightness decays, the Rhodes /
          celesta family -- each note doubled a few cents apart and spread
          across the stereo field;
    then  a synthetic plate reverb (decorrelated stereo, damped highs) lets it
          bloom and fade.

Everything is deterministic (fixed seed), so the files only change when this
script does. Never hand-edit the outputs.
"""

from __future__ import annotations

import argparse
import struct
from pathlib import Path

import numpy as np
from scipy import signal

SR = 48_000
LENGTH = 1.95
HIT = 0.14
ROOT = Path(__file__).resolve().parents[2]
RNG = np.random.default_rng(20260925)


def t_axis(seconds: float) -> np.ndarray:
    return np.arange(int(seconds * SR)) / SR


def place(buffer: np.ndarray, sound: np.ndarray, at: float, gain: float = 1.0) -> None:
    """Mixes `sound` (n,) or (n, 2) into `buffer` (N, 2) starting at `at` seconds."""
    start = int(at * SR)
    end = min(len(buffer), start + len(sound))
    if end <= start:
        return
    chunk = sound[: end - start]
    if chunk.ndim == 1:
        chunk = np.stack([chunk, chunk], axis=1)
    buffer[start:end] += gain * chunk


def pan(mono: np.ndarray, position: float) -> np.ndarray:
    """Equal-power pan, position -1 (left) … 1 (right)."""
    angle = (position + 1) * np.pi / 4
    return np.stack([mono * np.cos(angle), mono * np.sin(angle)], axis=1)


def envelope(t: np.ndarray, attack: float, decay: float) -> np.ndarray:
    """Smooth (raised-cosine) attack into an exponential decay; `decay` is the time to -60 dB."""
    rise = np.clip(t / attack, 0, 1)
    rise = 0.5 - 0.5 * np.cos(np.pi * rise)
    return rise * np.exp(-6.9078 * t / decay)


# MARK: - Voices

def tine(freq: float, seconds: float, decay: float, brightness: float = 1.0) -> np.ndarray:
    """One FM tine note. Two modulators: a 1:1 one for body that mellows over ~150 ms, and a
    high, inharmonic one (ratio 7.1) for the metallic strike that is gone in ~40 ms."""
    t = t_axis(seconds)
    body_index = brightness * (1.2 * np.exp(-t / 0.15) + 0.15)
    strike_index = brightness * 1.5 * np.exp(-t / 0.03)
    modulator = body_index * np.sin(2 * np.pi * freq * t) + strike_index * np.sin(2 * np.pi * freq * 7.1 * t)
    carrier = np.sin(2 * np.pi * freq * t + modulator)
    # A pure octave partial that outlives the rest: the "glass" in the tail.
    octave = 0.18 * np.sin(2 * np.pi * freq * 2.0 * t) * np.exp(-t / (decay * 0.22))
    return (carrier + octave) * envelope(t, attack=0.0025, decay=decay)


def chorused_note(freq: float, position: float, decay: float, brightness: float) -> np.ndarray:
    """The tine doubled +-3.5 cents, the two copies panned apart around `position`."""
    cents = 3.5
    up = tine(freq * 2 ** (cents / 1200), LENGTH, decay, brightness)
    down = tine(freq * 2 ** (-cents / 1200), LENGTH, decay, brightness)
    return 0.5 * (pan(up, np.clip(position + 0.25, -1, 1)) + pan(down, np.clip(position - 0.25, -1, 1)))


def sub_drop() -> np.ndarray:
    """A sine gliding 120 → 52 Hz: felt more than heard, it gives the hit its weight."""
    t = t_axis(0.5)
    freq = 52 + 68 * np.exp(-t / 0.045)
    phase = 2 * np.pi * np.cumsum(freq) / SR
    return np.sin(phase) * envelope(t, attack=0.004, decay=0.42)


def mallet_tick() -> np.ndarray:
    """A few milliseconds of high-passed noise: the felt of the mallet."""
    t = t_axis(0.03)
    noise = RNG.standard_normal(len(t))
    sos = signal.butter(4, [2500, 9000], btype="bandpass", fs=SR, output="sos")
    return signal.sosfilt(sos, noise) * np.exp(-t / 0.004)


def swell() -> np.ndarray:
    """Band-passed noise whose centre sweeps 700 Hz → 5 kHz as it rises: the in-breath before the
    hit. A per-sample state-variable filter, so the sweep is continuous (it is only ~8 k samples)."""
    seconds = HIT + 0.02
    n = int(seconds * SR)
    t = np.arange(n) / SR
    out = np.zeros((n, 2))
    for ch in range(2):
        noise = RNG.standard_normal(n)
        low = band = 0.0
        q = 1.4
        for i in range(n):
            centre = 700 * (5000 / 700) ** (i / n)
            f = 2 * np.sin(np.pi * centre / SR)
            high = noise[i] - low - band / q
            band += f * high
            low += f * band
            out[i, ch] = band
    amp = (t / seconds) ** 3.2
    amp *= np.clip((seconds - t) / 0.012, 0, 1)   # cut just as the hit lands
    return out * amp[:, None]


# MARK: - Space

def plate_ir(seconds: float = 1.5, rt60: float = 1.25) -> np.ndarray:
    """A stereo plate-ish impulse response: sparse early reflections, then decorrelated noise
    that decays exponentially and loses its highs over time."""
    n = int(seconds * SR)
    t = np.arange(n) / SR
    ir = np.zeros((n, 2))
    predelay = int(0.012 * SR)
    for ch in range(2):
        noise = RNG.standard_normal(n)
        bright = signal.sosfilt(signal.butter(2, 7000, fs=SR, output="sos"), noise)
        dark = signal.sosfilt(signal.butter(2, 1800, fs=SR, output="sos"), noise)
        fade = np.clip(t / 0.6, 0, 1)                      # highs die first
        tail = ((1 - fade) * bright + fade * dark) * np.exp(-6.9078 * t / rt60)
        tail *= np.clip(t / 0.02, 0, 1)                    # soft onset of the diffuse field
        ir[predelay:, ch] = tail[: n - predelay]
        for delay_ms, gain in ((7.3, 0.5), (11.9, -0.35), (17.1, 0.28), (23.6, -0.2)):
            k = predelay + int((delay_ms + ch * 1.7) * SR / 1000)
            ir[k, ch] += gain * 8
    return ir / np.sqrt(np.sum(ir ** 2, axis=0, keepdims=True))


def reverb(dry: np.ndarray, mix: float) -> np.ndarray:
    ir = plate_ir()
    # Feed the reverb without the sub: a boomy tail is what makes chimes cheap.
    sos = signal.butter(2, 220, btype="highpass", fs=SR, output="sos")
    send = signal.sosfilt(sos, dry, axis=0)
    wet = np.stack([signal.fftconvolve(send[:, ch], ir[:, ch])[: len(dry)] for ch in range(2)], axis=1)
    return dry + mix * wet


# MARK: - Mix

def render() -> np.ndarray:
    n = int(LENGTH * SR)
    mix = np.zeros((n, 2))

    place(mix, swell(), 0.0, gain=0.07)
    place(mix, sub_drop(), HIT, gain=0.42)
    place(mix, pan(mallet_tick(), -0.1), HIT, gain=0.10)

    # A4 · E5 · C#6 · E6, rising left → right, 62 ms apart; the top note is a quiet sparkle.
    notes = [
        (440.00, 0.000, -0.45, 1.55, 1.00, 0.34),
        (659.26, 0.062, -0.10, 1.35, 0.95, 0.30),
        (1108.73, 0.124, 0.25, 1.15, 0.85, 0.26),
        (1318.51, 0.186, 0.55, 0.95, 0.70, 0.15),
    ]
    for freq, offset, position, decay, brightness, gain in notes:
        place(mix, chorused_note(freq, position, decay, brightness), HIT + offset, gain=gain)

    mix = reverb(mix, mix=0.32)

    # Tone: take the harsh edge off the top, a touch of air shelf back via a gentle 11 kHz low-pass.
    mix = signal.sosfilt(signal.butter(2, 11_000, fs=SR, output="sos"), mix, axis=0)
    # Glue: soft saturation, then normalise to -1 dBFS.
    mix = np.tanh(1.4 * mix) / np.tanh(1.4)
    mix *= 10 ** (-1 / 20) / np.max(np.abs(mix))
    # Fade the last 250 ms so the tail never clicks off.
    fade = int(0.25 * SR)
    mix[-fade:] *= np.linspace(1, 0, fade)[:, None] ** 2
    return mix.astype(np.float32)


# MARK: - Files

def write_caf(path: Path, audio: np.ndarray) -> None:
    """Linear PCM, 16-bit big-endian, interleaved, in a Core Audio Format container."""
    pcm = np.clip(np.round(audio * 32767), -32768, 32767).astype(">i2")
    channels = audio.shape[1]
    # mSampleRate, 'lpcm', format flags (bit 0 float, bit 1 little-endian: 0 = big-endian signed
    # integer), bytes per packet, frames per packet, channels, bits per channel.
    desc = struct.pack(">dIIIIII", float(SR), 0x6C70636D, 0, 2 * channels, 1, channels, 16)
    data = pcm.tobytes()
    with path.open("wb") as f:
        f.write(b"caff" + struct.pack(">HH", 1, 0))
        f.write(b"desc" + struct.pack(">q", len(desc)) + desc)
        f.write(b"data" + struct.pack(">q", len(data) + 4) + struct.pack(">I", 0) + data)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--preview", action="store_true", help="also write a spectrogram PNG")
    parser.add_argument("--wav", type=Path, help="also write a WAV here (for listening)")
    args = parser.parse_args()

    import soundfile

    audio = render()
    ios = ROOT / "NoTomorrow/Resources/Sounds/rest_over.caf"
    android = ROOT / "android/app/src/main/res/raw/rest_over.ogg"
    ios.parent.mkdir(parents=True, exist_ok=True)
    android.parent.mkdir(parents=True, exist_ok=True)
    write_caf(ios, audio)
    soundfile.write(android, audio, SR, format="OGG", subtype="VORBIS")
    if args.wav:
        soundfile.write(args.wav, audio, SR, subtype="PCM_16")
    peak = 20 * np.log10(np.max(np.abs(audio)))
    rms = 20 * np.log10(np.sqrt(np.mean(audio ** 2)))
    print(f"{ios.relative_to(ROOT)}  {android.relative_to(ROOT)}  {LENGTH:.2f} s  peak {peak:.1f} dBFS  rms {rms:.1f} dBFS")

    if args.preview:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
        fig, axes = plt.subplots(2, 1, figsize=(10, 6))
        axes[0].plot(np.arange(len(audio)) / SR, audio[:, 0], lw=0.4)
        axes[0].plot(np.arange(len(audio)) / SR, audio[:, 1], lw=0.4, alpha=0.6)
        axes[1].specgram(audio[:, 0], Fs=SR, NFFT=2048, noverlap=1536, cmap="magma", vmin=-120)
        axes[1].set_ylim(0, 12_000)
        fig.savefig(Path(__file__).with_name("rest_over_preview.png"), dpi=110)


if __name__ == "__main__":
    main()
