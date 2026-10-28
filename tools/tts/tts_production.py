#!/usr/bin/env python3
"""German5000 audio production with Google Cloud Text-to-Speech.

Reads the generated content pack, synthesizes a lossless master per distinct input,
normalizes and encodes mono AAC-LC M4A at each record's stable path, and keeps a
resumable ledger. Workflow and rationale: docs/GOOGLE_CLOUD_TTS.md.

  python3 tools/tts/tts_production.py plan                  # dry run: counts, cost, destinations
  python3 tools/tts/tts_production.py pilot                 # choose the 100-entry pilot
  python3 tools/tts/tts_production.py voices                # list de-DE voices (API, no charge)
  python3 tools/tts/tts_production.py produce --pilot --voice de-DE-Neural2-G
  python3 tools/tts/tts_production.py verify --pilot
  python3 tools/tts/tts_production.py review --export work/tts/review.csv --pilot
  python3 tools/tts/tts_production.py rights
"""
from __future__ import annotations

import argparse
import csv
import datetime as dt
import hashlib
import json
import os
import re
import shutil
import sys
import threading
import uuid
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import audio_tools  # noqa: E402
import inventory  # noqa: E402
from google_tts import (FakeTts, FatalSynthesisError, GoogleTts, RateLimiter,  # noqa: E402
                        SynthesisSettings, TransientSynthesisError, with_retries)

DEFAULT_WORK = inventory.REPO / "work/tts"
DEFAULT_SETTINGS = Path(__file__).resolve().parent / "tts-settings.json"
# A run larger than this many billable characters needs --yes (the pilot is ~3,000).
CONFIRM_ABOVE_CHARS = 20_000


# --- Settings ------------------------------------------------------------------------

@dataclass(frozen=True)
class Settings:
    synthesis: SynthesisSettings
    normalization: audio_tools.Normalization
    bitrate_kbps: int
    encoder: str  # "auto", "ffmpeg" or "afconvert"

    @staticmethod
    def load(path: Path, voice: str | None, bitrate: int | None, encoder: str | None) -> "Settings":
        raw = json.loads(path.read_text(encoding="utf-8")) if path.is_file() else {}
        syn, norm, enc = raw.get("synthesis", {}), raw.get("normalization", {}), raw.get("encoding", {})
        return Settings(
            SynthesisSettings(
                voice=voice or syn.get("voice") or "",
                language=syn.get("language", "de-DE"),
                speaking_rate=syn.get("speakingRate", 1.0),
                sample_rate_hertz=syn.get("sampleRateHertz", 0),
            ),
            audio_tools.Normalization(
                target_lufs=norm.get("targetLufs", -18.0),
                ceiling_dbtp=norm.get("ceilingDbtp", -1.0),
                lead_pad_ms=norm.get("leadPadMs", 100),
                tail_pad_ms=norm.get("tailPadMs", 150),
            ),
            bitrate or enc.get("bitrateKbps", 48),
            encoder or enc.get("encoder", "auto"),
        )


def fingerprint(payload: dict) -> str:
    return hashlib.sha256(json.dumps(payload, sort_keys=True, ensure_ascii=False).encode()).hexdigest()


def synth_fingerprint(value: inventory.SynthesisInput, settings: Settings) -> str:
    return fingerprint({"input": value.value, "ssml": value.is_ssml, **settings.synthesis.as_dict()})


def delivery_fingerprint(synth_fp: str, settings: Settings, encoder: str) -> str:
    return fingerprint({
        "synthesis": synth_fp,
        "normalization": settings.normalization.as_dict(),
        "encoding": {"codec": "aac_low", "channels": 1, "bitrateKbps": settings.bitrate_kbps, "encoder": encoder},
    })


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def now() -> str:
    return dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat()


# --- Ledger ----------------------------------------------------------------------------

