# German5000 content

```
source/
  pack-settings.json   pack ID, content version, free limit, band size, rank source
  curriculum.txt       ordered entry IDs → curriculumRank (1..N)
  entries.json         editorial entries (lemma, POS, grammar, senses, examples, status, rights)
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

## Current state: pilot only

The 50 entries here are an **engineering pilot**: drafted with AI assistance, `draft` status,
placeholder ranks, no recordings and rights marked `pending`. They exist to exercise the app and
pipeline. They are not reviewed German and must not ship; release validation rejects them.

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
