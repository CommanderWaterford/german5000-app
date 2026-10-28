"""What to record: the audio records of the generated content pack, validated.

The pack's `spokenText` is the only synthesis input (it carries noun articles and
reflexive forms); `path` decides the destination. See docs/GOOGLE_CLOUD_TTS.md §1.
"""
from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
DEFAULT_PACK = REPO / "apps/german5000/build/generated/content/debug/content/german5000/pack.json"
CONTENT_ROOT = REPO / "content/german5000"
DEFAULT_OVERRIDES = CONTENT_ROOT / "audio-overrides.json"

# Google's synchronous synthesis input limit, in UTF-8 bytes (text or SSML).
MAX_INPUT_BYTES = 5000

# The two recordings every entry needs; nothing else is produced (§ Required audio).
_PATH = re.compile(r"^audio/([A-Za-z0-9][A-Za-z0-9._-]*)/(word|ex1)\.m4a$")
_KINDS = {"word": "{entry}.word", "ex1": "{entry}.ex1.audio"}

# US$ per million characters after free usage, as listed by Google on 28 October 2026.
# Recheck https://cloud.google.com/text-to-speech/pricing before generating.
PRICE_PER_MILLION = {"WaveNet": 4.0, "Neural2": 16.0, "Chirp3-HD": 30.0}
PRICES_CHECKED = "2026-10-28"


class InventoryError(Exception):
    def __init__(self, problems: list[str]):
        super().__init__(f"{len(problems)} problem(s):\n  " + "\n  ".join(problems[:20]))
        self.problems = problems


@dataclass(frozen=True)
class Clip:
    audio_id: str
    entry_id: str
    kind: str  # "word" or "ex1"
    spoken_text: str
    pack_path: str  # audio/<entryId>/<kind>.m4a

    def destination(self, content_root: Path = CONTENT_ROOT) -> Path:
        return content_root / self.pack_path


@dataclass(frozen=True)
class Override:
    """A reviewed pronunciation fix, kept beside the text it was written for."""
    for_text: str
    text: str | None = None
    ssml: str | None = None
    reason: str = ""


@dataclass(frozen=True)
class SynthesisInput:
    value: str
    is_ssml: bool

    @property
    def billed_characters(self) -> int:
        return len(self.value)


def load_pack(path: Path = DEFAULT_PACK) -> tuple[list[Clip], list[dict]]:
    """The pack's clips (validated) and entries."""
    if not path.is_file():
        raise InventoryError([f"{path} not found: run ./gradlew :apps:german5000:buildDebugContentPack"])
    pack = json.loads(path.read_text(encoding="utf-8"))
    return validate(pack.get("audio", []), pack.get("entries", [])), pack.get("entries", [])


def validate(records: list[dict], entries: list[dict]) -> list[Clip]:
    problems, clips, seen_ids, seen_paths = [], [], set(), set()
    for record in records:
        audio_id, path, text = record.get("audioId", ""), record.get("path", ""), record.get("spokenText") or ""
        match = _PATH.match(path)
        if not match:
            problems.append(f"{audio_id}: unsafe or unexpected path {path!r}")
            continue
        entry_id, kind = match.groups()
        if audio_id != _KINDS[kind].format(entry=entry_id):
            problems.append(f"{audio_id}: does not match its path {path}")
        if record.get("format") != "m4a":
            problems.append(f"{audio_id}: format {record.get('format')!r}, expected m4a")
        if not text.strip():
            problems.append(f"{audio_id}: empty spokenText")
        elif len(text.encode("utf-8")) > MAX_INPUT_BYTES:
            problems.append(f"{audio_id}: spokenText exceeds {MAX_INPUT_BYTES} bytes")
        if audio_id in seen_ids or path in seen_paths:
            problems.append(f"{audio_id}: duplicate audio ID or path")
        seen_ids.add(audio_id)
        seen_paths.add(path)
        clips.append(Clip(audio_id, entry_id, kind, text, path))
    for entry in entries:
        for kind in _KINDS:
            if f"audio/{entry['entryId']}/{kind}.m4a" not in seen_paths:
                problems.append(f"{entry['entryId']}: no {kind} audio record")
    if problems:
        raise InventoryError(problems)
    return clips


def load_overrides(path: Path = DEFAULT_OVERRIDES) -> dict[str, Override]:
    if not path.is_file():
        return {}
    raw = json.loads(path.read_text(encoding="utf-8"))
    overrides = {}
    for audio_id, item in raw.items():
        if audio_id.startswith("_"):  # comments
            continue
        override = Override(item["forText"], item.get("text"), item.get("ssml"), item.get("reason", ""))
        if (override.text is None) == (override.ssml is None):
            raise InventoryError([f"{audio_id}: an override needs exactly one of 'text' or 'ssml'"])
        overrides[audio_id] = override
    return overrides


