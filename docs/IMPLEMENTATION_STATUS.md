# Implementation status

Traceability from the [specification](german5000-android-spec.md) to code and tests, as of
the owner verification update (28 October 2026). "Automated" means a test in this repository
exercises the behaviour; device, accessibility and Play-console checks still need a person
with hardware and a Play developer account.

Legend: ✅ implemented and tested · 🟡 implemented, not yet verified on devices / partially
covered · ⛔ blocked on owner input or content work · ⬜ not started

## Leipzig expansion update

The catalog now contains **5,000 entries**: the existing owner-verified 4,010 plus
**990 draft Leipzig additions**, each with dictionary-derived grammar, an English meaning,
and exactly one German/English example with a target annotation. Existing entry IDs and
content are preserved. The new additions require their own editorial and audio review; the
owner's earlier acceptance applies to the original corpus. All **1,980 new audio clips** have been generated with Google Cloud TTS, bringing the
pack to **10,000 clips**. The new clips require listening review; original audio acceptance
is unchanged. Production and review evidence for the additions is kept separately.

## Confirmed original content status

The owner confirmed on 28 October 2026 that the existing **audio and example sentences
were verified and are okay**, and that the **frequency ranking was verified against the
Leipzig corpus**. These checks are complete; no further specification of the existing
frequency source is required.

- There are **4,010 entries**, each with one example sentence and both word and example
  audio: **8,020 packaged clips**, all present with matching pack checksums.
- Audio has **mixed origins**. Not all clips are Google Cloud synthesized; the Google TTS
  tooling and historical generation ledger must not be treated as a complete description
  of the current audio sources. Do not label every clip as Google-generated or synthetic.
- The **990-entry expansion is now imported**, with one example per new entry; it now includes
  **1,980 additional Google Cloud TTS clips**.
- Source records still carry `draft` / `corpusDraft` flags, and the production ledger still
  carries pending audio-review flags. These are bookkeeping that has not yet incorporated
  the owner's confirmation, not evidence that the accepted sentences or audio need to be
  reviewed again. English meanings, grammar, rights clearance and final editorial approval
  are separate from the confirmed audio, sentence and frequency checks.

## Functional requirements (§4)

| ID | Status | Where | Evidence / notes |
| --- | --- | --- | --- |
| FR-01 Offline first use + free lesson | ✅ | `features/onboarding`, `features/study`, `apps/german5000` | `FirstRunSmokeTest.onboardingThenFirstLesson` (Robolectric, no network) |
| FR-02 Validated catalog of exactly 5,000 | ⛔ | `core/content/PackValidator`, `tools/content-pipeline` | 5,000 entries: 4,010 original entries plus 990 draft Leipzig additions. Complete review of the additions and final editorial metadata. `ContentPipelineTest` |
| FR-03 Local progress, sessions, prefs, favourites | ✅ | `core/database`, `core/settings/UserPreferences` | `LearningRepositoryTest` |
| FR-04 Skippable placement assessment | 🟡 | `core/learning/Assessment`, `features/study/Assessment*` | Engine tested and resumable via persisted seed (`AssessmentTest`); eligibility still covers only 50 entries (49 in band 1, one in band 2). Enable suitable reviewed entries across all bands |
| FR-05 SRS scheduling | ✅ | `core/learning/SrsV1`, `SessionPlanner` | `SrsV1Test` incl. golden sequence; `SessionPlannerTest` |
| FR-06 Recognition, typed, listening, cloze, grammar | ✅ | `core/content/PromptFactory`, `languages/german`, `features/study/Practice*` | `ContentPackTest`, `GermanLanguagePackTest` |
| FR-07 Offline audio | 🟡 | `core/audio/AudioPlayer` | 10,000 clips present: original 8,020 owner-accepted clips with mixed origins, plus 1,980 Google Cloud TTS clips for the expansion. Listening review remains for the additions only |
| FR-08 Resume without duplicate events | ✅ | `LearningRepository`, `SessionEngine` | `repeatedSubmissionIsIdempotent`, `committedAnswerSurvivesRestart` |
| FR-09 Opt-in reminders | 🟡 | `core/settings/Reminders.kt` | DST/day logic in `ReminderCalculatorTest`; worker and permission flow need device testing |
| FR-10 Progress with documented metrics | ✅ | `core/learning/ProgressMetrics`, `features/progress` | `ProgressMetricsTest`; definitions shown in-app |
| FR-11 Purchase, pending, offline entitlement, restore | 🟡 | `core/billing` | `EntitlementRepositoryTest` with a fake gateway; needs Play license-tester runs (§12.3) |
| FR-12 Export/import, local deletion | ✅ | `core/settings/ProgressBackup`, `features/settings` | `ProgressBackupTest`, `resetKeepsEntitlementButDeleteAllClearsIt` |
| FR-13 TalkBack, large text, dark mode, non-audio routes | 🟡 | `core/designsystem`, all screens | Labels, 48dp targets, text-not-colour feedback, flashcard route; not yet audited with TalkBack/200% font on devices |
| FR-14 Content and DB upgrades without loss | 🟡 | `SessionEngine` (obsolete prompt handling), `UserDatabase.MIGRATIONS` | Schema v1 exported; no migrations exist yet to test. Stable IDs throughout |
| FR-15 Sync/web (P1) | ⬜ | — | Out of v1 scope; IDs, event log and scheduler version are preserved for it |

