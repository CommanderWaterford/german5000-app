# Google Cloud Text-to-Speech audio production

Project: German5000 Android app  
Corpus version: `0.2.0-corpus-draft`  
Prepared: 28 October 2026

## Required audio

Generate German speech during content production and bundle the finished files with the app. Playback must work offline, including premium entries. Google Cloud credentials and API calls belong in the production workflow; the Android app continues to play packaged files.

| Asset | Current corpus | Full 5,000-entry catalog |
| --- | ---: | ---: |
| Headword recording | 4,010 | 5,000 |
| Single example-sentence recording | 4,010 | 5,000 |
| **Total required files** | **8,020** | **10,000** |

No English recordings, additional example recordings, separate slow recordings, or grammar-form recordings are required for the current scope. Slow playback already uses speed `0.75` with pitch preserved in `AudioPlayer`.

All 8,020 current clips were produced on 28 October 2026 with `de-DE-Wavenet-G` (48 kbps), using [`tools/tts`](../tools/tts/README.md); native review is pending. This document specifies the production workflow; it does not provision a Google Cloud project or generate billable audio.

## 1. Use the app's generated audio manifest

The authoritative synthesis input is each audio record's `spokenText` in the generated content pack. This is produced by `ContentPipeline` using the German grammar renderer. Reading spreadsheet cells or synthesizing raw lemmas would miss noun articles and required reflexive forms.

From the repository root, build the content manifest:

```bash
./gradlew :apps:german5000:buildDebugContentPack
```

Read:

```text
apps/german5000/build/generated/content/debug/content/german5000/pack.json
```

Use the top-level `audio` array. Every record provides `audioId`, `path`, `spokenText`, and `format`. Current examples:

| Audio ID | Exact synthesis text | Final source file |
| --- | --- | --- |
| `de.noun.zeit.01.word` | `die Zeit` | `content/german5000/audio/de.noun.zeit.01/word.m4a` |
| `de.noun.zeit.01.ex1.audio` | `Ich habe heute keine Zeit.` | `content/german5000/audio/de.noun.zeit.01/ex1.m4a` |

The pack path `audio/<entryId>/word.m4a` maps to the repository path `content/german5000/audio/<entryId>/word.m4a`. Apply the same rule to `ex1.m4a`. Derive the destination from the record's path, keeping stable IDs intact.

Snapshot totals, before SSML, pronunciation overrides, retries, or deduplication:

| Input | Characters |
| --- | ---: |
| Headword speech | 38,390 |
| Example speech | 183,135 |
| **Total** | **221,525** |

