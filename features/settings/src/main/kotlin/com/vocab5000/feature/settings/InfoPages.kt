package com.vocab5000.feature.settings

/**
 * Offline copies of policy and help text. The privacy policy must match the public URL
 * listed on Google Play and be reviewed by the owner before release (spec §13.1).
 */
object InfoPages {
    const val PRIVACY_TITLE = "Privacy policy"
    val PRIVACY = """
        DRAFT — the app owner must complete developer identity and contact details and publish this at the public privacy URL before release.

        Who we are: [Developer legal name], [contact email].

        What stays on your device: your learning progress, review history, favourites, assessment results and settings are stored only in this app's private storage on your device. German5000 has no account and no learning server, and it does not upload your answers, vocabulary history or identifiers.

        Purchases: if you buy Premium, the purchase is processed by Google Play under Google's terms and privacy policy. The app stores a local record that the purchase was verified so Premium works offline. Purchase tokens are never included in backups.

        Backups: "Export progress" writes an unencrypted file to a location you choose. That location may be a cloud provider you selected. Exports contain your learning history; keep them private. Exports are not deleted when you delete app data or uninstall.

        Reminders: if you turn on reminders, the app schedules them on your device. Notification text does not include vocabulary or progress.

        Analytics and ads: none. The app contains no advertising or analytics SDKs. Google Play may provide the developer with aggregate crash and stability statistics.

        Retention and deletion: data remains on your device until you reset it, use "Delete all local app data", or uninstall the app. Android cloud backup of app data is disabled.

        Changes: material changes will be described in the app's release notes and at the public policy URL.
    """.trimIndent()

    const val METHOD_TITLE = "How the word list and reviews work"
    val METHOD = """
        The word list is ordered by a curated frequency ranking: one entry is a dictionary form plus part of speech. Inflected forms, plurals and multiple meanings don't count as extra words. Frequency order is a guide to usefulness, not a CEFR level, and completing the list does not guarantee fluency or a specific level of comprehension.

        Reviews use a fixed spaced-repetition schedule (g5000_srs_v1). New words are repeated after 1 and 10 minutes, then after days. Each correct review lengthens the gap; a slip brings the word back after 10 minutes and resumes at a shorter interval. Missing days never resets your history.

        "Currently strong" means a review interval of at least 21 days with your last two reviews rated Good or Easy.

        The placement check samples 30 items across five rank bands. It suggests a starting point only; it never marks words as learned.
    """.trimIndent()

    const val HELP_TITLE = "Help"
    val HELP = """
        Offline: all learning, audio, search, progress and reminders work without internet. Buying or restoring Premium needs a connection and a Google Play account; no German5000 account is needed.

        New device or reinstall: export a backup first, then import it on the new device (Settings → Backup). Premium is restored through "Restore purchases" with the same Google Play account. A backup file can never unlock Premium.

        Typing German letters: use the ä ö ü ß buttons under the answer field. Where listed, keyboard spellings like "schoen" for "schön" are accepted.

        If a correct answer was marked wrong, choose "My answer was correct". That review then counts as Good at most.

        Support: [support email — to be completed by the app owner].
    """.trimIndent()

    const val LICENSES_TITLE = "Licences and attribution"
    val LICENSES = """
        German5000 uses open-source components including AndroidX, Jetpack Compose, Room, WorkManager, DataStore, Kotlin and kotlinx libraries (Apache License 2.0), Gson (Apache License 2.0) and the Google Play Billing Library (Android Software Development Kit License).

        Vocabulary data, examples, translations and recordings: see the rights ledger for this content version. The current build contains pilot content that is not licensed for release.
    """.trimIndent()
}
