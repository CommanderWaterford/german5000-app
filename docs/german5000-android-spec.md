# German5000 — Android Product and Engineering Specification

**Version:** 1.0  
**Prepared:** 27 October 2026  
**Status:** Implementation baseline with explicit product defaults and release gates  
**First product:** German5000  
**Product family:** Separate branded vocabulary apps built from a shared codebase  
**Primary platform:** Android phones and tablets  
**Document audience:** Product owner, Android engineers, content editors, designer, QA, and release owner

> This file is the specification the code in this repository implements. Section numbers
> (§) referenced in code comments point here. Implementation status per requirement is
> tracked in [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md).

## 1. Purpose, provenance, and decision rules

Build an approachable German vocabulary app that helps learners acquire and retain 5,000 useful, frequency-ranked German vocabulary entries through context, pronunciation, recall practice, and spaced repetition. The app must provide a useful experience from the first launch without internet access or an app account. Learning history belongs to the user and is stored locally.

This specification continues the conversation titled **Apps for 1000 English Words**. The retrievable conversation confirms interest in a German5000 app and a request for an Android development document, but the earlier assistant responses are represented by inaccessible content-reference placeholders. Consequently, this document treats the explicit requirements in the current request as authoritative and does not claim to reconstruct those missing responses.

### 1.1 Confirmed requirements

- Separate branded language-learning apps sharing one maintained codebase, with German5000 first.
- Android, offline-first learning, local progress, no mandatory signup, optional web sync later.
- A catalog of 5,000 frequency-ranked German vocabulary entries.
- German grammar information, contextual examples, quality audio, assessment, SRS, quizzes, reminders, and progress.
- A free/premium model, privacy protections, and readiness for Google Play distribution.
- A detailed specification that can be used to implement and test the product.

### 1.2 Defaults introduced by this specification

The following are proposed implementation decisions, not recovered conversation commitments. They form the baseline unless the product owner changes them before implementation:

| Decision | Baseline |
| --- | --- |
| Launch learner language | English UI and English meanings; German learning content |
| German variety | Standard German, primarily Germany; clearly label regional alternatives |
| Audience | Independent adult learners and older teens; not specifically designed for children |
| Platform minimum | Android 8.0 / API 26; verify commercial device coverage before freezing |
| Stack | Kotlin, Jetpack Compose, Room, DataStore, WorkManager |
| Free tier | Entries ranked 1–1,000, including their core learning tools |
| Premium | One-time, non-consumable unlock of entries 1,001–5,000 and advanced practice filters |
| Ads and subscription | Neither in v1 |
| New-entry goal | 10/day by default; choices 5, 10, 15, 20; custom 0–30 |
| SRS | Deterministic, explicitly versioned scheduler defined in section 8 |
| Initial audio | All required word and primary-example recordings delivered with the app |
| Initial service footprint | No learning backend, account, analytics SDK, or cloud sync |
| Price | Set in Play Console after market testing; never hard-code a price in the app |

**Normative language:** MUST is a release requirement; SHOULD is a preferred implementation that may be changed with a recorded reason; MAY is optional. P0 means required for the first public release. P1 means a subsequent enhancement. A future feature must not silently become a v1 dependency.

### 1.3 Product boundaries

German5000 teaches vocabulary and the grammar necessary to use it. It is not a complete grammar course or a certification exam. Frequency order is a curriculum aid, not proof of a particular CEFR level. Do not promise fluency, an exact percentage of German comprehension, or a CEFR qualification from completing the app.

Excluded from v1: mandatory accounts, social feeds, leaderboards, teacher dashboards, user-generated courses, AI chat, speech recognition or pronunciation scoring, live translation, web learning, subscription billing, and cross-app purchase sharing.

## 2. Product family and shared codebase

### 2.1 App identity

Each language app has its own app name, icon, Android application ID, store listing, product IDs, content, and local data sandbox. Production package names require an owner-controlled namespace; examples in engineering fixtures must not become final identifiers by accident.

Implement one repository with Gradle product flavors or small app-shell modules. Shared code must include the navigation shell, sessions, SRS, persistence, billing abstraction, audio playback, accessibility, reminders, import/export, and tests. Language packs supply lexical data, grammar renderers, answer rules, theme tokens, and editorial metadata.

```text
apps/german5000/             # manifest, app identity, dependency wiring
core/model/                 # language-neutral IDs and domain models
core/database/              # Room schemas, repositories, migrations
core/learning/              # queue policy, scheduling, answer evaluation
core/designsystem/          # Compose components, theme, accessibility
core/audio/                 # packaged media lookup and playback
core/billing/               # Play adapter and entitlement repository
core/content/               # manifests, validation, versioning
core/settings/              # preferences, backup, reminders
features/onboarding/
features/today/
features/study/
features/library/
features/progress/
features/premium/
languages/german/           # German grammar and answer rules
content/german5000/         # edited sources and generated release pack
tools/content-pipeline/     # import, validation, QA reports
```

Depend on a `LanguagePack` interface exposing `languageId`, `contentVersion`, `grammarRenderer`, `answerNormalizer`, `exerciseProvider`, and media lookup. The scheduler must not contain German-specific gender, case, or inflection logic. A second language pack must compile using the shared learning engine without duplicating feature screens. A tiny synthetic pack in tests can demonstrate this; a second public app is not part of v1 delivery.

### 2.2 Store policy gate for later apps

Google Play restricts repetitive apps with highly similar experiences. Separate language branding is the requested product strategy, but it does not guarantee approval. Each future app must justify substantial, language-specific educational value, original content, grammar, and exercises; avoid launching cosmetic copies or small overlapping packs. Review the actual portfolio against the policy before every new listing. If approval requires consolidation, record a product decision before changing the distribution strategy. [S8]

## 3. User journeys and navigation

### 3.1 First launch

1. Show the German5000 value proposition: “Learn useful German words with examples and spaced review.”
2. Offer **Start from the beginning** or **Check what I know**. Assessment is skippable.
3. Select daily new-entry goal; explain that reviews are additional and can be completed in short sessions.
4. Offer reminders with a local time selector. Request system notification permission only after the user opts in.
5. Explain “Progress is stored on this device. Export a backup to keep a copy.”
6. Open Today. No login, billing screen, permission, remote configuration, or network check may block this path.

Persist onboarding after every completed step. Back navigation must be predictable. Skipping assessment must not produce a warning badge or a permanently incomplete setup state.

### 3.2 Main destinations

| Destination | Required content and actions |
| --- | --- |
| Today | Due reviews, new-entry allowance, daily activity, Continue session, change goal |
| Study | Review queue, learn new entries, practice modes, assessment entry point |
| Library | Search, rank bands, part of speech, studied state, favorites, locked-state explanation |
| Progress | Studied/mastered counts, retention proxy, activity calendar, streak, skill breakdown |
| Settings | Reminders, audio, display, backup/import, reset, premium restore, privacy, licenses, help |