class Ledger:
    """Append-only JSON Lines log; the latest record per key wins, so an interrupted run
    resumes where it stopped. Kinds: 'master' (by fingerprint), 'clip' and 'review' (by audioId)."""

    def __init__(self, path: Path):
        self.path = path
        self.masters: dict[str, dict] = {}
        self.clips: dict[str, dict] = {}
        self._lock = threading.Lock()
        if path.is_file():
            for line in path.read_text(encoding="utf-8").splitlines():
                if line.strip():
                    self._apply(json.loads(line))

    def _apply(self, record: dict) -> None:
        kind = record["kind"]
        if kind == "master":
            self.masters[record["fingerprint"]] = record
        elif kind == "clip":
            self.clips[record["audioId"]] = record
        elif kind == "review" and record["audioId"] in self.clips:
            clip = dict(self.clips[record["audioId"]])
            clip.update(reviewStatus=record["status"], reviewedAt=record["at"],
                        reviewer=record.get("reviewer"), reviewNote=record.get("note"))
            self.clips[record["audioId"]] = clip

    def append(self, record: dict) -> None:
        with self._lock:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            with self.path.open("a", encoding="utf-8") as f:
                f.write(json.dumps(record, ensure_ascii=False) + "\n")
                f.flush()
                os.fsync(f.fileno())
            self._apply(record)


# --- Planning ----------------------------------------------------------------------------

@dataclass
class ClipPlan:
    clip: inventory.Clip
    source: inventory.SynthesisInput
    synth_fp: str
    delivery_fp: str
    destination: Path
    action: str  # "ok", "encode", "synthesize", "blocked"
    note: str = ""


@dataclass
class Plan:
    items: list[ClipPlan] = field(default_factory=list)
    problems: list[str] = field(default_factory=list)

    def count(self, action: str) -> int:
        return sum(1 for item in self.items if item.action == action)

    def characters_to_synthesize(self) -> int:
        seen, total = set(), 0
        for item in self.items:
            if item.action == "synthesize" and item.synth_fp not in seen:
                seen.add(item.synth_fp)
                total += item.source.billed_characters
        return total

    def masters_to_synthesize(self) -> int:
        return len({item.synth_fp for item in self.items if item.action == "synthesize"})


def master_path(work: Path, synth_fp: str) -> Path:
    return work / "masters" / synth_fp[:2] / f"{synth_fp}.wav"


def master_ok(ledger: Ledger, work: Path, synth_fp: str) -> bool:
    record = ledger.masters.get(synth_fp)
    path = master_path(work, synth_fp)
    return record is not None and path.is_file() and sha256_file(path) == record["sha256"]


def make_plan(clips, overrides, settings, ledger, work, content_root, encoder, rerecord_reviewed=False) -> Plan:
    plan = Plan()
    master_cache: dict[str, bool] = {}
    for clip in clips:
        try:
            source = inventory.synthesis_input(clip, overrides)
        except inventory.InventoryError as exc:
            plan.problems.extend(exc.problems)
            continue
        synth_fp = synth_fingerprint(source, settings)
        delivery_fp = delivery_fingerprint(synth_fp, settings, encoder)
        destination = clip.destination(content_root)
        record = ledger.clips.get(clip.audio_id)
        current = (record is not None and destination.is_file()
                   and record.get("deliveryFingerprint") == delivery_fp
                   and sha256_file(destination) == record.get("finalSha256"))
        if current:
            action, note = "ok", ""
        elif (record and record.get("reviewStatus") == "approved" and destination.is_file()
              and not rerecord_reviewed):
            # Never overwrite a reviewed recording behind the reviewer's back.
            action, note = "blocked", "reviewed recording is out of date; rerun with --rerecord-reviewed"
        else:
            if synth_fp not in master_cache:
                master_cache[synth_fp] = master_ok(ledger, work, synth_fp)
            action = "encode" if master_cache[synth_fp] else "synthesize"
            note = "missing" if not destination.is_file() else "changed or unverified"
        plan.items.append(ClipPlan(clip, source, synth_fp, delivery_fp, destination, action, note))
    return plan


# --- Production --------------------------------------------------------------------------

