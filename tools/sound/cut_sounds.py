"""
Camera sounds for Spektralab-X, cut from real recordings of film cameras (all CC0 1.0, from
Freesound; authors in SOURCES and in NOTICE).

Each sound is one event taken from a recording, then made quiet and smooth the way a good camera
sounds next to the ear: rumble and hiss filtered off, the brittle 3-5 kHz edge of small microphones
taken down a little, the noise floor of the recording gated out of the tail, a 120 ms "hand" room
under it so it is one object and not a speaker click, levelled to its place in the set (the shutter
loudest, its click held to -1.5 dBFS; the frame counter a whisper), short fades, Vorbis.

    python tools/sound/cut_sounds.py SRC --fetch   # downloads the sources into SRC first (once)
    python tools/sound/cut_sounds.py SRC           # writes app/src/main/res/raw/snd_*.ogg
    python tools/sound/cut_sounds.py SRC --wav DIR # also keeps the 48 kHz WAVs, to listen to

Needs numpy and ffmpeg (with libvorbis). The sound is the same on every run (fixed seed); only the
OGG stream serial differs.
"""
import argparse, os, re, subprocess, sys, tempfile, urllib.request, wave
import numpy as np

SR = 48000
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
OUT = os.path.join(ROOT, 'app', 'src', 'main', 'res', 'raw')

# Freesound id: (author, title, preview url). All CC0 1.0 (creativecommons.org/publicdomain/zero/1.0).
SOURCES = {
    813225: ('alexradsby', 'Leica M10 Shutter Sound', 'https://cdn.freesound.org/previews/813/813225_{u}-hq.ogg'),
    734819: ('lisaisanut', 'Canon A-1 Shutter and Film Advance', 'https://cdn.freesound.org/previews/734/734819_{u}-hq.ogg'),
    585478: ('keverlyhills', 'Opening+Closing 35mm film camera back', 'https://cdn.freesound.org/previews/585/585478_{u}-hq.ogg'),
    667273: ('alegemaate', 'Bottle Slosh 2', 'https://cdn.freesound.org/previews/667/667273_{u}-hq.ogg'),
    439640: ('jzielke011', 'Film Canister Open and Close Sounds', 'https://cdn.freesound.org/previews/439/439640_{u}-hq.ogg'),
    588608: ('kulmajaba', 'Fujifilm Fuji GW670 II', 'https://cdn.freesound.org/previews/588/588608_{u}-hq.ogg'),
}


class Cut:
    """One sound: [a, b) seconds of a source and how to shape it."""

    def __init__(self, src, a, b, hp, lp, level, harsh=-2.0, body=1.5, room=0.10, fade_in=0.002, fade_out=0.08, squeeze=0.0, notch=(), floor=None):
        self.src, self.a, self.b = src, a, b
        self.hp, self.lp = hp, lp          # high-pass / low-pass corners, Hz
        self.level = level                 # loudest 50 ms, dBFS RMS
        self.harsh = harsh                 # dB at 4 kHz (the small-mic edge)
        self.body = body                   # dB low shelf at 220 Hz (the camera's body)
        self.room = room                   # wet share of the hand room
        self.fade_in, self.fade_out = fade_in, fade_out  # fade_out covers the room's 60 ms past the cut
        self.squeeze = squeeze             # dB under the peak where a gentle 3:1 compressor starts (0 = none)
        self.notch = notch                 # (Hz, dB, Q): a part that rings like a bell, taken down
        self.floor = floor                 # the recording's noise floor in dBFS, when its quiet parts are not silence


CUTS = {
    # Leica M10: the release's small pre-click, then the cloth curtains, then the recocking whirr fading.
    'shutter': Cut(813225, 0.030, 0.36, hp=70, lp=11000, level=-21.0, harsh=-3.0, body=2.0, room=0.08, fade_out=0.12, squeeze=12),
    # Canon A-1: one stroke of the advance lever, the ratchet, the stop, the lever back home.
    'ratchet': Cut(734819, 1.300, 1.495, hp=130, lp=7500, level=-23.5, harsh=-4.0, body=3.0, room=0.12, fade_out=0.07),
    # Canon A-1: the first tooth of that ratchet alone, dry (the frame counter stepping on).
    'counter': Cut(734819, 1.300, 1.352, hp=200, lp=8000, level=-32.0, harsh=-3.0, body=2.0, room=0.12, fade_out=0.07),
    # Liquid swung round in a bottle: the developing tank turned over. No metal ring (it pings).
    'agitate': Cut(667273, 0.020, 0.72, hp=120, lp=6500, level=-26.0, harsh=-2.0, body=1.0, room=0.10, fade_in=0.04, fade_out=0.26,
                   floor=-80),
    # A 35 mm canister's cap pressed home, then seated: the frame is put away.
    'saved': Cut(439640, 8.370, 8.62, hp=90, lp=7500, level=-22.5, harsh=-3.0, body=2.5, room=0.10, fade_out=0.10),
    # Pentax Spotmatic: the back swung shut and latched (the handling rumble under it filtered off).
    'load': Cut(585478, 8.335, 8.66, hp=180, lp=9000, level=-21.5, harsh=-2.0, body=0.0, room=0.08, fade_out=0.14),
    # Fuji GW670: the rewind crank's run, faded in and out.
    'rewind': Cut(588608, 163.96, 164.74, hp=150, lp=8000, level=-25.0, harsh=-3.0, body=2.5, room=0.12, fade_in=0.05, fade_out=0.26),
}