On tablets use a navigation rail and readable two-pane library/detail layout where space permits. Preserve semantic navigation and focus order at all sizes.

### 3.3 Daily learning loop

Today → due reviews → new vocabulary if allowance remains → short session summary → optional additional practice. Never force all overdue work to finish before allowing a small new lesson; warn when adding new entries would grow a large backlog and let the user choose.

Sessions default to at most 20 graded prompts. The user may stop after any prompt. A committed answer must survive process death, reboot, and screen rotation. A restored session must show the next unanswered prompt and the correct remaining count.

### 3.4 Vocabulary detail

Show lemma, article where applicable, frequency rank, part of speech, primary meaning, other selected senses, German grammar, primary example and translation, word/example playback, favorite, and study status. Optional notes must remain visually secondary. Reveal definitions during browsing freely; browsing never changes SRS state.

Show German nouns with article and plural together, for example `der Tisch · die Tische`. Show verbs with useful forms, auxiliary, and separability where relevant. Display regional labels and register warnings as text, not color alone.

## 4. Functional requirement inventory

| ID | Priority | Requirement |
| --- | --- | --- |
| FR-01 | P0 | Complete first use and a free lesson in airplane mode after installation |
| FR-02 | P0 | Browse a validated catalog of exactly 5,000 canonical entries |
| FR-03 | P0 | Keep learning progress, sessions, preferences, and favorites locally |
| FR-04 | P0 | Support skippable placement assessment with uncertainty-aware feedback |
| FR-05 | P0 | Schedule due review and new learning using the defined SRS |
| FR-06 | P0 | Provide recognition, typed recall, listening, cloze, and German grammar practice |
| FR-07 | P0 | Play word and primary-example audio offline for entitled entries |
| FR-08 | P0 | Resume interrupted sessions without duplicating review events |
| FR-09 | P0 | Provide opt-in reminders with correct timezone and permission behavior |
| FR-10 | P0 | Show comprehensible progress with documented metric definitions |
| FR-11 | P0 | Offer purchase, pending state, offline entitlement, and restore flows |
| FR-12 | P0 | Export/import progress and offer confirmed local deletion |
| FR-13 | P0 | Support TalkBack, large text, dark mode, and non-audio alternatives |
| FR-14 | P0 | Apply content and database upgrades without losing progress |
| FR-15 | P1 | Optional account-based sync and web companion without changing local-first learning |

## 5. Vocabulary scope, frequency, and editorial production

### 5.1 What counts as one of the 5,000

An entry is one teachable **lemma plus part of speech**, represented by one stable `entryId`. Inflected forms, plural forms, alternate spellings, and multiple senses of the same lemma/POS do not inflate the count. Homographs with different parts of speech may be separate entries, with explicit disambiguation. Separable verbs are entries under their infinitive; their separated surface forms are searchable aliases.

Articles and other high-frequency function words remain in scope. Multiword expressions are supporting material in v1 and do not count toward 5,000. Select a single default lexical analysis for each item; preserve exceptions in editorial notes rather than introducing accidental duplicates.

`curriculumRank` is unique and contiguous from 1 to 5,000. It is a curated frequency-based rank, not a claim that every source ranks the same vocabulary identically. Preserve `sourceRank`, counts/scores, corpus provenance, and curation reasons separately. Never use rank as a persistent identity.

### 5.2 Frequency methodology

1. Evaluate a licensed German frequency source with documented corpus composition, date, sample size, wordform/lemma treatment, and commercial redistribution permissions.
2. Prefer broad contemporary usage; document written/spoken balance and regional or genre bias. If using multiple sources, specify the exact weighting in a versioned build recipe.
3. Normalize Unicode, tokenize and lemmatize with a pinned toolchain, map POS, and aggregate counts only under documented rules.
4. Remove extraction artifacts, malformed tokens, unnecessary proper names, and unusable fragments. Do not silently remove pedagogically awkward but common function words.
5. Have German editors resolve homographs, separable verbs, compounds, register, and common senses.
6. Select the first 5,000 eligible lemma/POS items, preserving an exclusions ledger and tie-break order: descending frequency score, normalized lemma, then POS.
7. Store raw source references and reproducible transformation metadata. The public methodology explains curation in plain language.

Leipzig Corpora Collection is a candidate for evaluation, not an approved content dependency. Its documentation distinguishes frequency-ranked word forms and optional baseform/POS data; downloading a wordform list alone does not produce a ready-to-teach lemma curriculum. Dataset-specific licensing remains a procurement gate. [S10]

### 5.3 Required fields and German grammar

| Content | Required rules |
| --- | --- |
| Identity | Immutable ID, lemma, POS, rank, language, content revision |
| Meanings | One concise primary English sense; up to three secondary senses when useful |
| Nouns | Gender/article, nominative singular, plural or explicit no-plural/plural-only status; weak-noun flag where relevant |
| Verbs | Infinitive, third-person present, preterite, participle II, perfect auxiliary/allowed auxiliaries, separable prefix if any, reflexive use and case/valency notes where relevant |
| Adjectives | Comparative/superlative or explicit non-gradable status; spelling/stem irregularities |
| Prepositions | Governed case(s); distinguish two-way location/direction usage with reviewed examples |
| Pronouns/determiners | Relevant gender/case/number forms or a linked mini-table |
| Examples | Exactly one original or explicitly licensed natural German sentence per entry, English translation, sense association, target span, and example audio |
| Pronunciation | Reviewed word audio; IPA optional but, when present, must be checked |
| Editorial metadata | Source/license IDs, reviewers, status, version, register/region, optional editorial CEFR tag |

An explicit value such as `not_applicable` or `no_plural` distinguishes a grammatical fact from a missing field. CEFR tags are editorial aids and must not be mechanically inferred from frequency rank.

### 5.4 Quality and rights workflow

Every shipped entry must pass automated validation and a named native or professionally qualified German editorial review. A second reviewer checks German grammar, primary sense, example naturalness, translation, and audio alignment for every entry. Disagreements block publication until resolved. AI-assisted drafting is permitted only with human review and a recorded rights assessment; runtime learning must not require generative services.

Maintain a rights ledger for frequency data, definitions, examples, translations, voices, recordings, fonts, and third-party code. Record owner, license text/version, commercial-use permission, redistribution and derivative rights, attribution, territory/expiry, and evidence location. A public webpage or downloadable corpus is not permission to repackage its contents. Commissioned content agreements must cover app distribution and updates. Do not copy dictionary definitions or competitor lessons without permission.

Publication states: `draft → linguistic_review → audio_review → approved → published`. A build can include only approved records. Keep the full 5,000-entry editorial pipeline separate from UI engineering; a working application with placeholder content is not launch-ready.

## 6. Audio specification

### 6.1 Required assets

Provide one headword recording and one recording of the primary example for each entry: at least 10,000 reviewed clips. For nouns, the headword recording includes article plus singular, or the appropriate plural-only form. Additional noun plural or verb-form recordings are P1.