class Producer:
    def __init__(self, backend, settings: Settings, ledger: Ledger, work: Path, encoder: str,
                 limiter: RateLimiter, retry_sleep=None):
        self.backend = backend
        self.settings = settings
        self.ledger = ledger
        self.work = work
        self.encoder = encoder
        self.limiter = limiter
        self.retry_sleep = retry_sleep
        self._master_locks: dict[str, threading.Lock] = {}
        self._locks_guard = threading.Lock()
        self.synthesized = 0
        self.encoded = 0

    def _master_lock(self, fp: str) -> threading.Lock:
        with self._locks_guard:
            return self._master_locks.setdefault(fp, threading.Lock())

    def master(self, item: ClipPlan) -> Path:
        """The lossless master for this input: reused when its fingerprint and checksum
        match, otherwise synthesized once (identical inputs share it)."""
        path = master_path(self.work, item.synth_fp)
        with self._master_lock(item.synth_fp):
            if master_ok(self.ledger, self.work, item.synth_fp):
                return path

            def call():
                self.limiter.wait()
                return self.backend.synthesize(item.source.value, item.source.is_ssml, self.settings.synthesis)

            kwargs = {"sleep": self.retry_sleep} if self.retry_sleep else {}
            audio = with_retries(call, **kwargs)
            path.parent.mkdir(parents=True, exist_ok=True)
            tmp = path.with_suffix(f".{uuid.uuid4().hex}.tmp")
            tmp.write_bytes(audio)
            audio_tools.read_wav(tmp)  # reject anything that is not mono 16-bit WAV
            os.replace(tmp, path)
            self.ledger.append({
                "kind": "master", "fingerprint": item.synth_fp, "path": str(path.relative_to(self.work)),
                "sha256": sha256_file(path), "input": item.source.value, "ssml": item.source.is_ssml,
                "characters": item.source.billed_characters, "backend": self.backend.name,
                **self.settings.synthesis.as_dict(), "generatedAt": now(),
            })
            self.synthesized += 1
            return path

    def deliver(self, item: ClipPlan) -> None:
        master = self.master(item)
        samples, rate = audio_tools.read_wav(master)
        processed = audio_tools.normalize(samples, rate, self.settings.normalization)
        tmp_dir = self.work / "tmp"
        tmp_dir.mkdir(parents=True, exist_ok=True)
        wav = tmp_dir / f"{uuid.uuid4().hex}.wav"
        item.destination.parent.mkdir(parents=True, exist_ok=True)
        # Same directory as the destination so the final rename is atomic; not *.m4a, so
        # the content pipeline never picks up a half-written file.
        staged = item.destination.parent / f".{item.destination.name}.{uuid.uuid4().hex}.tmp"
        try:
            audio_tools.write_wav(wav, processed.samples, processed.rate)
            audio_tools.encode_m4a(wav, staged, self.settings.bitrate_kbps, self.encoder)
            info = audio_tools.inspect_m4a(staged)
            if not info.is_aac_lc_mono:
                raise audio_tools.AudioError(f"encoded file is {info}, expected mono AAC-LC")
            # AAC adds encoder delay and padding: allow up to ~2 frames plus priming.
            if abs(info.duration_ms - processed.duration_ms) > 150:
                raise audio_tools.AudioError(
                    f"encoded duration {info.duration_ms} ms vs {processed.duration_ms} ms processed")
            os.replace(staged, item.destination)
        finally:
            wav.unlink(missing_ok=True)
            staged.unlink(missing_ok=True)
        previous = self.ledger.clips.get(item.clip.audio_id, {})
        unchanged_text = previous.get("spokenText") == item.clip.spoken_text and previous.get("synthesisFingerprint") == item.synth_fp
        self.ledger.append({
            "kind": "clip", "audioId": item.clip.audio_id, "entryId": item.clip.entry_id,
            "spokenText": item.clip.spoken_text,
            "spokenTextSha256": hashlib.sha256(item.clip.spoken_text.encode()).hexdigest(),
            "input": item.source.value, "ssml": item.source.is_ssml,
            **self.settings.synthesis.as_dict(), "backend": self.backend.name,
            "normalization": self.settings.normalization.as_dict(),
            "encoding": {"codec": "aac_low", "channels": 1, "bitrateKbps": self.settings.bitrate_kbps,
                         "encoder": self.encoder},
            "synthesisFingerprint": item.synth_fp, "deliveryFingerprint": item.delivery_fp,
            "finalPath": item.clip.pack_path, "finalSha256": sha256_file(item.destination),
            "finalBytes": item.destination.stat().st_size, "durationMs": processed.duration_ms,
            "loudnessLufs": processed.loudness_out, "truePeakDbtp": processed.true_peak_dbtp,
            "gainDb": processed.gain_db, "trimmedMs": processed.trimmed_ms,
            "shortClip": processed.short_clip, "peakLimited": processed.peak_limited,
            "generatedAt": now(),
            # A new rendering of the same input keeps its review; new text needs a new one.
            "reviewStatus": previous.get("reviewStatus", "pending") if unchanged_text else "pending",
        })
        self.encoded += 1