def synthesis_input(clip: Clip, overrides: dict[str, Override]) -> SynthesisInput:
    """The text or SSML to send. An override applies only while the pack text it was
    written for is unchanged; a stale one is an error, not silently ignored."""
    override = overrides.get(clip.audio_id)
    if override is None:
        return SynthesisInput(clip.spoken_text, False)
    if override.for_text != clip.spoken_text:
        raise InventoryError([f"{clip.audio_id}: override was written for {override.for_text!r}, "
                              f"but the text is now {clip.spoken_text!r}; review it"])
    value = override.ssml if override.ssml is not None else override.text
    if len(value.encode("utf-8")) > MAX_INPUT_BYTES:
        raise InventoryError([f"{clip.audio_id}: override exceeds {MAX_INPUT_BYTES} bytes"])
    return SynthesisInput(value, override.ssml is not None)


def voice_family(voice: str | None) -> str | None:
    if not voice:
        return None
    lowered = voice.lower()
    for family in PRICE_PER_MILLION:
        if family.lower() in lowered:
            return family
    return None


def estimate_cost(characters: int, voice: str | None) -> float | None:
    family = voice_family(voice)
    return None if family is None else characters * PRICE_PER_MILLION[family] / 1_000_000


# --- Pilot ---------------------------------------------------------------------------

# (reason, how many entries, test). The pilot covers every case §3 asks a listener to hear.
def _categories(text_of):
    def g(e, key):
        return (e.get("grammar") or {}).get(key)

    def example(e):
        return text_of(e, "ex1")

    return [
        ("suffix entry", 1, lambda e: e["pos"] == "SUFFIX"),
        ("plural-only noun", 4, lambda e: g(e, "pluralStatus") == "plural_only"),
        ("reflexive verb (required)", 4, lambda e: g(e, "reflexive") == "required"),
        ("reflexive verb (optional)", 3, lambda e: g(e, "reflexive") == "optional"),
        ("separable verb", 8, lambda e: g(e, "separablePrefix") not in (None, "not_applicable")),
        ("number in sentence", 8, lambda e: re.search(r"\d", example(e)) is not None),
        ("abbreviation", 6, lambda e: re.search(r"\b[A-ZÄÖÜ]{2,}\b|\b[a-zA-Z]\.\s?[a-zA-Z]\.", example(e)) is not None),
        ("question", 8, lambda e: example(e).rstrip().endswith("?")),
        ("ß", 6, lambda e: "ß" in text_of(e, "word")),
        ("umlaut", 8, lambda e: re.search("[äöüÄÖÜ]", text_of(e, "word")) is not None),
        ("compound noun", 8, lambda e: e["pos"] == "NOUN" and len(e["lemma"]) >= 14),
        ("noun with article", 12, lambda e: e["pos"] == "NOUN" and g(e, "pluralStatus") == "has_plural"),
    ]


def select_pilot(clips: list[Clip], entries: list[dict], size: int = 100) -> list[dict]:
    """A deterministic, representative pilot: each category's quota spread across the
    rank range, then other parts of speech spread evenly to fill [size]."""
    by_key = {(c.entry_id, c.kind): c.spoken_text for c in clips}

    def text_of(entry, kind):
        return by_key[(entry["entryId"], kind)]

    ranked = sorted(entries, key=lambda e: e.get("curriculumRank", 0))
    categories = _categories(text_of)
    chosen: dict[str, dict] = {}

    def add(entry, reason):
        item = chosen.setdefault(entry["entryId"], {
            "entryId": entry["entryId"], "rank": entry.get("curriculumRank"), "pos": entry["pos"],
            "word": text_of(entry, "word"), "example": text_of(entry, "ex1"), "reasons": []})
        if reason not in item["reasons"]:
            item["reasons"].append(reason)

    for reason, quota, test in categories:
        matching = [e for e in ranked if test(e)]
        for entry in _spread(matching, quota):
            if len(chosen) >= size and entry["entryId"] not in chosen:
                break
            add(entry, reason)
    for entry in _spread([e for e in ranked if e["entryId"] not in chosen], max(0, size - len(chosen))):
        add(entry, f"spread ({entry['pos'].lower()})")
    # Record every category an entry happens to cover, not only the one that picked it.
    for item in chosen.values():
        entry = next(e for e in entries if e["entryId"] == item["entryId"])
        for reason, _, test in categories:
            if test(entry) and reason not in item["reasons"]:
                item["reasons"].append(reason)
    return sorted(chosen.values(), key=lambda item: item["rank"] or 0)


def _spread(items: list, count: int) -> list:
    if count <= 0 or not items:
        return []
    if count >= len(items):
        return list(items)
    step = len(items) / count
    return [items[int(i * step)] for i in range(count)]
