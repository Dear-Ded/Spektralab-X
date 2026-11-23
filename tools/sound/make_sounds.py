"""
Camera sounds for Spektralab-X, synthesised from scratch (no samples, so they are ours and GPL like the rest).

Each sound is a short sequence of mechanical events. An event is an impact (a burst of noise a
millisecond or two long) ringing a set of resonant modes: the steel and brass parts of a small camera
(curtain, pawl, latch) ring high and short, the body and the mirror box low and longer. Everything then
passes through a short "body" response so it sounds like one object in the hand, not a speaker click.

    python -I tools/sound/make_sounds.py            # writes app/src/main/res/raw/snd_*.ogg
    python -I tools/sound/make_sounds.py --wav DIR  # also keeps the 48 kHz WAVs, to listen to

Needs numpy and ffmpeg. The output is the same on every run (fixed seed). To use real recordings
instead, drop OGG files with the same names into res/raw; nothing else changes.
"""
import argparse, os, subprocess, sys, tempfile, wave
import numpy as np

SR = 48000
RNG = np.random.default_rng(20261123)
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
OUT = os.path.join(ROOT, 'app', 'src', 'main', 'res', 'raw')


def silence(sec):
    return np.zeros(int(sec * SR))


def modal_ir(freqs, decays, amps, length=0.12, detune=0.004):
    """Impulse response of a few damped modes. decays are T60-ish times in seconds."""
    t = np.arange(int(length * SR)) / SR
    ir = np.zeros_like(t)
    for f, d, a in zip(freqs, decays, amps):
        f = f * (1 + RNG.normal(0, detune))
        ph = RNG.uniform(0, 2 * np.pi)
        ir += a * np.exp(-6.9 * t / d) * np.sin(2 * np.pi * f * t + ph)
    return ir


def burst(ms=1.2, colour=0.0):
    """The impact: a short noise burst; colour > 0 darkens it (a softer material)."""
    n = max(8, int(ms / 1000 * SR))
    x = RNG.normal(0, 1, n) * np.exp(-np.linspace(0, 5, n))
    if colour > 0:  # a few passes of a one-pole low-pass
        for _ in range(int(colour)):
            x = np.convolve(x, [0.5, 0.5], mode='same')
    return x


def hit(buf, at, freqs, decays, amps, gain=1.0, ms=1.2, colour=0.0, length=0.12):
    ev = np.convolve(burst(ms, colour), modal_ir(freqs, decays, amps, length))
    i = int(at * SR)
    end = min(len(buf), i + len(ev))
    buf[i:end] += gain * ev[:end - i]


def noise_bed(buf, start, end, lo_hz, hi_hz, gain, shape='swell'):
    """Band-limited noise between two times (mechanism whir, friction), made in the frequency domain."""
    i0, i1 = int(start * SR), int(end * SR)
    n = i1 - i0
    spec = np.fft.rfft(RNG.normal(0, 1, n))
    fr = np.fft.rfftfreq(n, 1 / SR)
    spec *= ((fr > lo_hz) & (fr < hi_hz)) * 1.0
    x = np.fft.irfft(spec, n)
    x /= np.max(np.abs(x)) + 1e-9
    t = np.linspace(0, 1, n)
    env = {'swell': np.sin(np.pi * t) ** 1.5, 'fall': (1 - t) ** 2, 'flat': np.minimum(1, np.minimum(t, 1 - t) * 12)}[shape]
    buf[i0:i1] += gain * x * env


def bubble(buf, at, f0, gain, dur=0.05):
    """A liquid bubble: a sine whose pitch rises as it decays (Minnaert, roughly)."""
    t = np.arange(int(dur * SR)) / SR
    f = f0 * (1 + 2.2 * t / dur)
    x = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t / (dur * 0.28))
    i = int(at * SR)
    end = min(len(buf), i + len(x))
    buf[i:end] += gain * x[:end - i]


def body(x, mix=0.35):
    """The camera body around it: low modes of a small metal box."""
    ir = modal_ir([180, 410, 760, 1240], [0.05, 0.04, 0.03, 0.02], [1, 0.6, 0.35, 0.2], length=0.08)
    ir /= np.max(np.abs(ir))
    wet = np.convolve(x, ir)[:len(x)]
    wet /= np.max(np.abs(wet)) + 1e-9
    return x / (np.max(np.abs(x)) + 1e-9) + mix * wet


def finish(x, peak_db):
    # remove DC and the lowest rumble (one-pole high-pass at ~60 Hz)
    a = np.exp(-2 * np.pi * 60 / SR)
    y = np.zeros_like(x)
    prev_x = prev_y = 0.0
    for i, v in enumerate(x):
        prev_y = a * (prev_y + v - prev_x)
        prev_x = v
        y[i] = prev_y
    y /= np.max(np.abs(y)) + 1e-9
    y *= 10 ** (peak_db / 20)
    fade = min(len(y), int(0.008 * SR))
    y[-fade:] *= np.linspace(1, 0, fade)
    y[:16] *= np.linspace(0, 1, 16)
    return y


# --- the sounds -------------------------------------------------------------------------------

STEEL_HI = ([3150, 4720, 6380, 8900], [0.03, 0.022, 0.015, 0.01], [1, 0.7, 0.5, 0.3])
MIRROR = ([140, 290, 620, 1850, 2600], [0.06, 0.05, 0.04, 0.03, 0.02], [1, 0.8, 0.4, 0.35, 0.25])