# --- io -------------------------------------------------------------------------------

def load(path):
    raw = subprocess.run(['ffmpeg', '-loglevel', 'error', '-i', path, '-ac', '1', '-ar', str(SR), '-f', 'f32le', '-'],
                         capture_output=True, check=True).stdout
    return np.frombuffer(raw, dtype=np.float32).astype(np.float64)


def write_wav(path, x):
    pcm = (np.clip(x, -1, 1) * 32767).astype('<i2')
    with wave.open(path, 'wb') as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR); w.writeframes(pcm.tobytes())


def fetch(dir):
    os.makedirs(dir, exist_ok=True)
    for sid, (_, title, _) in SOURCES.items():
        dst = os.path.join(dir, f's_{sid}.ogg')
        if os.path.exists(dst):
            continue
        # The preview path carries the uploader's numeric id; read it off the sound's page.
        page = urllib.request.urlopen(f'https://freesound.org/s/{sid}/').read().decode('utf8', 'ignore')
        m = re.search(rf'https://cdn\.freesound\.org/previews/\d+/{sid}_(\d+)-lq\.mp3', page)
        if not m:
            sys.exit(f'{sid} ({title}): preview not found')
        url = SOURCES[sid][2].format(u=m.group(1))
        print('fetch', sid, title)
        urllib.request.urlretrieve(url, dst)


# --- filters (RBJ biquads) -------------------------------------------------------------------

def biquad(x, b, a):
    b = np.asarray(b) / a[0]; a = np.asarray(a) / a[0]
    y = np.zeros_like(x); x1 = x2 = y1 = y2 = 0.0
    for i, v in enumerate(x):
        o = b[0] * v + b[1] * x1 + b[2] * x2 - a[1] * y1 - a[2] * y2
        x2, x1, y2, y1 = x1, v, y1, o
        y[i] = o
    return y


def _w(f): return 2 * np.pi * f / SR


def highpass(x, f, q=0.707):
    w = _w(f); al = np.sin(w) / (2 * q); c = np.cos(w)
    return biquad(x, [(1 + c) / 2, -(1 + c), (1 + c) / 2], [1 + al, -2 * c, 1 - al])


def lowpass(x, f, q=0.707):
    w = _w(f); al = np.sin(w) / (2 * q); c = np.cos(w)
    return biquad(x, [(1 - c) / 2, 1 - c, (1 - c) / 2], [1 + al, -2 * c, 1 - al])


def peak(x, f, db, q=0.9):
    A = 10 ** (db / 40); w = _w(f); al = np.sin(w) / (2 * q); c = np.cos(w)
    return biquad(x, [1 + al * A, -2 * c, 1 - al * A], [1 + al / A, -2 * c, 1 - al / A])


def lowshelf(x, f, db):
    A = 10 ** (db / 40); w = _w(f); c = np.cos(w); al = np.sin(w) / 2 * np.sqrt(2)
    sA = 2 * np.sqrt(A) * al
    return biquad(x, [A * ((A + 1) - (A - 1) * c + sA), 2 * A * ((A - 1) - (A + 1) * c), A * ((A + 1) - (A - 1) * c - sA)],
                  [(A + 1) + (A - 1) * c + sA, -2 * ((A - 1) + (A + 1) * c), (A + 1) + (A - 1) * c - sA])


# --- shaping -------------------------------------------------------------------------------

def envelope(x, ms):
    n = max(1, int(ms * SR / 1000))
    return np.sqrt(np.convolve(x * x, np.ones(n) / n, mode='same') + 1e-12)


def gate(x, floor_db):
    """A soft downward expander: the recording's hiss under [floor_db] + 6 dB fades out smoothly."""
    env = 20 * np.log10(envelope(x, 12))
    g = np.clip((env - floor_db - 4) / 14, 0, 1) ** 1.5
    # Smooth the gain (fast to open, slow to close) so the tail does not chatter.
    out = np.empty_like(g); v = 0.0
    up, down = 1 - np.exp(-1 / (0.002 * SR)), 1 - np.exp(-1 / (0.045 * SR))
    for i, t in enumerate(g):
        v += (t - v) * (up if t > v else down)
        out[i] = v
    return x * out