def run(plan: Plan, producer: Producer, workers: int, log=print) -> list[tuple[str, str]]:
    """Process every clip that is not up to date. Stops at the first fatal error."""
    todo = [item for item in plan.items if item.action in ("synthesize", "encode")]
    errors: list[tuple[str, str]] = []
    stop = threading.Event()

    def task(item: ClipPlan) -> None:
        if stop.is_set():
            return
        try:
            producer.deliver(item)
        except FatalSynthesisError:
            stop.set()
            raise
        except (TransientSynthesisError, audio_tools.AudioError, OSError) as exc:
            errors.append((item.clip.audio_id, str(exc)))
            log(f"ERROR {item.clip.audio_id}: {exc}", file=sys.stderr)

    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = [pool.submit(task, item) for item in todo]
        done_count = 0
        for future in futures:
            try:
                future.result()
            except FatalSynthesisError:
                for other in futures:
                    other.cancel()
                raise
            done_count += 1
            if done_count % 50 == 0 or done_count == len(todo):
                log(f"  {done_count}/{len(todo)} clips")
    return errors


# --- Verification ------------------------------------------------------------------------

def verify(plan: Plan, ledger: Ledger) -> list[str]:
    """Every destination exists, is mono AAC-LC with a plausible duration, matches the
    ledger's checksum and was produced from the current text and settings."""
    problems = list(plan.problems)
    for item in plan.items:
        dest, audio_id = item.destination, item.clip.audio_id
        if not dest.is_file():
            problems.append(f"{audio_id}: missing {item.clip.pack_path}")
            continue
        record = ledger.clips.get(audio_id)
        try:
            info = audio_tools.inspect_m4a(dest)
        except (audio_tools.AudioError, OSError, IndexError, ValueError) as exc:
            problems.append(f"{audio_id}: unreadable M4A ({exc})")
            continue
        if not info.is_aac_lc_mono:
            problems.append(f"{audio_id}: not mono AAC-LC ({info})")
        if info.duration_ms < 150:
            problems.append(f"{audio_id}: only {info.duration_ms} ms long")
        if record is None:
            problems.append(f"{audio_id}: not in the production ledger")
        elif sha256_file(dest) != record.get("finalSha256"):
            problems.append(f"{audio_id}: file differs from the ledger checksum")
        elif record.get("deliveryFingerprint") != item.delivery_fp:
            problems.append(f"{audio_id}: produced from older text or settings")
    return problems


# --- Commands ------------------------------------------------------------------------------

def load(args) -> tuple[list[inventory.Clip], list[dict], dict, Settings, Ledger]:
    clips, entries = inventory.load_pack(args.pack)
    overrides = inventory.load_overrides(args.overrides)
    settings = Settings.load(args.settings, getattr(args, "voice", None),
                             getattr(args, "bitrate", None), getattr(args, "encoder", None))
    ledger = Ledger(args.work / "ledger.jsonl")
    if getattr(args, "pilot", False) or getattr(args, "ids", None):
        wanted = set(getattr(args, "ids", None) or [])
        if args.pilot:
            pilot_file = args.pilot_file
            if not pilot_file.is_file():
                raise SystemExit(f"{pilot_file} not found: run the 'pilot' command first")
            wanted |= {e["entryId"] for e in json.loads(pilot_file.read_text(encoding="utf-8"))["entries"]}
        clips = [c for c in clips if c.entry_id in wanted or c.audio_id in wanted]
    return clips, entries, overrides, settings, ledger


def resolved_encoder(settings: Settings) -> str:
    try:
        return audio_tools.find_encoder(settings.encoder)
    except audio_tools.AudioError:
        return "none"