## Acceptance tests (§16.2)

| ID | Status | Evidence |
| --- | --- | --- |
| AT-01 | ✅ | `FirstRunSmokeTest.onboardingThenFirstLesson`; current corpus audio is present and owner-verified |
| AT-02 | ✅ | `EntitlementRepositoryTest.offlineNeverRevokes` |
| AT-03 | ✅ | `LearningRepositoryTest.repeatedSubmissionIsIdempotent` |
| AT-04 | ✅ | `LearningRepositoryTest.committedAnswerSurvivesRestart`, `pendingItemIsReturnedInsteadOfPlanningANewOne` |
| AT-05 | ✅ | `SrsV1Test.againThenRelearningGoodUsesRecoveryInterval` |
| AT-06 | ✅ | `AssessmentTest`; assessment never writes cards/events |
| AT-07 | ✅ | `FirstRunSmokeTest.lockedEntriesExplainPremiumAndOtherTabsRender` (draft corpus free limit is 1,000) |
| AT-08 | ✅ | `EntitlementRepositoryTest.pendingDoesNotUnlock` |
| AT-09 | 🟡 | `restoreFromPlayOwnership` with fake gateway; real Play restore untested |
| AT-10 | ✅ | `authoritativeRefundRevokes` |
| AT-11 | 🟡 | Denial path implemented (reminders stay off, no re-prompt); time-zone receiver untested on device |
| AT-12 | ✅ | `GermanLanguagePackTest.schonIsNotSchoen` |
| AT-13 | 🟡 | Stable IDs + rank-independent user data by design; no multi-version content test yet |
| AT-14 | ✅ | `ProgressBackupTest.invalidBackupsChangeNothing` |
| AT-15 | ✅ | `ProgressBackupTest.roundTripRestoresHistoryButNeverEntitlement` |
| AT-16 | 🟡 | Needs device audit (TalkBack, 200% font, dark mode) |
| AT-17 | 🟡 | No review cap, batches of 20, pause-new + backlog warning implemented; not E2E tested |
| AT-18 | 🟡 | Data clearing tested; reminder cancellation and copy need device check |
| AT-19 | ✅ (simulated) | `failedCommitLeavesNothingBehind` (transaction rollback); literal disk-full not reproduced |
| AT-20 | ✅ | `ReminderCalculatorTest.dstGapUsesNextValidTime`, `dstOverlapUsesFirstOccurrence` |

## Deliberate implementation decisions

- **Read-only content store.** The spec calls for a packaged read-only content database. The
  pack is shipped as a validated JSON asset and held as an immutable, indexed in-memory store
  (`ContentPack`); user data stays in Room and references content only by stable ID. This keeps
  the pipeline's output and the runtime validator identical. Switch to a prebuilt SQLite pack if
  cold-launch measurements on the reference device miss the 2.5 s budget with 5,000 entries.
- **Ease in permille.** Ease is stored as an integer (2500 = 2.5) so interval maths is exact and
  reproducible on a future web client.
- **Grammar payload as key/value strings**, interpreted by the language pack
  (`languages/german/GermanGrammar.kt`). `pluralStatus` uses `has_plural | no_plural | plural_only`.
- **compileSdk 37, targetSdk 36.** Current AndroidX releases require compiling against API 37;
  the target stays at the Play requirement (API 36) per spec §17.
