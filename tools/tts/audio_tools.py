"""Audio processing for TTS production: loudness, trimming, encoding, inspection.

Pure Python (standard library only) so the masters can be processed on any machine;
encoding shells out to FFmpeg, or to macOS's afconvert where FFmpeg is missing.
Loudness follows ITU-R BS.1770-4 (K-weighting, 400 ms gated blocks).
"""
from __future__ import annotations

import array
import math
import shutil
import struct
import subprocess
import wave
from dataclasses import dataclass
from pathlib import Path

# Bump when processing changes in a way that should re-encode every clip.
PROCESSING_VERSION = 1


@dataclass(frozen=True)
class Normalization:
    target_lufs: float = -18.0
    ceiling_dbtp: float = -1.0
    # Silence is trimmed only beyond these pads, so soft onsets and final consonants stay.
    lead_pad_ms: int = 100
    tail_pad_ms: int = 150
    silence_below_peak_db: float = 40.0
    silence_floor_dbfs: float = -60.0

    def as_dict(self) -> dict:
        return {
            "targetLufs": self.target_lufs,
            "ceilingDbtp": self.ceiling_dbtp,
            "leadPadMs": self.lead_pad_ms,
            "tailPadMs": self.tail_pad_ms,
            "silenceBelowPeakDb": self.silence_below_peak_db,
            "silenceFloorDbfs": self.silence_floor_dbfs,
            "version": PROCESSING_VERSION,
        }


@dataclass(frozen=True)
class ProcessedAudio:
    samples: list
    rate: int
    loudness_in: float
    loudness_out: float
    gain_db: float
    true_peak_dbtp: float
    trimmed_ms: int
    short_clip: bool
    peak_limited: bool

    @property
    def duration_ms(self) -> int:
        return round(len(self.samples) * 1000 / self.rate)


class AudioError(Exception):
    pass


# --- WAV ---------------------------------------------------------------------------

def read_wav(path: Path) -> tuple[list, int]:
    """Mono 16-bit PCM WAV as floats in [-1, 1], plus the sample rate."""
    with wave.open(str(path), "rb") as w:
        if w.getnchannels() != 1 or w.getsampwidth() != 2:
            raise AudioError(f"{path}: expected mono 16-bit PCM, got {w.getnchannels()} ch / {8 * w.getsampwidth()} bit")
        rate = w.getframerate()
        pcm = array.array("h")
        pcm.frombytes(w.readframes(w.getnframes()))
    if pcm.itemsize != 2:
        raise AudioError("unsupported platform: array('h') is not 16-bit")
    if struct.pack("=h", 1) != struct.pack("<h", 1):
        pcm.byteswap()
    return [s / 32768.0 for s in pcm], rate


def write_wav(path: Path, samples: list, rate: int) -> None:
    pcm = array.array("h", (max(-32768, min(32767, round(s * 32767))) for s in samples))
    if struct.pack("=h", 1) != struct.pack("<h", 1):
        pcm.byteswap()
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(pcm.tobytes())


# --- Loudness (ITU-R BS.1770-4) ----------------------------------------------------

def _k_weighting(rate: int) -> list[tuple[float, float, float, float, float]]:
    """The two K-weighting biquads (b0, b1, b2, a1, a2) for any sample rate (libebur128 derivation)."""
    f0, gain_db, q = 1681.974450955533, 3.999843853973347, 0.7071752369554196
    k = math.tan(math.pi * f0 / rate)
    vh = 10 ** (gain_db / 20)
    vb = vh ** 0.4996667741545416
    a0 = 1 + k / q + k * k
    shelf = ((vh + vb * k / q + k * k) / a0, 2 * (k * k - vh) / a0, (vh - vb * k / q + k * k) / a0,
             2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0)
    f0, q = 38.13547087602444, 0.5003270373238773
    k = math.tan(math.pi * f0 / rate)
    a0 = 1 + k / q + k * k
    highpass = (1.0, -2.0, 1.0, 2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0)
    return [shelf, highpass]


def _biquad(x: list, b0: float, b1: float, b2: float, a1: float, a2: float) -> list:
    y = [0.0] * len(x)
    x1 = x2 = y1 = y2 = 0.0
    for i, v in enumerate(x):
        out = b0 * v + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2, x1, y2, y1 = x1, v, y1, out
        y[i] = out
    return y


def _lufs(mean_square: float) -> float:
    return -0.691 + 10 * math.log10(mean_square) if mean_square > 0 else float("-inf")


def integrated_loudness(samples: list, rate: int) -> tuple[float, bool]:
    """Integrated loudness in LUFS, and whether the clip was too short for a gated block."""
    weighted = samples
    for coeffs in _k_weighting(rate):
        weighted = _biquad(weighted, *coeffs)
    squares = [v * v for v in weighted]
    block, step = int(0.4 * rate), int(0.1 * rate)
    if len(squares) < block:
        # Under 400 ms there is no gated block: fall back to the whole clip (check by ear).
        return _lufs(sum(squares) / max(1, len(squares))), True
    prefix = [0.0]
    for s in squares:
        prefix.append(prefix[-1] + s)
    blocks = [(prefix[i + block] - prefix[i]) / block for i in range(0, len(squares) - block + 1, step)]
    gated = [z for z in blocks if _lufs(z) > -70.0]
    if not gated:
        return float("-inf"), False
    relative = _lufs(sum(gated) / len(gated)) - 10.0
    kept = [z for z in gated if _lufs(z) > relative]
    return _lufs(sum(kept) / len(kept)), False


