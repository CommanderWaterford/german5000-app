import contextlib
import io
import json
import tempfile
import unittest
from pathlib import Path

import audio_tools as at
import inventory
import tts_production as tp
from google_tts import FakeTts, FatalSynthesisError, RateLimiter, SynthesisSettings, TransientSynthesisError, with_retries


def record(entry, kind, text, path=None, fmt="m4a"):
    suffix = "word" if kind == "word" else "ex1.audio"
    return {"audioId": f"{entry}.{suffix}", "path": path or f"audio/{entry}/{kind}.m4a",
            "spokenText": text, "format": fmt}


def entry(entry_id, rank, pos="NOUN", lemma="Haus", **grammar):
    return {"entryId": entry_id, "curriculumRank": rank, "pos": pos, "lemma": lemma, "grammar": grammar}


def has_encoder():
    try:
        at.find_encoder()
        return True
    except at.AudioError:
        return False


class InventoryTest(unittest.TestCase):
    def test_valid_records_become_clips_at_stable_paths(self):
        clips = inventory.validate([record("de.noun.zeit.01", "word", "die Zeit"),
                                    record("de.noun.zeit.01", "ex1", "Ich habe keine Zeit.")],
                                   [entry("de.noun.zeit.01", 1)])
        self.assertEqual(Path("/c/audio/de.noun.zeit.01/word.m4a"), clips[0].destination(Path("/c")))
        self.assertEqual(("word", "ex1"), (clips[0].kind, clips[1].kind))

    def test_unsafe_paths_wrong_ids_formats_and_gaps_are_all_reported(self):
        records = [
            record("e1", "word", "a", path="audio/../../etc/word.m4a"),
            record("e2", "word", "b", path="/abs/word.m4a"),
            {"audioId": "e3.ex1.audio", "path": "audio/e3/word.m4a", "spokenText": "c", "format": "m4a"},
            record("e4", "word", "d", fmt="mp3"),
            record("e5", "word", "   "),
            record("e6", "word", "x" * 5001),
        ]
        with self.assertRaises(inventory.InventoryError) as ctx:
            inventory.validate(records, [entry("e4", 1)])
        text = "\n".join(ctx.exception.problems)
        for expected in ["unsafe", "does not match", "format 'mp3'", "empty spokenText", "exceeds 5000",
                         "e4: no ex1 audio record"]:
            self.assertIn(expected, text)

    def test_overrides_apply_only_to_the_text_they_were_reviewed_for(self):
        clip = inventory.Clip("e.word", "e", "word", "-jährig", "audio/e/word.m4a")
        current = {"e.word": inventory.Override(for_text="-jährig", ssml="<speak>jährig</speak>")}
        value = inventory.synthesis_input(clip, current)
        self.assertEqual(("<speak>jährig</speak>", True), (value.value, value.is_ssml))
        stale = {"e.word": inventory.Override(for_text="-jährige", text="jährige")}
        with self.assertRaises(inventory.InventoryError):
            inventory.synthesis_input(clip, stale)

    def test_cost_estimate_uses_the_voice_family(self):
        self.assertAlmostEqual(3.54, inventory.estimate_cost(221_291, "de-DE-Neural2-G"), places=2)
        self.assertAlmostEqual(0.89, inventory.estimate_cost(221_291, "de-DE-Wavenet-B"), places=2)
        self.assertIsNone(inventory.estimate_cost(1000, "de-DE-Standard-A"))

    def test_pilot_is_deterministic_and_covers_the_special_cases(self):
        entries, records = [], []
        for i in range(300):
            e = entry(f"e{i}", i + 1, lemma=f"Wort{i}", pluralStatus="has_plural")
            if i == 150:
                e = entry("suffix", i + 1, pos="SUFFIX", lemma="-jährig")
            if i in (10, 200):
                e["grammar"]["pluralStatus"] = "plural_only"
            entries.append(e)
            records += [record(e["entryId"], "word", f"die Tür{i}" if i % 5 == 0 else f"das Wort{i}"),
                        record(e["entryId"], "ex1", f"Hat er {i} Wörter?" if i % 7 == 0 else "Ein Satz.")]
        clips = inventory.validate(records, entries)
        first, second = inventory.select_pilot(clips, entries), inventory.select_pilot(clips, entries)
        self.assertEqual(first, second)
        self.assertEqual(100, len(first))
        reasons = {r for item in first for r in item["reasons"]}
        for expected in ["suffix entry", "plural-only noun", "question", "number in sentence", "umlaut"]:
            self.assertIn(expected, reasons)