Use a consistent, intelligible Standard German voice or small documented voice set. Human recordings are preferred; premium-quality generated speech is acceptable if commercial distribution rights and native review are established. Do not describe synthetic recordings as native human recordings. Runtime Android TTS may be an optional fallback but must not be the only offline audio source.

### 6.2 Production and playback

- Retain lossless masters; distribute mono AAC-LC in M4A at a tested quality setting, initially 32–48 kbps. Choose the final bitrate from listening tests rather than file size alone.
- Target consistent perceived loudness, approximately −18 LUFS for a batch where measurable, with true peak at or below −1 dBTP. Short clips require perceptual checks in addition to meters.
- Reject clipping, missing syllables, wrong stress, inconsistent pronunciation, excessive silence, and spoken text mismatches.
- Map assets by stable audio ID and checksum; never derive filenames from user-visible lemma strings.
- Include all required audio in the installed release, including premium assets, so a verified purchase does not require a separate content download. Local packaging is not a DRM guarantee.
- Playback must be immediate, cancel previous clips, respect audio focus, avoid unexpected autoplay, and stop on navigation/session exit. Offer replay and an optional slower speed with pitch preservation.
- Listening prompts must not display their answer before submission; TalkBack labels must describe controls without exposing hidden answer text.

The asset budget must be measured on a representative 100-entry pilot before full recording. At 32 kbps, 5,000 pairs averaging 5.5 seconds total are roughly 110 MB before packaging overhead; this is a planning estimate, not a delivery guarantee. Target a per-device download of at most 180 MB and installed footprint at most 260 MB before user history. If audio exceeds budget, improve encoding within quality acceptance or formally revise delivery; do not quietly substitute streaming and still advertise complete offline audio.

## 7. Assessment and answer evaluation

### 7.1 Placement assessment

Assessment estimates a starting point and does not award mastery or silently mark thousands of words learned. It uses reviewed items sampled across five bands: 1–1,000; 1,001–2,000; 2,001–3,000; 3,001–4,000; 4,001–5,000.

Use 30 items: four recognition and two typed German production items per band, randomizing within a curated equivalent-difficulty pool. Keep a deterministic seed for resumability. Include **I don't know**, no timer, and visible progress. Give feedback after completion to reduce answer leakage. All users can take the full assessment; this limited exposure does not unlock premium lessons.

Assign recognition correctness 1/0 and typed correctness 1/0; no partial credit in placement. For each band, compute `(recognition_correct + 2 × production_correct) / 8`. A band is provisionally familiar only when score ≥ 0.75 and at least one production item is correct. Recommend the first band that is not provisionally familiar; if all qualify, recommend band 5 with a suggestion to review earlier vocabulary.

Show “Suggested starting band” with an explicit small-sample caveat, not an exact vocabulary-size estimate or CEFR score. Offer beginning at rank 1 or selecting another unlocked band. Earlier entries remain unstudied and available. Free users whose suggested band is premium see a clear explanation and can begin free study without purchasing. Assessment answers are stored separately and do not affect review accuracy, streaks, SRS, or mastery counts.

### 7.2 Exercise modes

| Mode | Prompt and answer | Scheduled behavior |
| --- | --- | --- |
| Recognition | German lemma → choose English meaning | Supported as practice; not used for mature productive SRS |
| Typed recall | English sense + disambiguating context/POS → German lemma | Default scheduled recall mode |
| Listening | Audio → type German word, or choose meaning in practice | Scheduled listening can be enabled; stored on a separate card |
| Cloze | German sentence with target removed + English cue → target form | Practice in v1; requires explicit accepted inflections |
| Grammar | Article, plural, auxiliary, separability, or governed case | Practice in v1; no independent scheduling in baseline |
| Flashcard | Recall mentally, reveal, self-grade | Alternative accessible scheduled mode |

Distractors must share an appropriate POS/context, avoid multiple correct answers, avoid duplicates after normalization, and come from reviewed pools. Store a set of accepted answers per prompt/sense rather than assuming the primary translation is the only valid answer. If no safe distractors exist, use flashcard practice.

### 7.3 Normalization and feedback

- Normalize Unicode to NFC, trim leading/trailing spaces, and collapse repeated internal spaces.
- Preserve German umlauts and `ß` in canonical storage. Accept `ae/oe/ue` and `ss` substitutions as “accepted keyboard alternative” only when explicitly listed on that prompt; do not globally collapse ambiguous words such as `schon/schön` or `Masse/Maße`.
- In vocabulary recall, case-only errors receive corrective feedback but may count correct; explicit noun-capitalization exercises enforce capitalization.
- German article exercises require the correct article; general lemma prompts do not require an article unless the prompt says so. A correct article may be accepted when included and validated.
- Accept approved reflexive/separable forms only for the requested prompt. Sentence cloze answers require the correct contextual inflection.
- A near typo produces a suggestion but no automatic success. Allow **My answer was correct** to override a false negative, recording an override reason locally and capping that result at Good.
- Always show the canonical spelling and a short explanation after an error. Offer umlaut/ß input buttons without replacing the system keyboard.

## 8. Spaced repetition and session engine

### 8.1 Cards and learning states

An entry has a required productive card and an optional listening card. Each card is scheduled independently. The productive card is the only card used for the headline mastered-entry metric. Enabling listening adds cards for already introduced entries without counting them as newly introduced entries.

States: `new`, `learning`, `review`, `relearning`, `suspended`. User-declared familiar words remain unstudied until introduced; they do not become mastered. Favorites are orthogonal to scheduling. Premium lock is an entitlement condition, not an SRS state.

Implement the following deterministic scheduler as `g5000_srs_v1`. It is an initial product algorithm inspired by conventional spaced-review scheduling, not a claim of optimal retention or an implementation of FSRS/SM-2. Keep a pure scheduling function so a later algorithm can be introduced with explicit migration and comparative testing.

### 8.2 Rating and objective result mapping

After reveal, flashcards offer **Again**, **Hard**, **Good**, and **Easy** with plain explanations. For typed/listening scheduled questions:

- Wrong, skipped, or revealed before answering → Again. Showing the answer prevents a success rating for that attempt.
- Correct without hints → Good by default; the learner may choose Hard or Easy based on effort.
- Correct after a non-answer hint → Hard at most.
- An explicit valid-answer override → Good at most.
- Practice quizzes record results but do not advance scheduled cards. Only prompts marked `scheduled` call the scheduler.

No response-time thresholds determine difficulty. Accessibility needs and interruptions make such thresholds unreliable.

### 8.3 Exact scheduling rules

Persist timestamps as UTC epoch milliseconds. Use an injected clock. A day in interval arithmetic is exactly 86,400 seconds; calendar-based display and streak calculations are separate. `ease` starts at 2.5 and is clamped to [1.3, 3.0]. `intervalDays` is a positive integer only in review state. Round positive intervals half-up; maximum review interval is 365 days. No random interval fuzz in v1.