def true_peak(samples: list, oversample: int = 4, taps: int = 16) -> float:
    """Peak magnitude including inter-sample peaks, by windowed-sinc interpolation.

    Overs only form beside local maxima near the clip's peak, so only those spans
    are interpolated; that keeps this fast in pure Python.
    """
    peak = max((abs(v) for v in samples), default=0.0)
    if peak == 0.0:
        return 0.0
    # Lanczos weights for each fractional position, over samples k = -taps+1 .. taps.
    kernels = [[_sinc(f - k) * _sinc((f - k) / taps) for k in range(-taps + 1, taps + 1)]
               for f in (step / oversample for step in range(1, oversample))]
    n, best = len(samples), peak
    for i in range(n):
        a = abs(samples[i])
        if a < 0.7 * peak or (i and abs(samples[i - 1]) > a) or (i + 1 < n and abs(samples[i + 1]) > a):
            continue
        for left in (i - 1, i):  # the spans on both sides of the local maximum
            lo = left - taps + 1
            if lo < 0 or left + taps >= n:
                continue
            window = samples[lo:left + taps + 1]
            for weights in kernels:
                best = max(best, abs(sum(w * v for w, v in zip(weights, window))))
    return best


def _sinc(x: float) -> float:
    return 1.0 if x == 0 else math.sin(math.pi * x) / (math.pi * x)


def _db(value: float) -> float:
    return 20 * math.log10(value) if value > 0 else float("-inf")


# --- Trim and normalize ------------------------------------------------------------