@unittest.skipUnless(has_encoder(), "needs FFmpeg or afconvert")
class ProductionTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        root = Path(self.tmp.name)
        self.work, self.content = root / "work", root / "content"
        self.settings = tp.Settings(SynthesisSettings("de-DE-Fake-A"), at.Normalization(), 32, "auto")
        self.encoder = at.find_encoder()
        self.texts = {"e1": ("die Zeit", "Ich habe keine Zeit."),
                      "e2": ("das Haus", "Ich habe keine Zeit.")}  # shared sentence

    def tearDown(self):
        self.tmp.cleanup()

    def clips(self):
        records = []
        for entry_id, (word, sentence) in self.texts.items():
            records += [record(entry_id, "word", word), record(entry_id, "ex1", sentence)]
        return inventory.validate(records, [entry(e, i) for i, e in enumerate(self.texts)])

    def produce(self, backend=None, overrides=None, rerecord=False):
        ledger = tp.Ledger(self.work / "ledger.jsonl")
        plan = tp.make_plan(self.clips(), overrides or {}, self.settings, ledger, self.work, self.content,
                            self.encoder, rerecord)
        backend = backend or FakeTts()
        producer = tp.Producer(backend, self.settings, ledger, self.work, self.encoder, RateLimiter(0),
                               retry_sleep=lambda s: None)
        errors = tp.run(plan, producer, workers=2, log=lambda *a, **k: None)
        return plan, backend, errors

    def test_produces_every_clip_once_and_shares_identical_inputs(self):
        plan, backend, errors = self.produce()
        self.assertEqual([], errors)
        self.assertEqual(3, len(backend.calls))  # 4 clips, 3 distinct texts
        for clip in self.clips():
            info = at.inspect_m4a(clip.destination(self.content))
            self.assertTrue(info.is_aac_lc_mono)
        ledger = tp.Ledger(self.work / "ledger.jsonl")
        self.assertEqual([], tp.verify(tp.make_plan(self.clips(), {}, self.settings, ledger, self.work,
                                                    self.content, self.encoder), ledger))
        rec = ledger.clips["e1.word"]
        for key in ["spokenText", "spokenTextSha256", "voice", "language", "finalPath", "finalSha256",
                    "durationMs", "generatedAt", "reviewStatus"]:
            self.assertIn(key, rec)
        self.assertEqual("pending", rec["reviewStatus"])

    def test_rerun_skips_finished_clips_and_text_changes_redo_only_those(self):
        self.produce()
        plan, backend, _ = self.produce()
        self.assertEqual(4, plan.count("ok"))
        self.assertEqual([], backend.calls)
        self.texts["e1"] = ("die Zeit", "Wir haben viel Zeit.")
        plan, backend, _ = self.produce()
        self.assertEqual(["Wir haben viel Zeit."], backend.calls)
        self.assertEqual(3, plan.count("ok"))

    def test_settings_changes_reencode_from_cached_masters(self):
        self.produce()
        self.settings = tp.Settings(self.settings.synthesis, self.settings.normalization, 48, "auto")
        plan, backend, _ = self.produce()
        self.assertEqual(4, plan.count("encode"))
        self.assertEqual([], backend.calls)

    def test_reviewed_clips_keep_review_on_reencode_and_are_protected_from_new_text(self):
        self.produce()
        ledger = tp.Ledger(self.work / "ledger.jsonl")
        ledger.append({"kind": "review", "audioId": "e1.word", "status": "approved", "at": tp.now()})
        self.settings = tp.Settings(self.settings.synthesis, self.settings.normalization, 48, "auto")
        self.produce(rerecord=True)
        self.assertEqual("approved", tp.Ledger(self.work / "ledger.jsonl").clips["e1.word"]["reviewStatus"])
        self.texts["e1"] = ("die Zeiten", self.texts["e1"][1])
        plan, backend, _ = self.produce()
        self.assertEqual("blocked", next(i for i in plan.items if i.clip.audio_id == "e1.word").action)
        self.assertEqual([], backend.calls)
        plan, backend, _ = self.produce(rerecord=True)
        self.assertEqual(["die Zeiten"], backend.calls)
        self.assertEqual("pending", tp.Ledger(self.work / "ledger.jsonl").clips["e1.word"]["reviewStatus"])

    def test_transient_failures_are_retried(self):
        _, backend, errors = self.produce(FakeTts(fail_first={"die Zeit": 2}))
        self.assertEqual([], errors)
        self.assertEqual(2 + 3, len(backend.calls))

    def test_fatal_errors_stop_the_run(self):
        class Refusing(FakeTts):
            def synthesize(self, value, is_ssml, settings):
                self.calls.append(value)
                raise FatalSynthesisError("PermissionDenied: billing disabled")

        with self.assertRaises(FatalSynthesisError):
            self.produce(Refusing())
        self.assertFalse(any(c.destination(self.content).exists() for c in self.clips()))

    def test_verify_catches_missing_and_altered_files(self):
        self.produce()
        clips = self.clips()
        clips[0].destination(self.content).unlink()
        with clips[1].destination(self.content).open("ab") as f:
            f.write(b"\x00")
        ledger = tp.Ledger(self.work / "ledger.jsonl")
        problems = tp.verify(tp.make_plan(clips, {}, self.settings, ledger, self.work, self.content,
                                          self.encoder), ledger)
        self.assertTrue(any("missing" in p for p in problems))
        self.assertTrue(any("checksum" in p for p in problems))

    def test_interrupted_masters_are_not_trusted(self):
        self.produce()
        master = next((self.work / "masters").rglob("*.wav"))
        master.write_bytes(master.read_bytes()[:100])  # simulate a corrupted master
        self.texts["e2"] = ("das Haus", "Ein anderer Satz.")  # force re-delivery of something
        ledger = tp.Ledger(self.work / "ledger.jsonl")
        fp = master.stem
        self.assertFalse(tp.master_ok(ledger, self.work, fp))