def shutter():
    """A mechanical SLR: mirror up, first curtain, second curtain, mirror back down."""
    b = silence(0.24)
    hit(b, 0.000, *MIRROR, gain=0.55, ms=2.2, colour=2)
    noise_bed(b, 0.004, 0.11, 900, 3200, 0.05, 'swell')  # the mechanism running
    hit(b, 0.042, *STEEL_HI, gain=1.0, ms=0.8)
    hit(b, 0.068, [f * 0.93 for f in STEEL_HI[0]], STEEL_HI[1], STEEL_HI[2], gain=0.78, ms=0.8)
    hit(b, 0.104, *MIRROR, gain=0.62, ms=2.4, colour=2)
    hit(b, 0.106, [2100, 3300], [0.02, 0.015], [1, 0.6], gain=0.25)
    return finish(body(b, 0.4), -1.0)


def ratchet():
    """One stroke of the film-advance lever: the pawl over the teeth, then the stop, then the lever home."""
    b = silence(0.40)
    times = np.cumsum([0.0, 0.040, 0.036, 0.032, 0.029, 0.027, 0.025])
    for k, t in enumerate(times):
        hit(b, 0.02 + t, [5200, 6900, 9100], [0.012, 0.009, 0.006], [1, 0.6, 0.35], gain=0.35 + 0.08 * k, ms=0.6)
    noise_bed(b, 0.015, 0.24, 1500, 6000, 0.05, 'swell')  # friction under it
    hit(b, 0.255, [230, 520, 1450, 2200], [0.05, 0.04, 0.03, 0.02], [1, 0.6, 0.5, 0.3], gain=0.9, ms=1.8, colour=1)
    hit(b, 0.335, [2600, 4100], [0.02, 0.012], [1, 0.5], gain=0.3, ms=0.7)
    return finish(body(b, 0.3), -3.0)


def counter():
    """The frame counter stepping on: one tiny, bright click."""
    b = silence(0.06)
    hit(b, 0.002, [4300, 7200, 10100], [0.012, 0.008, 0.005], [1, 0.5, 0.3], gain=1, ms=0.4)
    return finish(body(b, 0.15), -10.0)


def agitate():
    """A developing tank turned over: liquid running round, the lid knocking."""
    b = silence(0.70)
    noise_bed(b, 0.0, 0.58, 250, 1800, 0.10, 'swell')
    for _ in range(36):
        t = RNG.uniform(0.05, 0.52)
        bubble(b, t, RNG.uniform(380, 1100), RNG.uniform(0.04, 0.12), RNG.uniform(0.025, 0.06))
    hit(b, 0.56, [210, 560, 1150], [0.05, 0.035, 0.02], [1, 0.5, 0.25], gain=0.45, ms=3.0, colour=3)
    return finish(b, -6.0)


def saved():
    """A finished slide pressed into its mount: two soft plastic clicks."""
    b = silence(0.16)
    hit(b, 0.002, [1250, 2900, 4400], [0.03, 0.02, 0.012], [1, 0.6, 0.35], gain=0.8, ms=1.0, colour=1)
    hit(b, 0.072, [1450, 3300, 5100], [0.025, 0.016, 0.01], [1, 0.55, 0.3], gain=0.55, ms=0.8, colour=1)
    return finish(body(b, 0.2), -5.0)


def rewind():
    """The rewind crank: a run of light clicks slowing down, then the leader slipping off the spool."""
    b = silence(0.80)
    t, gap = 0.01, 0.022
    k = 0
    while t < 0.62:
        hit(b, t, [3300, 5100], [0.01, 0.007], [1, 0.5], gain=0.45 * (1 - t / 0.9), ms=0.5)
        t += gap
        gap *= 1.045
        k += 1
    noise_bed(b, 0.0, 0.64, 300, 1200, 0.06, 'fall')
    hit(b, 0.67, [900, 2100, 3600], [0.03, 0.02, 0.012], [1, 0.6, 0.4], gain=0.55, ms=1.4)
    return finish(body(b, 0.25), -5.0)


def load():
    """The camera back closing over a new roll: a damped thunk and the latch."""
    b = silence(0.24)
    hit(b, 0.0, [130, 310, 720, 1500], [0.07, 0.05, 0.035, 0.02], [1, 0.7, 0.4, 0.2], gain=1.0, ms=3.5, colour=3)
    hit(b, 0.032, [2400, 3700, 5600], [0.02, 0.014, 0.008], [1, 0.6, 0.3], gain=0.45, ms=0.7)
    return finish(body(b, 0.35), -2.5)


SOUNDS = {'snd_shutter': shutter, 'snd_ratchet': ratchet, 'snd_counter': counter, 'snd_agitate': agitate,
          'snd_saved': saved, 'snd_rewind': rewind, 'snd_load': load}


def write_wav(path, x):
    with wave.open(path, 'wb') as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes((np.clip(x, -1, 1) * 32767).astype('<i2').tobytes())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--wav', help='also keep the WAVs in this folder')
    ap.add_argument('--out', default=OUT, help='where the OGG files go')
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    tmp = a.wav or tempfile.mkdtemp()
    os.makedirs(tmp, exist_ok=True)
    for name, make in SOUNDS.items():
        x = make()
        wav = os.path.join(tmp, name + '.wav')
        write_wav(wav, x)
        ogg = os.path.join(a.out, name + '.ogg')
        subprocess.run(['ffmpeg', '-y', '-loglevel', 'error', '-i', wav, '-c:a', 'libvorbis', '-q:a', '6', '-ac', '1', ogg], check=True)
        print(f'{name}: {len(x) / SR * 1000:.0f} ms, {os.path.getsize(ogg)} bytes')


if __name__ == '__main__':
    sys.exit(main())
