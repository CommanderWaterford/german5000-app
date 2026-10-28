# Audio production (Google Cloud Text-to-Speech)

`tts_production.py` turns the generated content pack into the app's packaged recordings:
one headword clip and one example-sentence clip per entry, at
`content/german5000/audio/<entryId>/{word,ex1}.m4a`. Why and how: [docs/GOOGLE_CLOUD_TTS.md](../../docs/GOOGLE_CLOUD_TTS.md).
It grew out of the HablaDeutsch generator (`~/german_tts/generate_from_manifest.py`): the same
Google client and dry-run/resume habits, plus lossless masters, loudness normalization,
M4A delivery, a ledger and review tracking.

## Setup

```bash
python3 -m venv .venv-tts && .venv-tts/bin/pip install -r tools/tts/requirements.txt
./gradlew :apps:german5000:buildDebugContentPack     # writes the pack.json the tool reads
```

Credentials are Application Default Credentials (`gcloud auth application-default login`
and `set-quota-project`, see the doc §2); the client refreshes them itself and nothing is
logged. Only `voices` and `produce` touch Google; everything else works offline with the
standard library. Encoding uses FFmpeg, or `afconvert` on a Mac without FFmpeg.

## Workflow

```bash
T="python3 tools/tts/tts_production.py"            # or .venv-tts/bin/python
$T plan                                             # dry run: clips, characters, cost, destinations
$T pilot                                            # work/tts/pilot.json: 100 representative entries
$T voices                                           # work/tts/voices-de-DE.json (not billed)
$T produce --pilot --audition --voice de-DE-Neural2-G   # one audition folder per candidate voice
$T produce --pilot --audition --voice de-DE-Wavenet-B --bitrate 32
```

Each audition lands in `work/tts/audition/<voice>/` (clips, masters and ledger), apart from the
real content. The clips sit in one folder per entry, as the app needs them; for listening,
`listen --pilot --audition --voice <voice>` copies them into `work/tts/audition/<voice>/listen/`
as `0051 word - das Jahr.m4a`, `0051 sentence - Wir fahren jedes Jahr nach Italien.m4a` and so on,
in rank order, with a `playlist.m3u`. Listen, compare bitrates (`--bitrate 32` re-encodes without new requests), and
check sizes with `verify --pilot --audition --voice <voice>`. Then record the chosen voice and
bitrate in `tts-settings.json`, produce the pilot for real and review it:

```bash
$T produce --pilot
$T verify --pilot                                   # codec, duration, checksum; projected total size
$T review --pilot --export work/tts/review.csv      # listening sheet; fill in approved/rejected
$T review --import work/tts/review.csv --reviewer "Name"
```

After sign-off, produce everything:

```bash
$T plan
$T produce --yes        # --yes is required above 20,000 billable characters
$T verify
$T rights               # provenance summary for content/german5000/rights/rights-ledger.json
```

`produce` starts with 2 workers at 120 requests per minute (`--workers`, `--rpm`); keep
the rate below your project's quota. A full run of 8,011 requests takes a little over an
hour at the default rate. Transient errors are retried with bounded backoff; an
authentication, permission, billing or invalid-input error stops the run at once.
Rerunning the same command resumes: finished clips are skipped.

To rehearse without credentials or charges, use the fake backend in an audition folder
(it refuses to write into `content/german5000`):

```bash
$T produce --pilot --audition --voice fake --backend fake
```

## What it keeps, and where

| Path | Contents | In git |
| --- | --- | --- |
| `work/tts/masters/` | Lossless LINEAR16 WAV, one per distinct input, named by fingerprint | No: archive it |
| `work/tts/ledger.jsonl` | Append-only ledger (below) | No: archive it |
| `work/tts/pilot.json`, `review.csv`, `last-run.json` | Pilot selection, listening sheet, last run report | No |
| `tools/tts/tts-settings.json` | Voice, rate, normalization and encoding settings | Yes |
| `content/german5000/audio-overrides.json` | Reviewed pronunciation fixes (text or SSML) | Yes |
| `content/german5000/audio/**.m4a` | The delivered clips | Yes |

**Ledger.** One JSON line per event: `master` (fingerprint, checksum, input, voice), `clip`
(audioId, spokenText and its SHA-256, voice, language, settings, final path, SHA-256, bytes,
duration, loudness, true peak, generation date, review status) and `review` (decision,
reviewer, note). The latest line per key wins.

**Caching.** A master is reused only when its fingerprint (input text or SSML, voice,
language, rate, sample rate) and its recorded checksum match, so identical sentences are
synthesized once. A delivered clip is skipped when its fingerprint (master plus
normalization and encoding settings) and checksum match. Changing the bitrate therefore
re-encodes from the masters without new requests. A changed text gets a new recording and
goes back to `pending` review. A clip already approved by review is not replaced unless you
pass `--rerecord-reviewed`.

**Processing.** Each master is trimmed of excess leading/trailing silence (keeping 100 ms
before and 150 ms after the voice), normalized to −18 LUFS (ITU-R BS.1770), with its gain
capped so the true peak stays at or below −1 dBTP, and encoded as mono AAC-LC
(48 kbps by default). A clip is moved into place only after the M4A has been read back
and checked: AAC-LC, mono, and a duration within 150 ms of the processed audio.
Clips under 400 ms are flagged `shortClip` in the ledger: meters are unreliable there, so
listen to them.

**Overrides.** For a word the voice misreads (the suffix entry "-jährig" is a likely one),
add a reviewed fix to `content/german5000/audio-overrides.json`:

```json
{ "de.corpus.5a9e317435242471.word": { "forText": "-jährig", "ssml": "<speak>jährig</speak>", "reason": "leading hyphen read as minus" } }
```

`forText` must equal the pack's current `spokenText`; when the text changes, the override
is reported as stale instead of being applied. Use the real audio ID from `plan` or the pack.

## Tests

```bash
python3 -m unittest discover -s tools/tts -p "test_*.py"
```

They use the fake backend and a temporary folder; encoding tests run where FFmpeg or
afconvert is installed.