def display(path: Path) -> str:
    try:
        return str(path.resolve().relative_to(inventory.REPO))
    except ValueError:
        return str(path)


def print_plan(plan: Plan, settings: Settings, encoder: str) -> None:
    chars = plan.characters_to_synthesize()
    total_chars = sum(len(i.clip.spoken_text) for i in plan.items)
    print(f"Clips:            {len(plan.items)} ({sum(i.clip.kind == 'word' for i in plan.items)} word, "
          f"{sum(i.clip.kind == 'ex1' for i in plan.items)} sentence), {total_chars:,} characters of spoken text")
    print(f"Distinct inputs:  {len({i.synth_fp for i in plan.items}):,}")
    print(f"Up to date:       {plan.count('ok')}")
    print(f"To encode:        {plan.count('encode')} (master already synthesized)")
    print(f"To synthesize:    {plan.count('synthesize')} clips from {plan.masters_to_synthesize()} requests, "
          f"{chars:,} billable characters")
    if plan.count("blocked"):
        print(f"Blocked:          {plan.count('blocked')} reviewed recordings are out of date")
    voice = settings.synthesis.voice or "(not chosen: pass --voice or set it in tools/tts/tts-settings.json)"
    print(f"Voice:            {voice}, {settings.synthesis.language}, rate {settings.synthesis.speaking_rate}")
    print(f"Delivery:         mono AAC-LC {settings.bitrate_kbps} kbps via {encoder}; "
          f"{settings.normalization.target_lufs} LUFS, true peak <= {settings.normalization.ceiling_dbtp} dBTP")
    cost = inventory.estimate_cost(chars, settings.synthesis.voice)
    rates = ", ".join(f"{family} ${chars * price / 1e6:.2f}" for family, price in inventory.PRICE_PER_MILLION.items())
    print(f"Estimated cost:   {'$%.2f' % cost if cost is not None else 'voice family unknown'} "
          f"({rates}; list prices checked {inventory.PRICES_CHECKED}, before free usage and taxes)")
    for item in [i for i in plan.items if i.action != "ok"][:6]:
        print(f"  {item.action:10} {item.clip.audio_id:34} {item.source.value[:40]!r} -> {display(item.destination)}")
    for problem in plan.problems[:20]:
        print(f"PROBLEM {problem}")


def cmd_plan(args) -> int:
    clips, _, overrides, settings, ledger = load(args)
    encoder = resolved_encoder(settings)
    plan = make_plan(clips, overrides, settings, ledger, args.work, args.content_root, encoder)
    print_plan(plan, settings, encoder)
    print("Dry run: no API calls made.")
    return 1 if plan.problems else 0


def cmd_pilot(args) -> int:
    clips, entries = inventory.load_pack(args.pack)
    pilot = inventory.select_pilot(clips, entries, args.size)
    out = args.pilot_file
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps({"createdAt": now(), "entries": pilot}, ensure_ascii=False, indent=1) + "\n",
                   encoding="utf-8")
    covered = {}
    for item in pilot:
        for reason in item["reasons"]:
            covered[reason] = covered.get(reason, 0) + 1
    print(f"Pilot: {len(pilot)} entries, {2 * len(pilot)} clips -> {out}")
    for reason, count in sorted(covered.items(), key=lambda kv: -kv[1]):
        print(f"  {count:3}  {reason}")
    return 0


def cmd_voices(args) -> int:
    backend = FakeTts() if args.backend == "fake" else GoogleTts(args.quota_project)
    voices = backend.list_voices(args.language)
    out = args.work / f"voices-{args.language}.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(voices, indent=1) + "\n", encoding="utf-8")
    for v in sorted(voices, key=lambda v: v["name"]):
        family = inventory.voice_family(v["name"])
        price = f"${inventory.PRICE_PER_MILLION[family]:.0f}/M chars" if family else "price: check"
        print(f"{v['name']:32} {v['ssmlGender']:8} {v['naturalSampleRateHertz']:>6} Hz  {price}")
    print(f"{len(voices)} voices -> {out}  (HablaDeutsch used de-DE-Neural2-G)")
    return 0