**New cards:** First presentation teaches the entry and creates `learning(step=0, due=now)`. It does not count as a successful recall. The next unanswered scheduled presentation may immediately ask for recall, with the answer concealed.

**Learning steps:** step 0 uses a 1-minute delay; step 1 uses a 10-minute delay.

| Current state | Again | Hard | Good | Easy |
| --- | --- | --- | --- | --- |
| Learning step 0 | Stay 0; due +1m | Stay 0; due +5m | Advance 1; due +10m | Graduate; review in 4d |
| Learning step 1 | Reset 0; due +1m | Stay 1; due +10m | Graduate; review in 1d | Graduate; review in 4d |
| Relearning step 0 | Stay; due +10m | Stay; due +15m | Return to review at stored recovery interval | Return to review at max(recovery interval, 2d) |

For learning graduation, set interval to the stated number of days and ease 2.5. A relearning card retains its post-lapse ease. All due times above are relative to the current attempt time.

**Review cards:** let `I = intervalDays` and `E = ease` before this attempt. Delayed reviews receive no extra interval bonus in v1.

| Rating | Transition |
| --- | --- |
| Again | Increment lapses; set ease=max(1.3,E−0.20); store recovery=max(1,roundHalfUp(I×0.5)); enter relearning step 0, due +10m |
| Hard | Review; interval=min(365,max(I+1,ceil(I×1.2))); ease=max(1.3,E−0.15) |
| Good | Review; interval=min(365,max(I+1,roundHalfUp(I×E))); ease unchanged |
| Easy | Review; interval=min(365,max(I+1,roundHalfUp(I×E×1.3))); ease=min(3.0,E+0.15) |

For review outcomes, due=`now + intervalDays × 86,400 seconds`. At the 365-day cap the interval remains 365. Example: `I=10,E=2.5` yields Hard=12d, Good=25d, Easy=33d; Again leads to +10m relearning with a 5d recovery interval. A Good at that relearning step schedules 5d, not 25d.

### 8.4 Queue behavior

1. Resume an in-progress session before generating another.
2. Select due learning/relearning cards ordered by due time, then due review cards ordered by due time; tie-break by card ID.
3. Interleave new entries only when the user selected a session allowing new learning, respecting the daily new-entry allowance. New entries follow the selected band, then ascending rank, skipping introduced entries.
4. Avoid adjacent prompts for the same entry if other eligible work exists. A not-yet-due card is never shown early simply to fill a session.
5. Introduce no more than five new entries in one session. Daily allowance counts unique entries introduced, not cards or attempts.
6. After the last currently eligible prompt, show a summary and “Next review in about …” if a short step is pending. Do not hold users on a countdown screen.
7. Keep overdue work available with no daily review cap. Offer manageable batches and **Pause new words**; do not reset intervals or punish missed days.

For eight review lapses, flag a card as difficult, suggest context/grammar practice, and offer suspension. Never suspend automatically. Unsuspending retains history and makes the card due now; the next answer follows its prior active state. Persist prior state on suspension.

### 8.5 Atomicity and clock changes

One database transaction must insert an immutable review event, update the card, mark the session item answered, and update introduction/activity bookkeeping. A unique attempt ID makes repeated submissions idempotent. Disable repeat submission while committing; a failed write leaves the prompt unanswered and offers retry.

Use wall-clock UTC for persisted due times and monotonic elapsed time for within-session duration. Detect large device-clock changes and show an unobtrusive explanation if due counts shift. Do not claim offline protection against deliberate clock manipulation. Travel changes reminder time and calendar display, not stored due timestamps.

## 9. Persistence and content contracts

### 9.1 Databases and ownership

Use a packaged, read-only content database and a separate writable user database, both exposed through repositories. Stable content IDs bridge them; validate references in code because SQLite foreign keys cannot enforce relationships across separate databases. Room is the user database layer. DataStore holds small preferences, not review histories. UI reads observable local state; online services must update local repositories rather than feed UI directly. This follows Android's documented offline-first architecture. [S1]

| Entity | Key fields and constraints |
| --- | --- |
| ContentManifest | packId, schemaVersion, contentVersion, languageId, entryCount, schedulerCompatibility, checksums, rightsManifestId |
| Entry | entryId PK, curriculumRank unique, lemma, POS, primarySenseId, grammar payload, revision, editorial status |
| Sense | senseId PK, entryId, English gloss, register, region, accepted-answer references |
| Example | exampleId PK, senseId, German text, English text, target spans, audioId |
| AudioAsset | audioId PK, relative path, spoken text, format, duration, checksum, rightsId |
| UserEntry | entryId PK, introducedAt, introductionLocalDate, favorite, selected sense preferences |
| CardState | cardId PK, entryId, skill, state, step, ease, intervalDays, recoveryIntervalDays, dueAt, lapses, consecutiveReviewSuccesses, priorState, schedulerVersion |
| ReviewEvent | eventId PK, attemptId unique, cardId, sessionId, promptRevision, contentVersion, answer result, grade, hint/override flags, before/after state, occurredAt, localDate, zoneId, durationMs |
| Session / SessionItem | sessionId, seed, queue mode, cursor; itemId, attemptId, prompt snapshot, answer status, scheduled/practice flag |
| PracticeResult | resultId, entryId, skill, correctness, localDate; never implicitly updates CardState |
| Assessment | assessmentId, seed, item results, band scores, recommendation, completion status |
| EntitlementCache | productId, state, verification source, checkedAt; not an imported authority |

Index card state by `(state,dueAt)`, review events by `(cardId,occurredAt)` and local date, and content by rank/POS/normalized search aliases. Enable schema export and test every supported user-database migration. Do not use destructive migration fallbacks in production.

### 9.2 Illustrative content record

This example is schema guidance, not approved production content. Its rank is deliberately a placeholder and must be assigned by the corpus pipeline.

```json
{
  "entryId": "de.noun.tisch.01",
  "curriculumRank": null,
  "lemma": "Tisch",
  "pos": "NOUN",
  "languageId": "de-DE",
  "primarySenseId": "de.noun.tisch.01.s1",
  "grammar": {
    "gender": "masculine",
    "article": "der",
    "singular": "Tisch",
    "plural": "Tische",
    "pluralStatus": "regular",
    "weakNoun": false
  },
  "senses": [{"senseId": "de.noun.tisch.01.s1", "glossEn": "table"}],
  "examples": [{
    "exampleId": "de.noun.tisch.01.ex1",
    "senseId": "de.noun.tisch.01.s1",
    "textDe": "Das Buch liegt auf dem Tisch.",
    "textEn": "The book is on the table.",
    "targetSpanCodepoints": [23, 28],
    "audioId": "de.noun.tisch.01.ex1.audio"
  }],
  "wordAudioId": "de.noun.tisch.01.word.audio",
  "revision": 1,
  "editorialStatus": "draft",
  "rightsIds": ["commissioned-content-contract-pending"]
}
```