- **Placement band size and free limit come from the pack manifest**. The draft corpus
  uses bands of 1,000 and a free limit of 1,000, matching the release configuration.
- **Keyboard alternatives** (`ä→ae`, `ß→ss`, …) are derived by the pipeline into the pack
  as explicit per-sense lists, skipped on collisions with other lemmas, and overridable by editors.

## Known gaps before release

1. **Expansion review (blocking):** the 990 additions are imported, bringing the catalog to
   5,000 entries. Review the new meanings, grammar, examples and audio before approving them.
   The original corpus's sentences, audio quality and frequency verification remain complete.
2. **UI strings** are English literals in Kotlin; move to string resources with plurals (§15.1)
   before adding UI translations.
3. **Tablet two-pane** library/detail layout (§3.2) — the navigation rail adapts, panes do not yet.
4. **Device verification:** TalkBack, 200% font, rotation, notification permission, OEM background
   limits, devices without Play services (§16.3).
5. **Performance budgets** (§15.2) unmeasured; needs the reference device and the full pack.
6. **Billing:** Play Console product setup and license-tester runs (§12.3); decide whether to add a
   server verifier (§18.2).
7. **Privacy policy** text in `features/settings/InfoPages.kt` is a draft with placeholders for the
   developer identity and contact.

8. **Editorial and release metadata:** reconcile recorded review states with the owner's
   confirmation, finish English/grammar approval and rights records, and reflect mixed audio
   origins accurately. The current release validator still rejects the draft flags. Owner
   acceptance of sentences/audio does not by itself change all entries to release-approved.
9. **Cloze annotations:** add valid target spans for `-jährig` and `Zentimeter`. The sentences
   are accepted; these are exercise-annotation gaps, not failed sentence reviews. They are
   the only two development content-validation warnings.
10. **Placement coverage:** enable appropriate reviewed entries throughout the frequency bands;
    the current 50 eligible entries do not cover the full catalog.

## Source for the remaining 990 entries

The owner supplied **`deu_news_2025_300K.tar`** on 28 October 2026 as the expansion
source. Its metadata records 300,000 sentences, 345,419 word types and 5,237,315 tokens
(build date 7 January 2026). It contains word frequencies but no POS/base-form list.
Use this **Leipzig Corpora Collection** dataset for candidate selection, consistent with
the existing frequency verification. Its [download service](https://wortschatz.uni-leipzig.de/en/download)
provides corpus word lists. The official
[download-format documentation](https://www.wortschatz.uni-leipzig.de/documents/Format_Download_File-eng.pdf)
describes `*_words.txt` as word forms sorted by descending frequency, with counts; some
corpora also include `*_words_pos_base.txt` with part of speech and base forms.

The recommended expansion workflow is to take a German frequency list, normalize forms to
lemma and part of speech, exclude entries and aliases already covered by the existing 4,010,
filter names, fragments and unsuitable tokens, and select the highest-frequency 990 useful
remaining entries. Raw list positions 4,011–5,000 are not necessarily 990 new vocabulary
entries because Leipzig lists word forms, while the app groups vocabulary into entries.
Keep existing IDs stable and preserve the accepted curriculum order; this is an expansion,
not a repeat of the completed frequency verification.

For each selected addition, supply the English meaning, grammar, exactly one suitable German
example with its English translation and target span, and word/example audio. Record the
chosen new dataset and its applicable reuse terms when importing it. The 990 additions are stored in `source/leipzig-expansion.json` and preserved by workbook
reimports. Their order uses observed headword counts, not aggregated inflection frequencies;
news-domain vocabulary and draft dictionary sense choices still need editorial review.

## Expanded-pack verification (28 October 2026)

- Debug build succeeds; 84 JVM/Android tests and 32 Python tests pass.
- Pack contains 5,000 entries, exactly one example each, and 10,000 audio assets.
- Every audio file inside the APK matches its manifest SHA-256 checksum.
- All 1,980 new clips pass codec/duration/checksum verification; listening review is pending.
- Original 4,010 entries are unchanged, and workbook reimport preserves the full 5,000.
- Development content validation: zero errors, only the two existing target-span warnings.
- Debug APK is 183,114,527 bytes (183.1 MB), above the 180 MB download budget. Measure the
  minified release/download size before release; if needed, reduce encoding size for the new
  clips without changing the already accepted original audio.