def room(n=0.12, seed=7):
    """The hand room: a short, dark, dense tail with a few early bounces off the palm and the body."""
    rng = np.random.default_rng(seed)
    t = np.arange(int(n * SR)) / SR
    ir = rng.normal(0, 1, len(t)) * np.exp(-t / 0.028)
    ir = lowpass(lowpass(ir, 4200), 4200)
    ir[:int(0.003 * SR)] = 0
    for d, g in ((0.0041, 0.5), (0.0067, 0.35), (0.0113, 0.22)):
        ir[int(d * SR)] += g * 6
    return ir / np.sqrt(np.sum(ir ** 2))


def compress(x, below):
    """A gentle 3:1 compressor from [below] dB under the peak: 0.5 ms attack, 60 ms release."""
    thr = np.max(np.abs(x)) * 10 ** (-below / 20)
    out = np.empty_like(x); env = 0.0
    att, rel = 1 - np.exp(-1 / (0.0005 * SR)), 1 - np.exp(-1 / (0.060 * SR))
    for i, v in enumerate(x):
        a = abs(v)
        env += (a - env) * (att if a > env else rel)
        g = 1.0 if env <= thr else (thr * (env / thr) ** (1 / 3)) / env
        out[i] = v * g
    return out


def fades(x, a, b):
    n = len(x); fi = max(1, int(a * SR)); fo = max(1, int(b * SR))
    w = np.ones(n)
    w[:fi] = np.sin(np.linspace(0, np.pi / 2, fi)) ** 2
    w[n - fo:] *= np.cos(np.linspace(0, np.pi / 2, fo)) ** 2
    return x * w


def loudest(x, ms=50):
    n = int(ms * SR / 1000)
    return max(np.sqrt(np.mean(x[i:i + n] ** 2)) for i in range(0, max(1, len(x) - n), n // 4))


def make(c, src_dir, ir):
    whole = load(os.path.join(src_dir, f's_{c.src}.ogg'))
    # The recording's own noise floor, from its quietest stretches.
    e = 20 * np.log10(envelope(whole, 20))
    floor = float(np.percentile(e, 8)) if c.floor is None else c.floor
    pad = int(0.01 * SR)
    x = whole[max(0, int(c.a * SR) - pad):int(c.b * SR) + pad].copy()
    x -= np.mean(x)
    x = highpass(highpass(x, c.hp), c.hp)
    x = lowpass(x, c.lp)
    x = peak(x, 4000, c.harsh, 0.8)
    x = lowshelf(x, 220, c.body)
    for f, db, q in c.notch:
        x = peak(x, f, db, q)
    x = gate(x, floor)
    if c.squeeze:
        x = compress(x, c.squeeze)
    x = x[pad:len(x) - pad]
    # The room, under the dry sound; it rings on past the cut a little, then fades.
    wet = np.convolve(x, ir)[:len(x) + int(0.06 * SR)]
    dry = np.concatenate([x, np.zeros(len(wet) - len(x))])
    y = dry * (1 - c.room) + wet * c.room * 1.6
    y = fades(y, c.fade_in, c.fade_out)
    y *= 10 ** (c.level / 20) / loudest(y)
    pk = np.max(np.abs(y))
    if pk > 10 ** (-1.5 / 20):
        y *= 10 ** (-1.5 / 20) / pk
    return y, floor


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('src', help='directory with s_<freesound id>.ogg')
    ap.add_argument('--fetch', action='store_true', help='download the sources into src first')
    ap.add_argument('--wav', help='also write WAVs here')
    ap.add_argument('--out', default=OUT)
    args = ap.parse_args()
    if args.fetch:
        fetch(args.src)
    ir = room()
    os.makedirs(args.out, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        for name, c in CUTS.items():
            y, floor = make(c, args.src, ir)
            wav = os.path.join(args.wav or tmp, f'snd_{name}.wav')
            if args.wav:
                os.makedirs(args.wav, exist_ok=True)
            write_wav(wav, y)
            out = os.path.join(args.out, f'snd_{name}.ogg')
            subprocess.run(['ffmpeg', '-loglevel', 'error', '-y', '-i', wav, '-c:a', 'libvorbis', '-q:a', '6',
                            '-ar', str(SR), '-ac', '1', '-map_metadata', '-1', out], check=True)
            print(f'{name:8s} {len(y) / SR:5.2f} s  floor {floor:6.1f} dB  peak {20 * np.log10(np.max(np.abs(y))):5.1f}  '
                  f'loudest {20 * np.log10(loudest(y)):5.1f}  {SOURCES[c.src][1]}')


if __name__ == '__main__':
    main()