Target spans use zero-based, half-open Unicode codepoint offsets in NFC text, not UTF-16 code units. Android must convert offsets safely; validate that the span resolves to the intended target. Empty or overlapping spans are rejected unless an explicitly supported multi-token pattern requires multiple spans.

### 9.3 Upgrades

Content ships with app updates in v1. Preflight manifest/checksums and schema compatibility before activation; keep existing valid content until replacement validation succeeds. Never modify IDs simply because frequency ranks change. Sense spelling/translation fixes preserve progress. A substantial semantic replacement requires a new ID plus an explicit mapping or retirement rule; do not transfer mastery automatically between unrelated meanings.

Retired entries retain history as archived records. A content build must keep exactly 5,000 active entries. Split/merge changes require a reviewed migration report. An active session keeps its prompt snapshot for consistency; a content upgrade may safely end an unanswered obsolete prompt and regenerate it without fabricating a review.

## 10. Progress and motivation

| Metric | Exact v1 definition |
| --- | --- |
| Introduced | Unique entries with `introducedAt`, independent of number of cards |
| In learning | Introduced entries whose productive card is learning or relearning |
| Mastered | Productive card in review, interval ≥21d, and at least two consecutive review-state Good/Easy answers since the last Again/Hard |
| Due | Active, unlocked scheduled cards with dueAt ≤ now; show count as cards |
| Scheduled recall success | First attempt per card per study-local-date, review-state prompts only; successful means correct without reveal, or flashcard self-grade Good/Easy |
| Accuracy window | Successful eligible first attempts / eligible first attempts over last 30 calendar days; show sample size and omit percentage below 10 attempts |
| Study day | At least five graded scheduled answers or one completed new-entry introduction; practice/assessment alone does not qualify |
| Streak | Consecutive qualifying local dates; if today has no activity, retain yesterday's streak until today ends |
| Daily goal | Unique entries introduced today / chosen new-entry goal; reviews displayed separately |

Mastery can decrease after a lapse; label it “currently strong” in explanatory copy. Increment `consecutiveReviewSuccesses` only for Good/Easy answers whose pre-answer state was review. Reset it to zero on Again or Hard; learning/relearning graduation does not increment it. A Hard answer resets consecutive Good/Easy successes without deleting prior history. Suspended cards do not count as mastered. Show progress against 5,000, plus a free-tier subtotal; locked vocabulary must not appear as a learning failure.

Store the event's local date and timezone at the time of activity and keep those calendar buckets stable when traveling. Count a calendar date only once. Do not issue negative messaging, paid streak repairs, or misleading “words learned” totals based only on card exposure. All progress screens work offline.

## 11. Reminders and background behavior

Reminders are disabled by default. The user can choose enabled days and an approximate local time, initially 19:00, with one notification maximum per local date. Use a unique one-time WorkManager request computed for the next selected local time, then schedule the next occurrence. Persist `lastNotifiedLocalDate` and re-check eligibility in the worker to prevent duplicates.

Android 13+ requires notification runtime permission for ordinary app notifications. Explain the purpose before the system request, handle denial without nagging, and link to system settings when requested. Android may delay background work; never promise exact delivery. WorkManager timing is inexact and subject to system constraints. Exact-alarm permissions are unnecessary for this vocabulary reminder. [S2, S3]

Recompute the next reminder after setting changes, timezone changes, app launch, and relevant system scheduling recovery. For a nonexistent DST local time, use the next valid time; for an ambiguous repeated time, use the first occurrence and still send once. If the desired date/time was missed, schedule the next future occurrence rather than a burst of catch-up notifications.

Suppress reminders if the user met the daily goal; with goal 0, suppress if a study day already qualifies. If no due or new eligible work exists, do not notify. Respect global notification settings and system quiet modes. Notification text may say “A few German words are ready to review”; avoid exposing individual vocabulary or personal progress on the lock screen. Tapping opens Today or resumes the saved session without recording activity automatically.

## 12. Monetization and entitlements

### 12.1 Tier definition

| Capability | Free | Premium one-time unlock |
| --- | --- | --- |
| Catalog browse/search | All entries with clear lock indicators | All entries |
| Detailed learning/audio/examples | Entries 1–1,000 | Entries 1–5,000 |
| SRS, core quizzes, reminders | Included for unlocked entries | Included for all entries |
| Assessment | Full assessment | Full assessment |
| Progress, export/import, privacy, accessibility | Included | Included |
| Favorites and reviewed-state filters | Included | Included |
| Advanced targeted practice | Basic mode selection | Combine rank/POS/grammar/error-history filters |
| Web sync | Not offered in v1 | Not offered in v1; future terms undecided |

Library search may show lemma/rank/POS for locked entries, but not full answer content. Display a respectful upgrade explanation when opening a locked detail. A free user can complete a substantial course and must not lose access to free progress tools.

Sell one non-consumable Play product, provisionally `german5000_premium_lifetime`. “Lifetime” means access to this app's purchased offline premium catalog, not a promise of perpetual cloud services, all future language apps, or maintenance forever. Prefer the user-facing name **German5000 Premium — one-time purchase**.

### 12.2 Purchase lifecycle

Use the current supported Play Billing Library at implementation freeze and record its version/support deadline. Fetch localized product details from Play. Purchasing and restoring can require internet and an appropriate Play account; an app account remains unnecessary. Explain this distinction in Help.

Required states: `free`, `purchase_pending`, `premium_verified`, `verification_unavailable`, and `revoked`. On a completed `PURCHASED` state, verify the purchase using supported Billing validation, grant the unlock idempotently, persist it, then acknowledge it. Never unlock or acknowledge a pending purchase. Retry acknowledgement through durable work and on reconnect/resume; surface a help state when processing fails. Google documents a three-day acknowledgement window after a purchase becomes purchased. [S4]

The no-backend v1 implementation uses available client-side verification, trusted Billing responses, and any supported signature checks, with the explicitly accepted limitation that it offers weaker fraud protection and delayed revocation awareness. Do not ship service-account credentials in the app. If stronger verification is required, add a small purchase-verification service as a separate approved scope item; it must not become a learning/login dependency. Server verification is the preferred direction documented by Google. [S4]

Query owned purchases after Billing reconnect/resume and on **Restore purchases**. Cached verified premium access does not expire merely because the device is offline or a query errors. Revoke only on an authoritative refund/revocation or a successful reconciled ownership result, never on timeout, empty uninitialized cache, or canceled purchase UI. Explain that a Play account change may affect restoration. Reinstalling while offline cannot prove a former purchase from local app data that was removed; require an online restore.

On revocation, preserve all history and allow backups. Premium cards become inaccessible to scheduling; free cards continue. Re-purchase resumes preserved premium cards with their original due dates and manageable overdue batches. An imported progress file cannot grant premium. Premium is scoped to this app/package; no cross-app purchase entitlement is promised.

### 12.3 Purchase acceptance tests