The longest current input is 129 UTF-8 bytes. Google documents a 5,000-byte synchronous input limit; enforce the current limit when preparing requests. These short clips fit synchronous synthesis. [Google quotas and limits](https://docs.cloud.google.com/text-to-speech/quotas)

Regenerate this inventory after content changes. Freeze the source text before full production to avoid paying to record sentences that will be edited again.

## 2. Google Cloud setup

Install the Google Cloud CLI, Python 3, and FFmpeg on the production machine. Select a Google Cloud project with billing enabled and enable the Cloud Text-to-Speech API. Google requires billing even when usage is covered by an applicable free allowance. [Google setup guide](https://docs.cloud.google.com/text-to-speech/docs/get-started)

Run these commands yourself, replacing the project placeholder:

```bash
export TTS_PROJECT_ID="your-google-cloud-project-id"
gcloud init
gcloud config set project "$TTS_PROJECT_ID"
gcloud services enable texttospeech.googleapis.com --project "$TTS_PROJECT_ID"
gcloud auth application-default login
gcloud auth application-default set-quota-project "$TTS_PROJECT_ID"
```

Use Application Default Credentials for local production. If quota-project access is denied, the account needs the appropriate project permissions, including `serviceusage.services.use`; Google's authentication guide explains the Service Usage Consumer role. Keep credentials outside the repository. For automated production, use an attached service identity or workload identity rather than embedding credentials in the APK. [Authentication guide](https://docs.cloud.google.com/text-to-speech/docs/authentication)

## 3. Select one Standard German voice

Use language code `de-DE` and one consistent voice for both words and sentences. Audition available WaveNet or Neural2 voices as an initial candidate set. Voice choice is a listening decision; record the exact voice name after selection.

List the voices available to the project:

```bash
mkdir -p work/tts
curl --fail-with-body --silent --show-error \
  -H "Authorization: Bearer $(gcloud auth application-default print-access-token)" \
  -H "x-goog-user-project: $TTS_PROJECT_ID" \
  "https://texttospeech.googleapis.com/v1/voices?languageCode=de-DE" \
  -o work/tts/voices-de.json
```

`python3 tools/tts/tts_production.py voices` writes the same list through the client library to `work/tts/voices-de-DE.json`, and `pilot` selects the 100 entries below into `work/tts/pilot.json`. Inspect the list and choose a returned `name`. Record its natural sample rate and verify the chosen voice's supported synthesis settings. [Voice-list documentation](https://docs.cloud.google.com/text-to-speech/docs/list-voices-and-types)

Generate a representative 100-entry pilot, covering nouns with articles, plural-only nouns, umlauts, ß, compounds, separable and reflexive verbs, questions, numbers, abbreviations, and the suffix entry. Review those 200 clips before producing the remaining corpus. Use a neutral, clear delivery at natural speed. Produce the single example sentence exactly once per entry.

## 4. Synthesize a lossless master

For each manifest record, write a UTF-8 JSON request with its exact `spokenText`. Replace the voice placeholder below with the selected name returned by the voice API:

```json
{
  "input": {
    "text": "die Zeit"
  },
  "voice": {
    "languageCode": "de-DE",
    "name": "REPLACE_WITH_SELECTED_VOICE_NAME"
  },
  "audioConfig": {
    "audioEncoding": "LINEAR16"
  }
}
```

Save the example request as `work/tts/request.json`, then:

```bash
curl --fail-with-body --silent --show-error \
  -H "Authorization: Bearer $(gcloud auth application-default print-access-token)" \
  -H "x-goog-user-project: $TTS_PROJECT_ID" \
  -H "Content-Type: application/json; charset=utf-8" \
  --data-binary @work/tts/request.json \
  "https://texttospeech.googleapis.com/v1/text:synthesize" \
  -o work/tts/response.json

python3 -c 'import base64,json; from pathlib import Path; result=json.loads(Path("work/tts/response.json").read_text()); Path("work/tts/master.wav").write_bytes(base64.b64decode(result["audioContent"], validate=True))'
```

The REST response contains base64-encoded audio. With `LINEAR16`, the decoded output includes a WAV header and can be saved directly as `.wav`. Google also offers MP3 and Ogg Opus, but the app's current pipeline expects M4A, so retain WAV masters and encode the delivery format separately. Renaming an MP3 or WAV file to `.m4a` does not convert it. [Synthesis quickstart](https://docs.cloud.google.com/text-to-speech/docs/create-audio-text-command-line), [audio encodings](https://docs.cloud.google.com/text-to-speech/docs/reference/rest/v1/AudioEncoding)

## 5. Encode the app's M4A files

Retain lossless masters in an archive outside the app assets. Deliver mono AAC-LC in an M4A container. Begin the listening pilot at 48 kbps, compare 32 kbps, and select the smallest setting that preserves intelligibility. These settings follow the repository's audio specification in `docs/german5000-android-spec.md`, section 6.

`tools/tts/tts_production.py produce` does this for every clip, after normalization and silence trimming, and falls back to macOS's `afconvert` where FFmpeg is not installed. By hand, for the single word example:

```bash
mkdir -p content/german5000/audio/de.noun.zeit.01
ffmpeg -n -i work/tts/master.wav \
  -vn -ac 1 -c:a aac -profile:a aac_low -b:a 48k \
  -movflags +faststart \
  content/german5000/audio/de.noun.zeit.01/word.m4a
```

Create `ex1.m4a` separately from that entry's sentence master. The command uses `-n` to avoid overwriting an existing recording accidentally. A batch generator should write to a temporary destination and atomically replace the final file only after decoding and metadata checks pass.

Target consistent perceived loudness near −18 LUFS where measurable and a true peak no higher than −1 dBTP. Use a reviewed normalization workflow; the example FFmpeg command above performs encoding only. Check short headwords by ear because loudness meters can be unreliable on very short clips. Remove excessive leading/trailing silence without cutting consonants or final syllables.

Measure total audio bytes and APK size after the 100-entry pilot. The existing specification targets a download of at most 180 MB and an installed footprint of at most 260 MB; do not assume the final corpus fits until measured.

## 6. Batch generator requirements

[`tools/tts/tts_production.py`](../tools/tts/README.md) implements these requirements; its README covers usage, the ledger and overrides. It must:

1. Load `pack.json`, validate safe relative paths, and create exactly the required word/example destinations.
2. Use `spokenText` as the input; keep any reviewed SSML or pronunciation expansion separately alongside the original text. Do not speak the English translations.
3. Support a dry run that prints clip count, character count, estimated cost, chosen voice, and destinations without making synthesis requests.
4. Cache by the hash of text or SSML, voice name, synthesis settings, normalization settings, and encoding settings. Reuse a file only when that fingerprint and its recorded checksum match.
5. Start with two concurrent requests and a configurable requests-per-minute cap below the project's actual quota. Retry only transient failures with bounded exponential backoff; refresh credentials as needed. Stop on persistent authentication, billing, or invalid-input errors.
6. Save a resumable production ledger with `audioId`, source `spokenText`, spoken-text hash, voice, language, settings, generation date, final path, final SHA-256, measured duration, and review status. Never log access tokens.
7. Keep one final asset at every required path, even when identical text can reuse one synthesis result. The current manifest contains 8,011 distinct text strings across 8,020 required files; voice/settings/overrides must also match before deduplication.
8. Preserve reviewed files during reruns and regenerate only missing, changed, or invalid outputs.

All 8,020 clips can be synthesized separately with the synchronous API. No runtime Cloud Storage download, streaming endpoint, or additional app permission is required for this design.

### Cost planning

As checked on 28 October 2026, Google's listed rates after applicable free usage are $4 per million characters for WaveNet, $16 for Neural2, and $30 for Chirp 3 HD. For the current 221,525 plain-text characters, the corresponding baseline is approximately **$0.89**, **$3.54**, or **$6.65**, before allowances, retries, extra auditions, taxes, or other services. This is an estimate, not a charge quote. Recheck the chosen voice's current rate and account usage before generation. [Google pricing](https://cloud.google.com/text-to-speech/pricing)

## 7. Review and integrate

Review pronunciation, stress, articles, reflexive forms, natural sentence delivery, abbreviation/number readings, clipping, silence, and text-to-audio alignment. Have a qualified German reviewer sign off. The corpus's draft text should be reviewed before final recording; structural validation alone does not establish linguistic correctness.

Keep the chosen voice, generation settings, synthetic origin, production/review evidence, and applicable usage terms in `content/german5000/rights/rights-ledger.json`. Label the recordings as synthetic. The `audio-google-tts-pending-review` record holds that evidence for the 28 October 2026 production (it replaced the earlier `audio-unrecorded` record); `tools/tts/tts_production.py rights` prints an updated summary after any rerun. Google's documentation permits use of generated audio in applications subject to its terms; retain the relevant evidence for the release process. [Google usage and limits](https://docs.cloud.google.com/text-to-speech/quotas)

The current pipeline discovers the `.m4a` files and computes SHA-256 checksums automatically. `durationMs` and per-audio `rightsId` currently default to null; keep those in the production ledger until pipeline support is added. The pipeline checks file presence and hashes, but does not verify codecs, duration, loudness, or pronunciation. Add media inspection and listening review to the production checks.

Rebuild and validate from the repository root:

```bash
./gradlew :tools:content-pipeline:run --args="validate $PWD/content/german5000/source pilot"
./gradlew :apps:german5000:assembleDebug
./gradlew jvmTest testDebugUnitTest
```

Verify the rebuilt pack has **8,020 audio records**, a nonempty checksum for each record, and **no missing-audio warnings**. The two existing target-span warnings for the suffix and unspaced measurement examples are unrelated to audio. Check every packaged file with FFprobe or another media inspector and test word, sentence, replay, slow playback, and navigation cancellation in airplane mode.

Adding audio alone does not make this a release-ready pack: the catalog still contains 4,010 entries, and draft content, linguistic review, and rights requirements remain. The production gate continues to require 5,000 approved entries with complete, reviewed audio.

## Completion checklist

- [ ] Exact German voice and synthesis settings recorded.
- [ ] Representative 100-entry / 200-clip pilot reviewed and encoding selected.
- [ ] All 4,010 word files and 4,010 single-sentence files delivered at stable-ID paths.
- [ ] Lossless masters and resumable generation ledger archived.
- [ ] Checksums, duration, codec, loudness, silence, and pronunciation checked.
- [ ] German review and synthetic-audio rights/provenance recorded.
- [ ] Rebuilt APK contains all required audio and plays it offline.
- [ ] Remaining non-audio release requirements tracked separately.