def cmd_produce(args) -> int:
    clips, _, overrides, settings, ledger = load(args)
    if not settings.synthesis.voice:
        raise SystemExit("No voice chosen: pass --voice (see the 'voices' command) or set it in tools/tts/tts-settings.json")
    if args.backend == "fake" and args.content_root.resolve() == inventory.CONTENT_ROOT.resolve():
        raise SystemExit("The fake backend writes tones, not speech: pass --content-root somewhere outside "
                         "content/german5000 (e.g. work/tts/rehearsal)")
    encoder = audio_tools.find_encoder(settings.encoder)
    plan = make_plan(clips, overrides, settings, ledger, args.work, args.content_root, encoder, args.rerecord_reviewed)
    if args.limit:
        keep = [i for i in plan.items if i.action in ("synthesize", "encode")][:args.limit]
        keep_ids = {id(i) for i in keep}
        for item in plan.items:
            if item.action in ("synthesize", "encode") and id(item) not in keep_ids:
                item.action = "skipped"
    print_plan(plan, settings, encoder)
    if plan.problems:
        print("Fix the problems above first.", file=sys.stderr)
        return 1
    chars = plan.characters_to_synthesize()
    if args.backend == "google" and chars > CONFIRM_ABOVE_CHARS and not args.yes:
        print(f"This synthesizes {chars:,} billable characters. Rerun with --yes to proceed.", file=sys.stderr)
        return 2
    backend = FakeTts() if args.backend == "fake" else GoogleTts(args.quota_project)
    producer = Producer(backend, settings, ledger, args.work, encoder, RateLimiter(args.rpm))
    try:
        errors = run(plan, producer, args.workers)
    except FatalSynthesisError as exc:
        print(f"STOPPED: {exc}", file=sys.stderr)
        print("Nothing else was attempted. Fix credentials, billing or input, then rerun: finished clips are kept.",
              file=sys.stderr)
        return 3
    report = {
        "finishedAt": now(), "backend": backend.name, "synthesis": settings.synthesis.as_dict(),
        "requests": producer.synthesized, "clipsWritten": producer.encoded, "errors": errors,
    }
    (args.work / "last-run.json").write_text(json.dumps(report, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"\nSynthesized {producer.synthesized} masters; wrote {producer.encoded} clips; errors: {len(errors)}")
    if errors:
        print("Rerun the same command to retry the failed clips; finished ones are kept.", file=sys.stderr)
    return 1 if errors else 0


def cmd_verify(args) -> int:
    clips, _, overrides, settings, ledger = load(args)
    encoder = resolved_encoder(settings)
    plan = make_plan(clips, overrides, settings, ledger, args.work, args.content_root, encoder)
    problems = verify(plan, ledger)
    for problem in problems[:50]:
        print(problem)
    if len(problems) > 50:
        print(f"... and {len(problems) - 50} more")
    reviewed = sum(1 for i in plan.items if ledger.clips.get(i.clip.audio_id, {}).get("reviewStatus") == "approved")
    sizes = [i.destination.stat().st_size for i in plan.items if i.destination.is_file()]
    print(f"Checked {len(plan.items)} clips: {len(problems)} problem(s); {reviewed} approved by review; "
          f"{sum(sizes) / 1e6:.1f} MB of audio")
    if sizes:
        # §6.2: measure the budget on the pilot before recording everything.
        average = sum(sizes) / len(sizes)
        print(f"Average {average / 1e3:.1f} kB per checked clip; extrapolated audio size "
              f"{average * 10000 / 1e6:.0f} MB for 10,000 clips. "
              "This subset estimate excludes app code; measure the final download against the 180 MB budget.")
    return 1 if problems else 0


def cmd_review(args) -> int:
    clips, _, _, _, ledger = load(args)
    if args.export:
        args.export.parent.mkdir(parents=True, exist_ok=True)
        with args.export.open("w", newline="", encoding="utf-8") as f:
            writer = csv.writer(f)
            writer.writerow(["audioId", "kind", "spokenText", "file", "durationMs", "loudnessLufs",
                             "shortClip", "reviewStatus", "note"])
            for clip in clips:
                record = ledger.clips.get(clip.audio_id, {})
                writer.writerow([clip.audio_id, clip.kind, clip.spoken_text, display(clip.destination(args.content_root)),
                                 record.get("durationMs", ""), record.get("loudnessLufs", ""),
                                 record.get("shortClip", ""), record.get("reviewStatus", "not produced"), ""])
        print(f"Review sheet: {args.export} ({len(clips)} clips). Fill in reviewStatus "
              "(approved/rejected) and note, then: review --import <file> --reviewer <name>")
        return 0
    if args.import_csv:
        updates = 0
        with args.import_csv.open(encoding="utf-8") as f:
            for row in csv.DictReader(f):
                status = (row.get("reviewStatus") or "").strip()
                if status in ("approved", "rejected") and row["audioId"] in ledger.clips \
                        and ledger.clips[row["audioId"]].get("reviewStatus") != status:
                    ledger.append({"kind": "review", "audioId": row["audioId"], "status": status,
                                   "reviewer": args.reviewer, "note": row.get("note", ""), "at": now()})
                    updates += 1
        print(f"Recorded {updates} review decision(s).")
        return 0
    raise SystemExit("review needs --export FILE or --import FILE")


def cmd_listen(args) -> int:
    """Copy the produced clips into one flat folder, named by rank and text, with a playlist:
    the app's per-entry folders are awkward to listen through."""
    clips, entries, _, _, _ = load(args)
    rank = {e["entryId"]: e.get("curriculumRank") or 0 for e in entries}
    out = args.work / "listen"
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)
    names = []
    for clip in sorted(clips, key=lambda c: (rank.get(c.entry_id, 0), c.kind != "word")):
        source = clip.destination(args.content_root)
        if not source.is_file():
            continue
        text = re.sub(r'[/\\:*?"<>|]', "_", clip.spoken_text)[:70].rstrip(" .")
        name = f"{rank.get(clip.entry_id, 0):04d} {'word' if clip.kind == 'word' else 'sentence'} - {text}.m4a"
        shutil.copyfile(source, out / name)
        names.append(name)
    (out / "playlist.m3u").write_text("\n".join(names) + "\n", encoding="utf-8")
    print(f"{len(names)} clips -> {out}")
    print(f"Open it with: open {display(out)}   (or play everything: open {display(out / 'playlist.m3u')})")
    return 0