Use Play license testers for successful, declined, canceled, pending-completes, pending-cancels, duplicate callback, interrupted app, missed callback, acknowledgement retry, refund, offline launch, restore after reinstall, and account-change scenarios. Display the current Play price and purchase terms before confirmation. Do not implement an external checkout in v1.

## 13. Privacy, security, backup, and deletion

### 13.1 Data handling baseline

Store learning data in app-private storage under Android's application sandbox. Device storage protection is the baseline; do not describe the database as separately encrypted unless such encryption is implemented. Ship no advertising identifier access, contact/location permission, microphone access, tracking SDK, or remote analytics. Use only permissions justified by implemented features: internet/network state for Billing-related connectivity where needed, notifications after opt-in, and scheduling-related declarations required by the chosen Android libraries. Inspect the merged manifest for unexpected transitive permissions.

The app owner must maintain a data inventory covering local progress, settings, billing interactions, platform behavior, and any support diagnostics. “No app account” and “offline learning” do not imply that Google Play processes no purchase data. Do not send answer text, vocabulary history, or persistent identifiers to a server in v1.

Publish a real privacy policy with developer identity, contact method, data purposes, local storage, purchases, exports, retention, and deletion. Make it available offline in Settings and at a public URL linked in the listing. Complete Data safety from actual behavior and SDK review; do not predeclare “no data collected” solely from the local-first design. Google requires a privacy policy and accurate disclosures, including applicable SDK behavior. [S6]

No product analytics upload in v1. Use aggregate Play Console stability information for release monitoring and user-initiated diagnostics when needed. A diagnostics preview must exclude personal answers, purchase tokens, and exported progress. A future analytics feature requires a separately reviewed data inventory and disclosure update.

### 13.2 Backup strategy

The v1 product intentionally excludes app-managed user progress and entitlement files from Android cloud backup and device transfer via the applicable legacy backup and Android 12+ data-extraction rules. Exclude static content/audio as well. Test the rules on supported Android versions and document manufacturer-dependent transfer behavior; do not rely on `allowBackup=false` alone as proof that every device transfer path is disabled. Android has platform backup behavior that must be considered explicitly. [S5]

Offer **Export progress** through the Storage Access Framework without broad storage permissions. Explain that the selected destination may itself be a cloud provider. Export a versioned UTF-8 JSON document containing app family/pack IDs, export schema version, content version, timestamp, user-entry state, card state, review events, favorites, assessment summary, and learning preferences. Exclude purchase tokens, trusted entitlement caches, secrets, media, and notification permission state.

The export is unencrypted in v1; warn in the export UI that it contains learning history and should be stored privately. Never write it to a public folder without the user's system-picker choice. Keep exports compatible with future import migrations. Enforce the same documented size/event limits on export and import; never silently truncate history. If a limit is exceeded, explain that a larger-format backup or a version upgrade is required and preserve all local data.

### 13.3 Import and reset

Import is **replace**, not merge, in v1. Parse and validate the entire file before changing anything: maximum 256 MB, at most 1,000,000 review events, supported schema and pack, valid IDs/enums/ranges, event uniqueness, bounded string lengths and collection counts, nonnegative durations, finite numeric values, valid timestamps, and compatible scheduler version. Use streaming parsing and bounded batches so the file-size limit does not imply loading the whole document into memory. Reject malformed/untrusted fields; do not execute embedded links or content. Future-dated reviews beyond a reasonable clock-error allowance require confirmation and explicit correction rather than silent acceptance.

Preview imported counts, timestamp, and any retired/missing content IDs. Offer exporting current progress first; then require explicit confirmation to replace local learning data. Apply replacement atomically and retain the old database until success. Imported reminder settings remain disabled until the user re-enables them. Entitlements are restored only from Billing. If a file contains premium history, retain it even when the current device has free access.

Provide two clear actions:

- **Reset learning progress:** deletes learning/session/assessment history and favorites after confirmation; retains premium entitlement and general display preferences.
- **Delete all local app data:** clears progress and preferences, cancels reminders, and clears local caches; explains that Play purchases, system/platform records, and previously exported files are outside this action. A purchased unlock can be restored from Play.

Uninstall removes local app data through normal Android behavior but does not delete user-created exports. Retain local history until the user resets/deletes it or uninstalls. No v1 account-deletion screen is needed because no app account exists. If later versions introduce accounts, implement account/data deletion in-app and through the required external path before enabling registration. [S7]

## 14. Optional sync and web companion — future contract

Do not build the cloud service in v1. Preserve stable IDs, versioned review events, scheduler versions, and repository interfaces so it can be added without replacing offline storage.

Future sync must be opt-in. Signup must never gate Android offline study. Before upload, explain what leaves the device, service terms, retention, and deletion. Let the user keep local data when signing out and continue learning while sync is paused or unavailable.

Proposed future design, subject to a separate specification:

- Local writes commit immediately and create outbox events; background sync pushes and pulls idempotently with cursors.
- Namespace data by account, language pack, and device. Authorize every server request; use TLS and platform-protected tokens.
- Deduplicate by event ID. A deterministic server sequence resolves concurrent review events; all clients replay the same ordered history under a recorded scheduler version. Do not merge conflicting due dates by taking the maximum.
- Use per-field versions for preferences/favorites. Carry reset/delete tombstones so stale offline devices cannot resurrect deleted progress.
- Prompt before combining an existing cloud profile with device history; preview consequences. Define server limits, retry/backoff, expired authentication, and long-offline-device behavior.
- The web client uses the same card IDs, answer rules, and scheduler contract with cross-platform fixtures.
- Define account deletion, backup-retention expiry, web entitlement rules, and language-specific purchases before launch. A local one-time purchase does not automatically imply a paid web service.

## 15. Accessibility, design, and nonfunctional requirements

### 15.1 Experience rules

Use Material components with a calm visual hierarchy and an obvious next study action. Offer light/dark/system themes. Gender colors can supplement written `der/die/das`, never replace them. Avoid mandatory animations, autoplay surprises, and score-only feedback.

- Every actionable control has a meaningful TalkBack label and logical focus order.
- Minimum touch target 48dp; readable contrast targets follow WCAG AA conventions.
- At 200% font scaling, prompts, answers, paywall terms, and primary controls remain usable without clipping.
- Provide textual equivalents for audio controls and non-listening study routes. Listening is optional, including for mastery.
- Correctness must be conveyed through text/icons as well as color. Respect reduced animation settings where available.
- Support portrait/landscape, small phones, tablets, system back, keyboard input, and input-method composition.
- Use localized string resources with proper plural handling. German linguistic data remains distinct from UI translations.

### 15.2 Measurable targets

These are product acceptance targets to measure on a named midrange reference device selected at implementation kickoff, not claims based on public benchmarks:

