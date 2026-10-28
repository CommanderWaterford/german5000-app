# German5000 (Android)

An offline-first German vocabulary app: 5,000 frequency-ranked entries taught with context,
grammar, audio, recall practice and a deterministic spaced-repetition scheduler. No account,
no analytics, no ads. Progress stays on the device.

German5000 is the first app of a product family. The code is a shared engine
(`core/*`, `features/*`) plus a German language pack (`languages/german`) and
German content (`content/german5000`).

- **Specification:** [docs/german5000-android-spec.md](docs/german5000-android-spec.md)
  (code comments cite its sections as `§n`)
- **What is done / open:** [docs/IMPLEMENTATION_STATUS.md](docs/IMPLEMENTATION_STATUS.md)
- **Release runbook and gates:** [docs/RELEASE.md](docs/RELEASE.md)
- **Content workflow:** [content/german5000/README.md](content/german5000/README.md)
- **Google Cloud audio production:** [docs/GOOGLE_CLOUD_TTS.md](docs/GOOGLE_CLOUD_TTS.md)

> **Status:** **5,000 entries**, each with one bilingual example and grammar: 4,010 original
> workbook entries plus 990 draft additions from the supplied Leipzig news corpus. The owner
> accepted the original audio and sentences and verified its frequency ranking against Leipzig.
> Existing audio has mixed origins. The new additions remain drafts pending their own review;
> production builds still enforce editorial and rights approval.

## Requirements

- JDK 17+ (21 tested)
- Android SDK with platform **android-37** (compileSdk) and build-tools 36+;
  `targetSdk` is 36, `minSdk` 26
- Gradle is provided by the wrapper (`./gradlew`)

Create `local.properties` with `sdk.dir=/path/to/Android/sdk` if `ANDROID_HOME` is not set.

## Build and test

```bash
./gradlew :apps:german5000:assembleDebug          # debug APK with the draft corpus pack
./gradlew jvmTest testDebugUnitTest               # all JVM + Robolectric tests
./gradlew lintDebug                               # Android Lint, all modules
./gradlew :tools:content-pipeline:run --args="validate $PWD/content/german5000/source pilot"
```

The content pack is **generated at build time** from `content/german5000/source` by
`tools/content-pipeline` and added to the APK assets. The validation report is written to
`apps/german5000/build/reports/content/<variant>-validation.txt`.

### Release gates

`assembleRelease` / `bundleRelease` fail unless:

1. an owner-controlled application ID is supplied: `-Pg5000.applicationId=<your.package>`
   (the default `invalid.placeholder.german5000` is a deliberate placeholder), and
2. the content pack passes **release** validation: exactly 5,000 approved, rights-cleared
   entries with checksummed word and example audio, corpus-derived ranks, free limit 1,000.

For internal testing only, `-Pg5000.allowPilotContentInRelease=true` lets a minified
release-type build carry pilot content. Never use it for a production upload.

Signing keys are never stored in the repository; see [docs/RELEASE.md](docs/RELEASE.md).

## Module map

| Module | Responsibility |
| --- | --- |
| `apps/german5000` | App identity, manifest, backup rules, DI container, navigation shell, content-pack build wiring |
| `core/model` | Language-neutral content/learning models, `Prompt` snapshots, `LanguagePack` interfaces, injectable clock |
| `core/learning` | Pure Kotlin: `g5000_srs_v1` scheduler, rating policy, answer evaluation, session planner, assessment scoring, progress metrics, reminder time maths |
| `core/content` | Pack format, runtime loader + validator, search, prompt generation, free/premium access rules |
| `core/database` | Room user database (schema exported to `core/database/schemas`), atomic answer commits, stats |
| `core/settings` | DataStore preferences, WorkManager reminders, streaming backup export/import, clock-change detection |
| `core/billing` | Play Billing adapter, entitlement state machine, client-side verification, acknowledgement retry |
| `core/audio` | Packaged-audio playback with audio focus; optional on-device speech fallback |
| `core/designsystem` | Compose theme (light/dark), accessible components, umlaut/ß input helpers |
| `features/*` | Onboarding, Today, Study (sessions, practice, assessment), Library, Progress, Premium, Settings |
| `languages/german` | German grammar rendering, answer rules (articles, `sich`, umlaut alternatives), grammar validation and exercises |
| `tools/content-pipeline` | Builds and validates the content pack from editorial sources |
| `content/german5000` | Editorial sources, curriculum order, rights ledger (audio goes in `audio/`) |
| `build-logic` | Gradle convention plugins (SDK levels, Compose, JVM modules) |

## Adding another language app

Implement `LanguagePack` (see `languages/german`), add a content source directory and an
app-shell module with its own application ID, brand and Play product ID. The scheduler,
planner, persistence, billing and screens are shared; `SyntheticLanguagePackTest` shows a
non-German pack running through the shared engine. Review Google Play's repetitive-content
policy before listing another app (spec §2.2).
