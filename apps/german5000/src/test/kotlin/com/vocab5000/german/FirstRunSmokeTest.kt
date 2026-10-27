package com.vocab5000.german

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Robolectric does not run androidx.startup, so WorkManager is initialised explicitly. */
class TestApp : German5000App() {
    override fun onCreate() {
        WorkManagerTestInitHelper.initializeTestWorkManager(this, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        super.onCreate()
    }
}

/**
 * AT-01 smoke test on the JVM: fresh install with no network, skip the assessment,
 * finish onboarding, and learn and recall a free word. No account, billing or permission
 * is involved.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = TestApp::class)
class FirstRunSmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun waitForText(text: String, timeoutMs: Long = 10_000) {
        try {
            compose.waitUntil(timeoutMs) { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Throwable) {
            throw AssertionError("'$text' not shown. Screen:\n" + compose.onRoot(useUnmergedTree = true).printToString(), e)
        }
    }

    private fun click(text: String) {
        waitForText(text)
        compose.onNodeWithText(text, substring = true).performScrollTo().performClick()
        compose.waitForIdle()
    }

    @Test fun onboardingThenFirstLesson() {
        click("Get started")
        click("Start from the beginning")
        click("10 words (recommended)")
        click("Not now")
        click("Go to Today")
        waitForText("reviews due")
        click("Start session")

        // Ranks 1 and 2 of the pilot pack are introduced; the planner then avoids asking
        // the just-introduced word and recalls rank 1 ("der") first.
        waitForText("article · rank 1")
        click("Got it")
        waitForText("conjunction · rank 2")
        click("Got it")

        waitForText("Type the German word")
        waitForText("the (definite article)")
        compose.onNode(hasSetTextAction()).performTextInput("der")
        click("Check")
        waitForText("✓ Correct")
        waitForText("Good — correct")
        click("Continue")

        // The committed answer advanced the session: rank 2 ("und") is recalled next.
        waitForText("1 answered")
        waitForText("and")
    }

    private fun finishOnboarding() {
        // DataStore is a process-wide singleton, so an earlier test may have completed onboarding.
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("Get started")).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodes(hasText("reviews due")).fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodes(hasText("Get started")).fetchSemanticsNodes().isEmpty()) return
        click("Get started")
        click("Start from the beginning")
        click("5 words")
        click("Not now")
        click("Go to Today")
        waitForText("reviews due")
    }

    /** AT-07 (pilot scale): words above the free limit are browsable but locked, with an explanation. */
    @Test fun lockedEntriesExplainPremiumAndOtherTabsRender() {
        finishOnboarding()
        compose.onNodeWithText("Library").performClick()
        waitForText("Search German or English")
        compose.onNode(hasSetTextAction()).performTextInput("Leute")
        waitForText("Premium")
        // Locked rows show lemma, rank and part of speech only — no meaning.
        compose.onNode(hasText("noun") and hasText("Leute") and hasClickAction()).performClick()
        waitForText("part of Premium")
        click("About Premium")
        waitForText("German5000 Premium — one-time purchase")
        waitForText("Restore purchases")
        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()

        compose.onNodeWithText("Progress").performClick()
        waitForText("words introduced")
        waitForText("Not enough reviews yet")
        compose.onNodeWithText("Settings").performClick()
        waitForText("Export progress")
        waitForText("Delete all local app data")
    }
}