| Area | Target and measurement |
| --- | --- |
| Cold launch | p95 ≤2.5s to interactive Today across 30 release-build runs |
| Warm navigation | p95 ≤300ms for local destination transitions |
| Search | p95 ≤150ms query processing on the full 5,000-entry pack, measured separately from debounce |
| Answer commit | p95 ≤150ms durable transaction; UI responds immediately with commit status |
| Audio | p95 ≤300ms from tap to audible start for packaged clips |
| Stability | No known reproducible crashes or ANRs in core journeys before release |
| Download/install | Measured per-device download ≤180MB and initial installed data ≤260MB, or approved revised budget |
| User history | Remains responsive with 100,000 review events and a tested import at the documented size limit |
| Offline | No indefinite spinners or online dependency for unlocked learning, search, audio, reminders, or progress |

Run expensive imports, content checks, and media decoding away from the main thread. Bound search results and paginate history. No continuous background polling or foreground service is needed for ordinary reminders. If user storage is full, preserve existing progress, report the failed save, and allow export when possible.

## 16. Test strategy and acceptance scenarios

### 16.1 Automated coverage

- Pure domain tests: every SRS transition, interval rounding, cap/ease boundaries, queue tie-breaks, daily allowance, hint/reveal mapping, mastery definitions, and clock/timezone handling.
- Data tests: uniqueness/foreign references, event idempotency, process-death persistence, Room upgrade paths, corrupt/unsupported imports, low-storage failure, and atomic rollback.
- Content pipeline: exactly 5,000 active IDs/ranks; required grammar per POS; sense/example links; codepoint spans; audio existence/checksums/text match; approved editorial/rights records; rank methodology report.
- UI instrumentation: onboarding without permissions, session resume, free/premium boundaries, search and German keyboard alternatives, TalkBack semantics, large fonts, rotation, and system back.
- Integration: Billing states via test adapters and Play test products; notification denial/timezone changes; no-network launch and audio; export/import round-trip.
- Architecture: synthetic second language pack uses shared study/screens without German rule leakage.

Do not infer linguistic/audio correctness from automated tests alone. Require completed editorial records and listening checks. Keep golden scheduler fixtures with exact input/output state for future Android/web compatibility.

### 16.2 End-to-end acceptance matrix

| ID | Given / When | Required outcome |
| --- | --- | --- |
| AT-01 | Fresh installation, airplane mode, skip assessment | Finish onboarding and learn free entries with examples/audio; no signup |
| AT-02 | Known-premium device offline | All unlocked lessons and packaged audio work; no entitlement expiry on network error |
| AT-03 | Answer submitted twice during a slow save | Exactly one review event, one transition, one answered item |
| AT-04 | Kill app immediately after committed answer | Resume at next prompt with correct counts and due state |
| AT-05 | Review interval 10d/ease 2.5, grade Again then relearning Good | +10m relearning, then 5d review; history retained |
| AT-06 | Complete assessment with high scores in several bands | Suggest a band; earlier entries remain unstudied; no mastery inflation |
| AT-07 | Reach rank 1,001 on free tier | Explain premium; keep free study/progress/export available |
| AT-08 | Purchase remains pending | No unlock; pending UI; unlock only after verified completion |
| AT-09 | Reinstall and restore online with owning Play account | Premium restored without an app account; learning requires a backup import |
| AT-10 | Refund is authoritatively reconciled | Premium study pauses, free study continues, history/export preserved |
| AT-11 | Reject notifications / later change timezone | Learning works; no nagging; enabled reminders recompute and deduplicate |
| AT-12 | Enter `schon` for prompt requiring `schön` | Incorrect unless explicitly approved; show canonical answer |
| AT-13 | Content ranks change in an upgrade | Stable IDs preserve favorites/cards/history; no destructive migration |
| AT-14 | Import corrupted or wrong-language backup | Explain validation error; no local data modified |
| AT-15 | Import valid backup without premium | Restore history, preserve locks, no entitlement granted |
| AT-16 | Font scale 200%, TalkBack, dark mode | All core tasks and paywall terms remain accessible |
| AT-17 | Large overdue queue after a month away | Offer batches and pause-new choice; no history reset or forced catch-up marathon |
| AT-18 | Delete all local data | Clear local state/cancel reminders; explain external exports and Play purchase retention |
| AT-19 | Device storage full during answer commit | No fabricated success; retry offered; prior history remains intact |
| AT-20 | Daylight-saving transition at chosen reminder time | Next valid occurrence, one notification per local date |

### 16.3 Device matrix

Test the minimum supported API, Android 13 notification behavior, Android 16/API 36 target behavior, and the latest stable Android available at release. Include at least one low-memory device, a midrange phone, tablet, a device without usable Play Billing, and multiple manufacturer background-management behaviors. On devices without Play services, free offline learning remains available; purchases show an accurate availability explanation.

## 17. Google Play release checklist

Public technical/policy sources were checked on 27 October 2026. Store rules and library support windows can change; the release owner must recheck the linked primary sources at submission. The checklist is an engineering release gate, not a certification of future approval.

- [ ] Freeze owner-controlled package ID, signing ownership, product IDs, app name, developer/support contact, and privacy URL.
- [ ] Submit a signed release Android App Bundle; configure Play App Signing and securely retain upload/recovery credentials outside the repository.
- [ ] Target at least Android 16/API 36 for a normal phone/tablet new submission under the requirement effective 31 August 2026; verify the live requirement before uploading. Keep `minSdk=26` separate from `targetSdk`. [S9]
- [ ] Select compatible stable Kotlin, Android Gradle Plugin, Compose, Room, WorkManager, and Billing versions; lock versions and record toolchain and Billing support deadlines. Avoid speculative version numbers in this document.
- [ ] Test modern Android behavior, including notification permission, edge-to-edge layouts/insets, back navigation, and any changes relevant to the selected target SDK.
- [ ] Inventory native libraries; if any are introduced by media/other SDKs, verify applicable ABI and page-size requirements against current Play checks. Prefer a dependency set that avoids unnecessary native code.
- [ ] Complete app content declarations: ads status (none in v1), target audience, content rating, app access, Data safety, privacy policy, and applicable deletion questions. Do not claim children's targeting without the corresponding product/policy work.
- [ ] Make privacy policy, licenses, and attribution accessible in-app; verify the public privacy page is available and matches actual shipped behavior. [S6]
- [ ] Configure and test the one-time product, localized prices, pending transactions, acknowledgement, restore, and refund/revocation reconciliation. [S4]
- [ ] Provide reviewer instructions to reach free and premium screens, assessment, offline learning, restore, and settings; use supported review/test access rather than a hidden production unlock.
- [ ] Supply honest screenshots of actual screens, icon, feature graphic, short/full descriptions, changelog, and support route. Describe the free limit and one-time purchase plainly; do not imply that every entry is free.
- [ ] Substantiate frequency methodology and “5,000” count; publish no unverified fluency or comprehension claims.
- [ ] Complete rights/editorial/audio review of all 5,000 active entries and required recordings; retain audit evidence.
- [ ] Complete internal testing, relevant closed testing, Play pre-launch checks, and production-access requirements for the actual developer account.
- [ ] If using a personal account created after 13 November 2023, the checked policy requires at least 12 closed-test participants continuously opted in for 14 days before applying for production access. Verify current eligibility and account-specific instructions in Play Console. [S11]
- [ ] Review repetitive-content policy before publishing additional language apps. Distinct content is a justification to assess, not a guaranteed exemption. [S8]
- [ ] Verify offline marketing against a fresh per-device installation and a previously purchased device; clearly state that purchase/restore needs connectivity.
- [ ] Approve release QA report, migration report, app-size measurements, dependency/license inventory, and known limitations.
- [ ] Stage production rollout, monitor aggregate Play stability/refunds/support feedback, and define pause/hotfix ownership. Preserve database compatibility and ship forward fixes rather than relying on version-code downgrade.