def trim_silence(samples: list, rate: int, norm: Normalization) -> tuple[list, int]:
    """Cut leading/trailing silence beyond the pads; returns (samples, trimmed ms)."""
    frame = max(1, rate // 100)  # 10 ms
    rms = []
    for i in range(0, len(samples), frame):
        chunk = samples[i:i + frame]
        rms.append(math.sqrt(sum(v * v for v in chunk) / len(chunk)))
    peak_db = _db(max(rms, default=0.0))
    threshold = 10 ** (max(norm.silence_floor_dbfs, peak_db - norm.silence_below_peak_db) / 20)
    voiced = [i for i, r in enumerate(rms) if r > threshold]
    if not voiced:
        return samples, 0
    start = max(0, voiced[0] * frame - norm.lead_pad_ms * rate // 1000)
    end = min(len(samples), (voiced[-1] + 1) * frame + norm.tail_pad_ms * rate // 1000)
    return samples[start:end], round((len(samples) - (end - start)) * 1000 / rate)


def normalize(samples: list, rate: int, norm: Normalization) -> ProcessedAudio:
    trimmed, trimmed_ms = trim_silence(samples, rate, norm)
    loudness_in, short = integrated_loudness(trimmed, rate)
    if loudness_in == float("-inf"):
        raise AudioError("clip is silent")
    gain_db = norm.target_lufs - loudness_in
    peak_db = _db(true_peak(trimmed)) + gain_db
    limited = peak_db > norm.ceiling_dbtp
    if limited:
        # No compression: lower the gain so the true peak meets the ceiling.
        gain_db -= peak_db - norm.ceiling_dbtp
    factor = 10 ** (gain_db / 20)
    out = [v * factor for v in trimmed]
    loudness_out, _ = integrated_loudness(out, rate)
    return ProcessedAudio(out, rate, round(loudness_in, 2), round(loudness_out, 2), round(gain_db, 2),
                          round(_db(true_peak(out)), 2), trimmed_ms, short, limited)


# --- Encoding -----------------------------------------------------------------------

def find_encoder(preferred: str = "auto") -> str:
    """'ffmpeg' or 'afconvert' (macOS); FFmpeg wins when both exist."""
    if preferred in ("ffmpeg", "afconvert"):
        if not shutil.which(preferred):
            raise AudioError(f"{preferred} is not installed")
        return preferred
    for name in ("ffmpeg", "afconvert"):
        if shutil.which(name):
            return name
    raise AudioError("no AAC encoder found: install FFmpeg (or run on macOS, which has afconvert)")


def encode_m4a(wav: Path, out: Path, bitrate_kbps: int, encoder: str) -> None:
    """Mono AAC-LC in an M4A container. [out] may have any suffix (callers use a temp name)."""
    if encoder == "ffmpeg":
        cmd = ["ffmpeg", "-nostdin", "-loglevel", "error", "-y", "-i", str(wav), "-vn", "-ac", "1",
               "-c:a", "aac", "-profile:a", "aac_low", "-b:a", f"{bitrate_kbps}k",
               "-movflags", "+faststart", "-f", "ipod", str(out)]
    elif encoder == "afconvert":
        cmd = ["afconvert", "-f", "m4af", "-d", "aac", "-c", "1", "-b", str(bitrate_kbps * 1000),
               "-s", "0", str(wav), str(out)]
    else:
        raise AudioError(f"unknown encoder {encoder}")
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        raise AudioError(f"{encoder} failed: {result.stderr.strip()[:300]}")


# --- M4A inspection -----------------------------------------------------------------

@dataclass(frozen=True)
class M4aInfo:
    codec: str
    audio_object_type: int
    channels: int
    sample_rate: int
    duration_ms: int

    @property
    def is_aac_lc_mono(self) -> bool:
        return self.codec == "mp4a" and self.audio_object_type == 2 and self.channels == 1


def inspect_m4a(path: Path) -> M4aInfo:
    """Codec, AAC object type, channels and duration, read from the MP4 boxes."""
    data = path.read_bytes()
    moov = _child(data, 0, len(data), "moov")
    for typ, start, end in _boxes(data, *moov):
        if typ != "trak":
            continue
        mdia = _child(data, start, end, "mdia")
        hdlr = _child(data, *mdia, "hdlr")
        if data[hdlr[0] + 8:hdlr[0] + 12] != b"soun":
            continue
        mdhd = _child(data, *mdia, "mdhd")
        timescale, duration = _mdhd(data, mdhd[0])
        stbl = _child(data, *_child(data, *mdia, "minf"), "stbl")
        stsd_start, stsd_end = _child(data, *stbl, "stsd")
        typ, entry_start, entry_end = next(_boxes(data, stsd_start + 8, stsd_end))
        version = struct.unpack(">H", data[entry_start + 8:entry_start + 10])[0]
        channels = struct.unpack(">H", data[entry_start + 16:entry_start + 18])[0]
        rate = struct.unpack(">I", data[entry_start + 24:entry_start + 28])[0] >> 16
        children = entry_start + 28 + {0: 0, 1: 16, 2: 36}.get(version, 0)
        aot, asc_channels = _audio_specific_config(data, children, entry_end) if typ == "mp4a" else (0, 0)
        # The sample entry's channel count is often a fixed 2; the AAC config is authoritative.
        return M4aInfo(typ, aot, asc_channels or channels, rate,
                       round(duration * 1000 / timescale) if timescale else 0)
    raise AudioError(f"{path}: no audio track")


def _boxes(data: bytes, start: int, end: int):
    i = start
    while i + 8 <= end:
        size, typ = struct.unpack(">I4s", data[i:i + 8])
        header = 8
        if size == 1:
            size, header = struct.unpack(">Q", data[i + 8:i + 16])[0], 16
        elif size == 0:
            size = end - i
        if size < header or i + size > end:
            raise AudioError("corrupt MP4 box structure")
        yield typ.decode("latin-1"), i + header, i + size
        i += size


def _child(data: bytes, start: int, end: int, name: str) -> tuple[int, int]:
    for typ, s, e in _boxes(data, start, end):
        if typ == name:
            return s, e
    raise AudioError(f"missing MP4 box '{name}'")


def _mdhd(data: bytes, start: int) -> tuple[int, int]:
    if data[start] == 1:
        return struct.unpack(">IQ", data[start + 20:start + 32])
    return struct.unpack(">II", data[start + 12:start + 20])


def _audio_specific_config(data: bytes, start: int, end: int) -> tuple[int, int]:
    """(audioObjectType, channelConfiguration) from the esds descriptor; AAC-LC is 2. (0, 0) if absent."""
    for typ, s, e in _boxes(data, start, end):
        if typ == "wave":  # QuickTime-style wrapper
            found = _audio_specific_config(data, s, e)
            if found[0]:
                return found
        if typ != "esds":
            continue
        i = s + 4  # full-box version and flags
        while i < e:
            tag, i = data[i], i + 1
            length = 0
            for _ in range(4):
                byte, i = data[i], i + 1
                length = (length << 7) | (byte & 0x7F)
                if not byte & 0x80:
                    break
            if tag == 0x03:  # ES_Descriptor: ES_ID, flags, then optional fields
                flags, i = data[i + 2], i + 3
                if flags & 0x80:  # streamDependenceFlag
                    i += 2
                if flags & 0x40:  # URL_Flag
                    i += 1 + data[i]
                if flags & 0x20:  # OCRstreamFlag
                    i += 2
            elif tag == 0x04:  # DecoderConfigDescriptor: fixed 13 bytes, then DecSpecificInfo
                i += 13
            elif tag == 0x05:  # AudioSpecificConfig: 5 bits object type, 4 bits rate index, 4 bits channels
                bits = int.from_bytes(data[i:i + 6], "big")
                total = 8 * len(data[i:i + 6])
                aot = bits >> (total - 5)
                rate_index = (bits >> (total - 9)) & 0xF
                shift = total - 9 - (24 if rate_index == 0xF else 0) - 4
                return aot, (bits >> shift) & 0xF
            else:
                i += length
    return 0, 0