def cmd_rights(args) -> int:
    """Provenance summary for content/german5000/rights/rights-ledger.json (printed, not written)."""
    ledger = Ledger(args.work / "ledger.jsonl")
    clips = [c for c in ledger.clips.values() if c.get("backend") == "google"]
    voices = sorted({c["voice"] for c in clips})
    statuses = {}
    for c in clips:
        statuses[c.get("reviewStatus", "pending")] = statuses.get(c.get("reviewStatus", "pending"), 0) + 1
    summary = {
        "rightsId": "audio-google-cloud-tts",
        "kind": "synthetic-speech",
        "label": "Synthetic speech generated with Google Cloud Text-to-Speech; not a human recording.",
        "provider": "Google Cloud Text-to-Speech",
        "voices": voices,
        "language": sorted({c["language"] for c in clips}),
        "settings": sorted({json.dumps({k: c[k] for k in ("speakingRate", "sampleRateHertz", "audioEncoding")},
                                       sort_keys=True) for c in clips}),
        "encoding": sorted({json.dumps(c["encoding"], sort_keys=True) for c in clips}),
        "clips": len(clips),
        "reviewStatus": statuses,
        "firstGenerated": min((c["generatedAt"] for c in clips), default=None),
        "lastGenerated": max((c["generatedAt"] for c in clips), default=None),
        "evidence": ["work/tts/ledger.jsonl (archive with the lossless masters)",
                     "https://cloud.google.com/text-to-speech/pricing",
                     "https://docs.cloud.google.com/text-to-speech/quotas"],
    }
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--pack", type=Path, default=inventory.DEFAULT_PACK, help="generated pack.json")
    parser.add_argument("--work", type=Path, default=DEFAULT_WORK, help="masters, ledger and pilot (keep out of git)")
    parser.add_argument("--content-root", type=Path, default=inventory.CONTENT_ROOT,
                        help="where audio/<entryId>/*.m4a is written")
    parser.add_argument("--settings", type=Path, default=DEFAULT_SETTINGS)
    parser.add_argument("--overrides", type=Path, default=inventory.DEFAULT_OVERRIDES)
    parser.add_argument("--pilot-file", type=Path, default=DEFAULT_WORK / "pilot.json")
    sub = parser.add_subparsers(dest="command", required=True)

    def scoped(p):
        p.add_argument("--pilot", action="store_true", help="only the entries in work/tts/pilot.json")
        p.add_argument("--ids", nargs="*", help="only these entry or audio IDs")
        p.add_argument("--audition", action="store_true",
                       help="keep this voice's clips, masters and ledger in work/tts/audition/<voice>, "
                            "apart from the real content, to compare voices side by side")

    def synthesis_options(p):
        p.add_argument("--voice", help="voice name, overriding tts-settings.json (for auditions)")
        p.add_argument("--bitrate", type=int, help="AAC bitrate in kbps")
        p.add_argument("--encoder", choices=["auto", "ffmpeg", "afconvert"])

    p = sub.add_parser("plan", help="dry run: counts, characters, cost and destinations")
    scoped(p)
    synthesis_options(p)
    p.set_defaults(fn=cmd_plan)

    p = sub.add_parser("pilot", help="write the pilot file with a representative selection")
    p.add_argument("--size", type=int, default=100)
    p.set_defaults(fn=cmd_pilot)

    p = sub.add_parser("voices", help="list voices (API call, not billed) to work/tts/voices-<lang>.json")
    p.add_argument("--language", default="de-DE")
    p.add_argument("--backend", choices=["google", "fake"], default="google")
    p.add_argument("--quota-project", help="Google Cloud project billed for the requests")
    p.set_defaults(fn=cmd_voices)

    p = sub.add_parser("produce", help="synthesize, normalize, encode and record")
    scoped(p)
    synthesis_options(p)
    p.add_argument("--backend", choices=["google", "fake"], default="google")
    p.add_argument("--quota-project", help="Google Cloud project billed for the requests")
    p.add_argument("--workers", type=int, default=2, help="concurrent requests (start with 2)")
    p.add_argument("--rpm", type=int, default=120, help="requests per minute, below your project quota")
    p.add_argument("--limit", type=int, help="process at most this many clips")
    p.add_argument("--rerecord-reviewed", action="store_true", help="also replace reviewed clips whose input changed")
    p.add_argument("--yes", action="store_true", help=f"confirm runs over {CONFIRM_ABOVE_CHARS:,} billable characters")
    p.set_defaults(fn=cmd_produce)

    p = sub.add_parser("verify", help="check every destination, codec, duration and checksum")
    scoped(p)
    synthesis_options(p)
    p.set_defaults(fn=cmd_verify)

    p = sub.add_parser("review", help="export a listening sheet, or import its decisions")
    scoped(p)
    p.add_argument("--voice", help="with --audition: which audition to review")
    p.add_argument("--export", type=Path)
    p.add_argument("--import", dest="import_csv", type=Path)
    p.add_argument("--reviewer", default="")
    p.set_defaults(fn=cmd_review)

    p = sub.add_parser("listen", help="copy produced clips into one flat, readable folder with a playlist")
    scoped(p)
    p.add_argument("--voice", help="with --audition: which audition to collect")
    p.set_defaults(fn=cmd_listen)

    p = sub.add_parser("rights", help="print a provenance summary for the rights ledger")
    p.set_defaults(fn=cmd_rights)

    args = parser.parse_args(argv)
    if getattr(args, "audition", False):
        if not getattr(args, "voice", None):
            raise SystemExit("--audition needs --voice")
        args.work = args.content_root = DEFAULT_WORK / "audition" / args.voice
    try:
        return args.fn(args)
    except inventory.InventoryError as exc:
        print(f"Content pack: {exc}", file=sys.stderr)
        return 1
    except FatalSynthesisError as exc:
        print(f"STOPPED: {exc}", file=sys.stderr)
        return 3


if __name__ == "__main__":
    raise SystemExit(main())