## 18. Implementation phases and deliverables

Content work and Android implementation should proceed in parallel workstreams, but this is not a schedule estimate. A team must estimate effort after the 100-entry pilot and rights review; content/audio may be the critical path.

| Phase | Work | Exit gate |
| --- | --- | --- |
| 0 — Feasibility | Approve tier defaults, dataset/rights route, 100-entry pilot, recording method, size estimate, package/brand ownership | Licensed production approach and reviewed pilot; no critical policy uncertainty hidden |
| 1 — Offline foundation | Shared modules, German pack contract, content DB, user DB, onboarding, library, detail, audio | Free lesson and local persistence work without network |
| 2 — Learning engine | Exact scheduler, answer rules, queues, session resume, assessment, quizzes | Domain fixtures and AT-03/04/05/06/12 pass |
| 3 — Learner controls | Progress, favorites, reminders, export/import, reset, accessibility | AT-11/14/15/16/18/20 pass |
| 4 — Commercial readiness | Play Billing, paywall, restore, offline cache, rights/license surfaces | Purchase lifecycle acceptance and premium-offline scenarios pass |
| 5 — Full catalog and release | 5,000 entries, audio QA, migrations, performance, account-specific testing and listing | All P0 requirements, acceptance tests, and release gates pass |
| Later — Optional services | Sync/web specification, consent/account deletion, backend and web client | Separate approved design and compatible event/scheduler contract |

Required engineering handoff artifacts:

1. Versioned source repository and reproducible build instructions with final dependency versions.
2. Release content pack, corpus transformation recipe, exclusion/curation ledger, and rights inventory.
3. Content schema, user database schema/migrations, import/export schema, and golden scheduler fixtures.
4. Screen designs and accessibility annotations covering loading, empty, error, locked, pending, and offline states.
5. QA report mapping FR and AT IDs to evidence, device matrix, performance results, and remaining issues.
6. Play listing assets, privacy/attribution pages, reviewer notes, signing/runbook ownership, and release checklist.

### 18.1 Definition of done

V1 is done when a fresh install supports free learning entirely offline; a verified purchaser can study all 5,000 entries offline; all required content and recordings are approved and licensed; every answer persists correctly; the scheduler and session recovery pass their fixtures; the user can understand progress, control reminders, export/import, and delete local data; purchase/restore behavior is tested; and Google Play submission gates are complete. Placeholder vocabulary, unreviewed audio, and broken backup migrations are release blockers.

### 18.2 Decisions requiring owner input before their phase starts

| Decision | Working default | Deadline |
| --- | --- | --- |
| Dataset, corpus blend, rights and attribution | Unselected; evaluate documented candidates | Before production content import |
| Content/audio budget and reviewers | Two-stage linguistic review, reviewed recordings | Before scaling beyond pilot |
| Final brand, domain, package and legal entity | German5000 working name; owner-controlled identifiers | Before store/Billing setup |
| Free boundary and launch price | 1,000 free; one-time premium; price from Play | Before paywall finalization |
| Human vs generated audio, voice identity | Human preferred; approved generated speech permitted | Before mass recording |
| Client-only billing fraud tradeoff | Accept documented v1 limitation or separately scope verifier | Before billing implementation |
| Minimum device support | API 26 | Before device matrix freeze |
| Additional UI/translation languages | English only | After v1 unless explicitly funded |

These decisions do not prevent implementation of the app shell, pure scheduler, persistence contract, and content validation tools. They do prevent making unsupported claims about approved data rights, final costs, or a release date.

## 19. Validated public sources

Checked **27 October 2026**. These sources support platform/policy facts; the product defaults, schema, scheduler, assessment scoring, and performance budgets above are proposed engineering decisions. No public source is asserted to validate the pedagogical efficacy of the custom scheduler or placement test.

| ID | Primary source | Use in this specification |
| --- | --- | --- |
| S1 | [Android Developers — Build an offline-first app](https://developer.android.com/topic/architecture/data-layer/offline-first) | Local data as the observable source of truth; repositories and deferred network operations |
| S2 | [Android Developers — Notification runtime permission](https://developer.android.com/develop/ui/compose/notifications/notification-permission) | Runtime notification permission on Android 13+ |
| S3 | [Android Developers — PeriodicWorkRequest](https://developer.android.com/reference/androidx/work/PeriodicWorkRequest) | Background timing is inexact and affected by system constraints; periodic work has a 15-minute minimum, so this spec uses computed one-time daily work |
| S4 | [Android Developers — Integrate the Google Play Billing Library](https://developer.android.com/google/play/billing/integrate) | Purchase processing, pending states, verification direction, acknowledgement, and reconnect reconciliation |
| S5 | [Android Developers — Back up user data with Auto Backup](https://developer.android.com/identity/data/autobackup) | Explicit backup/data-extraction configuration and awareness of platform backup behavior |
| S6 | [Google Play — User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311?hl=en) | Privacy policy and accurate Data safety disclosures |
| S7 | [Google Play — App account deletion requirements](https://support.google.com/googleplay/android-developer/answer/13327111?hl=en) | Account deletion obligations if optional accounts are later introduced |
| S8 | [Google Play — Spam / Repetitive Content](https://support.google.com/googleplay/android-developer/answer/9899034?hl=en) | Policy risk and review gate for multiple similar branded apps |
| S9 | [Android Developers — Meet Google Play's target API level requirement](https://developer.android.com/google/play/requirements/target-sdk) | API 36 requirement effective 31 August 2026 for ordinary new phone/tablet submissions |
| S10 | [Leipzig Corpora Collection — Download file format](https://www.wortschatz.uni-leipzig.de/documents/Format_Download_File-eng.pdf) | Distinction between wordform frequency lists and optional POS/baseform fields; not a license grant |
| S11 | [Google Play — Testing requirements for new personal developer accounts](https://support.google.com/googleplay/android-developer/answer/14151465?hl=en) | Account-specific closed testing and production-access planning |

Before submission, the release owner must record the date and result of a fresh policy/dependency check alongside the release checklist. Source changes should update the relevant implementation requirement, not merely this reference table.
