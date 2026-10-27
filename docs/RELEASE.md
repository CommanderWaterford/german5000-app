# Release runbook

Engineering release gates for German5000. Recheck every external requirement against the
primary sources in spec §19 at submission time.

## Locked toolchain (initial implementation, 27 Oct 2026)

| Component | Version | Notes |
| --- | --- | --- |
| Gradle | 9.8.2 | wrapper |
| Android Gradle Plugin | 9.4.1 | built-in Kotlin support |
| Kotlin / Compose compiler | 2.4.21 | |
| KSP | 2.3.12 | Room annotation processing |
| Compose BOM | 2026.09.00 | |
| Room | 2.8.5 | schema exported to `core/database/schemas` |
| WorkManager | 2.12.0 | |
| DataStore | 1.3.0 | |
| Play Billing Library | 9.1.0 | **Record the support deadline** from Google's Billing deprecation schedule at freeze |
| compileSdk / targetSdk / minSdk | 37 / 36 / 26 | targetSdk meets the API 36 requirement effective 31 Aug 2026 |

All versions live in `gradle/libs.versions.toml`. Update this table when they change.

## Identity and signing

- Supply the owner-controlled application ID: `-Pg5000.applicationId=com.yourcompany.german5000`.
  The build fails on the placeholder `invalid.placeholder.german5000`.
- Upload key (never committed): pass `-Pg5000.signing.storeFile=…`, `-Pg5000.signing.storePassword=…`,
  `-Pg5000.signing.keyAlias=…`, `-Pg5000.signing.keyPassword=…` (e.g. from CI secrets or
  `~/.gradle/gradle.properties`). Use Play App Signing.
- Optional client-side purchase signature check: `-Pg5000.playLicenseKey=<base64 key from Play Console>`.
- Play product: one non-consumable in-app product `german5000_premium_lifetime`
  (`EntitlementRepository.PRODUCT_ID`); price set in Play Console only.

```bash
./gradlew :apps:german5000:bundleRelease \
  -Pg5000.applicationId=com.yourcompany.german5000 \
  -Pg5000.signing.storeFile=/secure/upload.jks …
```

## Content gate

`bundleRelease` runs the content pipeline in **release** mode and fails unless:

- exactly 5,000 active entries, ranks contiguous 1–5,000 from the corpus pipeline (`rankSource` ≠ `placeholder`);
- every entry `approved`/`published`, with cleared rights IDs (no `pending`);
- every word and primary-example audio file present under `content/german5000/audio/` with checksums;
- grammar complete per part of speech, spans resolve, senses/examples linked;
- free limit 1,000 and band size 1,000.

The report is at `apps/german5000/build/reports/content/release-validation.txt`.

## Permissions inventory (merged manifest)

| Permission | Source | Why |
| --- | --- | --- |
| `POST_NOTIFICATIONS` | `core/settings` | Reminders; requested only after opt-in (Android 13+) |
| `com.android.vending.BILLING` | Play Billing | One-time purchase |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Play Billing / WorkManager | Purchase, restore, acknowledgement retry |
| `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED` | WorkManager | Reliable scheduling of the reminder |
| `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | AndroidX core | Internal, app-scoped |

`FOREGROUND_SERVICE` contributed by WorkManager is removed (`tools:node="remove"`): no foreground
work is used. Re-run `aapt2 dump permissions` on the release bundle before each submission.

No native libraries are currently packaged; re-check ABI and 16 KB page-size requirements if
any dependency adds them.

## Backup behaviour

`allowBackup=false`, plus `backup_rules.xml` (Android ≤ 11) and `data_extraction_rules.xml`
(Android 12+) exclude all app data from cloud backup and device transfer. Verify on the device
matrix, including OEM transfer tools; learners use **Export progress** instead.

## Play Console checklist

Follow spec §17. Items that depend on this codebase:

- [ ] Data safety form from the actual behaviour: no data collected by the app itself;
      purchases processed by Google Play; no analytics/ads SDKs.
- [ ] Privacy policy: complete `features/settings/InfoPages.kt` (developer identity, contact)
      and publish the same text at the public URL.
- [ ] Reviewer notes: free path works offline; premium via license-tester account; no hidden unlock.
- [ ] Closed testing requirements for the developer account (12 testers × 14 days for new personal accounts).
- [ ] QA report mapping FR/AT IDs to evidence ([IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md)).
