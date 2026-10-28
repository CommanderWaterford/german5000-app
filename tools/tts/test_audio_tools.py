import math
import tempfile
import unittest
from pathlib import Path

import audio_tools as at


def tone(seconds, amplitude=0.1, freq=1000.0, rate=48000, phase=0.0):
    return [amplitude * math.sin(2 * math.pi * freq * i / rate + phase) for i in range(int(seconds * rate))]


def has_encoder():
    try:
        at.find_encoder()
        return True
    except at.AudioError:
        return False


class LoudnessTest(unittest.TestCase):
    def test_reference_tone_measures_minus_23_lufs_at_both_sample_rates(self):
        # BS.1770: a 1 kHz sine at -20 dBFS reads -23.01 LUFS.
        for rate in (48000, 24000):
            loudness, short = at.integrated_loudness(tone(2, rate=rate), rate)
            self.assertAlmostEqual(-23.01, loudness, delta=0.1)
            self.assertFalse(short)

    def test_gating_ignores_silence(self):
        # Ungated, two seconds of silence around four of tone would read 1.8 dB quieter;
        # gated, only the few blocks straddling the edges pull it down a little.
        rate = 48000
        signal = [0.0] * rate + tone(4, rate=rate) + [0.0] * rate
        self.assertAlmostEqual(-23.01, at.integrated_loudness(signal, rate)[0], delta=0.5)

    def test_clips_under_one_block_are_flagged_short(self):
        self.assertTrue(at.integrated_loudness(tone(0.3), 48000)[1])

    def test_true_peak_finds_inter_sample_overs(self):
        # A quarter-rate sine sampled 45 degrees off its crests: samples read 0.707 of the real peak.
        signal = [math.sin(2 * math.pi * i / 4 + math.pi / 4) for i in range(4000)]
        self.assertAlmostEqual(0.7071, max(abs(v) for v in signal), places=3)
        self.assertAlmostEqual(1.0, at.true_peak(signal), delta=0.01)


class NormalizeTest(unittest.TestCase):
    def test_reaches_the_target_loudness(self):
        out = at.normalize(tone(1.5, rate=24000), 24000, at.Normalization())
        self.assertAlmostEqual(-18.0, out.loudness_out, delta=0.2)
        self.assertLessEqual(out.true_peak_dbtp, -1.0 + 0.05)
        self.assertFalse(out.peak_limited)

    def test_peaky_audio_is_limited_by_the_true_peak_ceiling_not_clipped(self):
        # A quiet bed with one loud spike: reaching -18 LUFS would push the spike over -1 dBTP.
        rate = 24000
        signal = tone(2, amplitude=0.01, rate=rate)
        signal[rate] = 0.9
        out = at.normalize(signal, rate, at.Normalization())
        self.assertTrue(out.peak_limited)
        self.assertLessEqual(out.true_peak_dbtp, -1.0 + 0.05)
        self.assertLess(out.loudness_out, -18.0)

    def test_trims_long_silence_but_keeps_the_pads(self):
        rate = 24000
        signal = [0.0] * rate + tone(0.5, rate=rate) + [0.0] * rate
        out = at.normalize(signal, rate, at.Normalization(lead_pad_ms=100, tail_pad_ms=150))
        self.assertAlmostEqual(500 + 100 + 150, out.duration_ms, delta=25)
        self.assertGreater(out.trimmed_ms, 1700)

    def test_silence_is_an_error(self):
        with self.assertRaises(at.AudioError):
            at.normalize([0.0] * 24000, 24000, at.Normalization())


class FileTest(unittest.TestCase):
    def test_wav_round_trip(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "a.wav"
            at.write_wav(path, tone(0.2, amplitude=0.5, rate=24000), 24000)
            samples, rate = at.read_wav(path)
            self.assertEqual(24000, rate)
            self.assertAlmostEqual(0.5, max(samples), delta=0.01)

    @unittest.skipUnless(has_encoder(), "needs FFmpeg or afconvert")
    def test_encoded_file_is_mono_aac_lc_of_the_right_length(self):
        with tempfile.TemporaryDirectory() as tmp:
            wav, out = Path(tmp) / "a.wav", Path(tmp) / "a.tmp"
            at.write_wav(wav, tone(1.0, amplitude=0.3, freq=440, rate=24000), 24000)
            at.encode_m4a(wav, out, 48, at.find_encoder())
            info = at.inspect_m4a(out)
            self.assertTrue(info.is_aac_lc_mono, info)
            self.assertAlmostEqual(1000, info.duration_ms, delta=150)

    def test_garbage_is_not_an_m4a(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "x.m4a"
            path.write_bytes(b"\x00\x00\x00\x10ftypM4A \x00\x00\x00\x00")
            with self.assertRaises(at.AudioError):
                at.inspect_m4a(path)


if __name__ == "__main__":
    unittest.main()
