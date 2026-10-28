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
        This build contains 5,000 entries: the original 4,010 workbook entries, whose frequency ranking was verified against the Leipzig corpus, followed by 990 additions selected from the Leipzig German news corpus. Each entry has one example sentence. The additions use observed headword frequency and remain drafts awaiting review of meanings, grammar, examples and audio. Frequency order is a guide to usefulness, not a CEFR level, and completing the list does not guarantee fluency or a specific level of comprehension.

        Reviews use a fixed spaced-repetition schedule (g5000_srs_v1). New words are repeated after 1 and 10 minutes, then after days. Each correct review lengthens the gap; a slip brings the word back after 10 minutes and resumes at a shorter interval. Missing days never resets your history.

        "Currently strong" means a review interval of at least 21 days with your last two reviews rated Good or Easy.

        The placement check uses eligible entries with English meanings. Unreviewed imported corpus entries are excluded. It suggests a starting point only; it never marks words as learned.
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

        German grammar and some draft meanings are adapted from English Wiktionary contributors via Kaikki.org / Wiktextract (extracted 25 October 2026 from the 1 October 2026 dump). Per-word source URLs are included in the entry notes. Wiktionary text is available under Creative Commons Attribution-ShareAlike; see https://en.wiktionary.org/wiki/Wiktionary:Copyrights and https://creativecommons.org/licenses/by-sa/4.0/. Extraction and adaptation to the app's grammar fields were automated; manual draft corrections were also made.

        Most imported English meanings and sentence translations were generated with Google Translate on 28 October 2026 and are unreviewed. The ranked word list and German sentences come from the user-supplied german_words.xlsx workbook. One absent original example was supplied as an AI draft. The 990 Leipzig additions use German news corpus examples, with ten AI-drafted replacements to remove caption artifacts or simplify context, dictionary-derived English meanings and grammar, and Google Cloud Translation for the remaining sentence translations. Audio for these additions is synthetic speech generated with Google Cloud Text-to-Speech; the original corpus audio has mixed origins.

        Vocabulary data, examples, translations and recordings: see the rights ledger for this content version. This draft content is pending rights review and is not cleared for release.
    """.trimIndent()
}
