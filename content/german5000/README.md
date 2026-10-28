# German5000 content

```
source/
  pack-settings.json   pack ID, content version, free limit, band size, rank source
  curriculum.txt       ordered entry IDs → curriculumRank (1..N)
  entries.json         editorial entries (lemma, POS, grammar, senses, examples, status, rights)
  pilot-entries.json   original 50 enriched entries; stable identities retained on reimport
  corpus-enrichment.json  cached draft translations and dictionary grammar, tied to workbook SHA-256
  corpus-import.json   source checksum, source ranks, selected sentences and entry-ID mapping
rights/
  rights-ledger.json   rights records referenced by entries' rightsIds (spec §5.4)
audio/                 (not yet present) recordings at audio/<entryId>/word.m4a and ex<N>.m4a
```

The app never reads these files directly. `tools/content-pipeline` turns them into
`content/german5000/pack.json` inside the APK, assigning:

- `curriculumRank` from the order in `curriculum.txt` (ranks are positions, never identities);
- stable IDs `<entryId>.s<n>` for senses, `<entryId>.ex<n>` for examples,
  `<entryId>.word` / `<exampleId>.audio` for audio;
- code point target spans from each example's `target` tokens (whole-word match; separable
  verbs list both parts, e.g. `["fängt", "an"]`);
- keyboard alternatives (`schön → schoen`) unless a sense sets `keyboardAlternatives`
  explicitly (use `[]` to suppress), skipping any that collide with another lemma.

Validate locally:

```bash
./gradlew :tools:content-pipeline:run --args="validate $PWD/content/german5000/source pilot"
./gradlew :tools:content-pipeline:run --args="validate $PWD/content/german5000/source release"
```

## Current state: 4,010-entry draft corpus

The supplied `german_words.xlsx` contributes all 4,010 ranked rows. The free range and
rank bands are 1,000 entries wide. All original 50 pilot IDs, definitions, grammar and
bilingual examples are preserved at their corresponding workbook ranks. Other entries
carry `corpusDraft: true`, automated English translations and grammar derived from the
[Kaikki German Wiktextract export](https://kaikki.org/dictionary/German/) of English
Wiktionary (2026-10-25 extraction, 2026-10-01 dump). The export is used for inflection
facts and occasional short glosses; per-entry Wiktionary attribution is retained in notes.
Manual draft grammar fills common preposition cases, abbreviations and plural-only or
uncountable headwords, and identifies inflected comparison/participle forms.

Each entry contains **exactly one example sentence**. The importer chooses a complete
source sentence containing a literal target when possible, preserving abbreviations,
initials and ordinal dates. The one missing source example (Aktiengesellschaft) has an
explicitly marked AI draft. Existing pilot examples retain their correct translations.
Clear spelling/OCR errors are corrected with original spellings retained as search aliases;
the second source `Welle` is `Weile` in its supplied example. No source row is discarded.

The workbook ranking is preserved as supplied; its underlying frequency source is not
yet established. All entries remain `draft`, with recordings and rights review pending.
Unreviewed corpus entries are excluded from placement assessment. Grammar fields are
structurally validated for every part of speech. Pilot validation permits absent target
annotations with warnings for the suffix and unspaced measurement examples. Cloze practice is
available only when a target can be annotated safely. Release validation still rejects
these drafts and requires the full 5,000 reviewed entries, complete grammar, targets and audio.

Reimport the same workbook (offline, standard-library Python):

```bash
python3 tools/content-pipeline/import_corpus.py /path/to/german_words.xlsx
python3 -m unittest discover -s tools/content-pipeline -p 'test_*.py'
```

Reimports are deterministic. Corpus IDs use the original headword and its occurrence
number, never its rank. Cached enrichment is checked against the workbook checksum.
To regenerate enrichment, `enrich_corpus.py` accepts the workbook, a JSON map of matching
Wiktextract records, a JSON map of source strings to English machine translations, and
the output path. The large dictionary download and transient translation cache are not
required to build the app; all final editorial data is checked in.

## Grammar fields (German)

| POS | Required keys |
| --- | --- |
| NOUN | `gender` (masculine/feminine/neuter/plural_only), `article`, `singular`, `plural`, `pluralStatus` (has_plural/no_plural/plural_only), `weakNoun` |
| VERB | `present3sg`, `preterite`, `participle2`, `auxiliary` (haben/sein/haben\|sein), `separablePrefix` (prefix or not_applicable), `reflexive` (no/required/optional); optional `valency` |
| ADJECTIVE | `gradable`, `comparative`, `superlative`; optional `spellingNote` |
| PREPOSITION | `governedCases` (accusative/dative/genitive/accusative\|dative) |
| PRONOUN, DETERMINER, ARTICLE | `forms` |

Use `not_applicable` to state a grammatical fact rather than leaving a field blank.

## Editorial workflow (spec §5.4)

`draft → linguistic_review → audio_review → approved → published`. Every entry needs two
qualified German reviewers and cleared rights before it may be `approved`. Record reviewers and
evidence in the rights ledger / editorial tracker; a successful validation run is necessary but
not sufficient.
