# Implementation status

Traceability from the [specification](german5000-android-spec.md) to code and tests, as of
the corpus import update (28 October 2026). "Automated" means a test in this repository
exercises the behaviour; device, accessibility and Play-console checks still need a person
with hardware and a Play developer account.

Legend: ✅ implemented and tested · 🟡 implemented, not yet verified on devices / partially
covered · ⛔ blocked on owner input or content work · ⬜ not started

## Functional requirements (§4)

| ID | Status | Where | Evidence / notes |
| --- | --- | --- | --- |
| FR-01 Offline first use + free lesson | ✅ | `features/onboarding`, `features/study`, `apps/german5000` | `FirstRunSmokeTest.onboardingThenFirstLesson` (Robolectric, no network) |
| FR-02 Validated catalog of exactly 5,000 | ⛔ | `core/content/PackValidator`, `tools/content-pipeline` | 4,010 ranked draft entries imported with one example each and English/grammar enrichment; release still requires 5,000 approved entries. `ContentPipelineTest` |
| FR-03 Local progress, sessions, prefs, favourites | ✅ | `core/database`, `core/settings/UserPreferences` | `LearningRepositoryTest` |
| FR-04 Skippable placement assessment | ✅ | `core/learning/Assessment`, `features/study/Assessment*` | `AssessmentTest`; resumable via persisted seed |
| FR-05 SRS scheduling | ✅ | `core/learning/SrsV1`, `SessionPlanner` | `SrsV1Test` incl. golden sequence; `SessionPlannerTest` |
| FR-06 Recognition, typed, listening, cloze, grammar | ✅ | `core/content/PromptFactory`, `languages/german`, `features/study/Practice*` | `ContentPackTest`, `GermanLanguagePackTest` |
| FR-07 Offline audio | ⛔ | `core/audio/AudioPlayer` | Playback path implemented; **no recordings exist yet**. Optional device TTS fallback is labelled synthetic |
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
| AT-01 | ✅ | `FirstRunSmokeTest.onboardingThenFirstLesson` (audio pending recordings) |
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

1. **Content (blocking):** frequency source and rights, 5,000 reviewed entries, 10,000 recordings
   (spec §5–6, §18.2). The pilot content must not ship.
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