class CommandTest(unittest.TestCase):
    def test_fake_backend_refuses_to_write_into_the_real_content_folder(self):
        pack = {"audio": [record("e1", "word", "die Zeit"), record("e1", "ex1", "Satz.")],
                "entries": [entry("e1", 1)]}
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "pack.json"
            path.write_text(json.dumps(pack))
            with self.assertRaises(SystemExit) as ctx, contextlib.redirect_stdout(io.StringIO()):
                tp.main(["--pack", str(path), "--work", tmp, "produce", "--backend", "fake", "--voice", "x"])
            self.assertIn("fake backend", str(ctx.exception))

    @unittest.skipUnless(has_encoder(), "needs FFmpeg or afconvert")
    def test_large_google_runs_need_confirmation(self):
        texts = [f"Satz Nummer {i} mit genug Text, um teuer zu werden." * 3 for i in range(300)]
        records, entries = [], []
        for i, text in enumerate(texts):
            entries.append(entry(f"e{i}", i))
            records += [record(f"e{i}", "word", f"Wort{i}"), record(f"e{i}", "ex1", text)]
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "pack.json"
            path.write_text(json.dumps({"audio": records, "entries": entries}))
            with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                code = tp.main(["--pack", str(path), "--work", tmp, "--content-root", tmp,
                                "produce", "--voice", "de-DE-Neural2-G"])
            self.assertEqual(2, code)


class RetryTest(unittest.TestCase):
    def test_bounded_backoff_gives_up_after_the_last_attempt(self):
        delays = []

        def always_busy():
            raise TransientSynthesisError("503")

        with self.assertRaises(TransientSynthesisError):
            with_retries(always_busy, attempts=4, base_delay=1, max_delay=3, sleep=delays.append)
        self.assertEqual(3, len(delays))
        self.assertTrue(all(d <= 3 for d in delays))


if __name__ == "__main__":
    unittest.main()
