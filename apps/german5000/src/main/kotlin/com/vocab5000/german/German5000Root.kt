package com.vocab5000.german

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.vocab5000.core.designsystem.ErrorMessage
import com.vocab5000.core.designsystem.G5000Theme
import com.vocab5000.core.designsystem.LoadingState
import com.vocab5000.core.designsystem.ThemePreference
import com.vocab5000.core.model.SessionMode
import com.vocab5000.core.settings.UserPreferences
import com.vocab5000.feature.library.EntryDetailScreen
import com.vocab5000.feature.library.LibraryScreen
import com.vocab5000.feature.library.LibraryViewModel
import com.vocab5000.feature.onboarding.OnboardingScreen
import com.vocab5000.feature.premium.PremiumScreen
import com.vocab5000.feature.progress.ProgressScreen
import com.vocab5000.feature.progress.ProgressViewModel
import com.vocab5000.feature.settings.AppInfo
import com.vocab5000.feature.settings.SettingsScreen
import com.vocab5000.feature.settings.SettingsViewModel
import com.vocab5000.feature.study.AssessmentScreen
import com.vocab5000.feature.study.AssessmentViewModel
import com.vocab5000.feature.study.BandOption
import com.vocab5000.feature.study.PracticeScreen
import com.vocab5000.feature.study.PracticeViewModel
import com.vocab5000.feature.study.SessionEngine
import com.vocab5000.feature.study.StudyHomeScreen
import com.vocab5000.feature.study.StudySessionScreen
import com.vocab5000.feature.study.StudyViewModel
import com.vocab5000.feature.today.TodayScreen
import com.vocab5000.feature.today.TodayViewModel
import kotlinx.coroutines.launch

private enum class TopLevel(val route: String, val label: String, val icon: ImageVector) {
    TODAY("today", "Today", Icons.Filled.Home),
    STUDY("study", "Study", Icons.Filled.PlayArrow),
    LIBRARY("library", "Library", Icons.Filled.Search),
    PROGRESS("progress", "Progress", Icons.Filled.Info),
    SETTINGS("settings", "Settings", Icons.Filled.Settings),
}

/** Routes that hide the navigation bar so the learner can focus. */
private val focusRoutes = setOf("session?mode={mode}", "assessment", "practice")

@Composable
fun German5000Root(container: AppContainer, clockChanged: Boolean) {
    val prefs by container.preferences.preferences.collectAsState(initial = null)
    val content by produceState<ContentState?>(null) { value = container.content() }
    val theme = prefs?.theme?.let { runCatching { ThemePreference.valueOf(it) }.getOrNull() } ?: ThemePreference.SYSTEM
    G5000Theme(theme) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val p = prefs
            val c = content
            when {
                p == null || c == null -> Box(Modifier.safeDrawingPadding()) { LoadingState(label = "Loading German5000") }
                c is ContentState.Failed -> ContentError(c.problems)
                !p.onboardingComplete -> Box(Modifier.safeDrawingPadding()) {
                    OnboardingScreen(p.onboardingStep, container.preferences, onRemindersChanged = {
                        container.appScope.launch { container.reminderScheduler.reschedule() }
                    })
                }
                c is ContentState.Ready -> MainScaffold(container, c, p, clockChanged)
            }
        }
    }
}

@Composable
private fun ContentError(problems: List<String>) {
    Column(Modifier.safeDrawingPadding().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("The word list couldn't be opened", style = MaterialTheme.typography.headlineSmall)
        Text("Your progress is safe. Please update or reinstall the app; if this continues, contact support.")
        problems.take(5).forEach { ErrorMessage(it) }
    }
}

@Composable
private fun MainScaffold(container: AppContainer, content: ContentState.Ready, prefs: UserPreferences, clockChanged: Boolean) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val currentTop = TopLevel.entries.firstOrNull { it.route == route }
    val layoutType = if (route in focusRoutes) {
        NavigationSuiteType.None
    } else {
        NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(androidx.compose.material3.adaptive.currentWindowAdaptiveInfo())
    }
    NavigationSuiteScaffold(
        layoutType = layoutType,
        navigationSuiteItems = {
            TopLevel.entries.forEach { dest ->
                item(
                    selected = currentTop == dest,
                    onClick = { nav.navigateTopLevel(dest.route) },
                    icon = { Icon(dest.icon, contentDescription = null) },
                    label = { Text(dest.label) },
                )
            }
        },
    ) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).imePadding()) {
            G5000NavHost(nav, container, content, prefs, clockChanged)
        }
    }
}

private fun NavHostController.navigateTopLevel(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@Composable
private fun G5000NavHost(nav: NavHostController, container: AppContainer, content: ContentState.Ready, prefs: UserPreferences, clockChanged: Boolean) {
    val catalog = content.catalog
    val pack = container.languagePack
    val premium = container.entitlements.isPremium
    val scope = rememberCoroutineScope()
    val play: (String?, String?) -> Unit = { audioId, fallback ->
        content.audio.play(audioId, fallback, slow = prefs.slowAudio, allowSpeechFallback = prefs.speechFallback)
    }

    NavHost(nav, startDestination = TopLevel.TODAY.route) {
        composable(TopLevel.TODAY.route) {
            val vm = viewModel { TodayViewModel(catalog, container.learning, container.stats, container.preferences, premium, container.clock) }
            TodayScreen(
                viewModel = vm,
                clockChanged = clockChanged,
                pendingAssessment = prefs.pendingAssessmentAfterOnboarding,
                onStart = { mode -> nav.navigate("session?mode=${mode?.name ?: ""}") },
                onAssessment = { nav.navigate("assessment") },
                onOpenPremium = { nav.navigate("premium") },
            )
        }
        composable(TopLevel.STUDY.route) {
            val isPremium by premium.collectAsStateWithLifecycle()
            StudyHomeScreen(
                bands = (1..catalog.bands.count).map { band ->
                    BandOption(band, catalog.bands.label(band), catalog.isBandUnlocked(band, isPremium), band == prefs.selectedBand)
                },
                onStartSession = { mode -> nav.navigate("session?mode=${mode.name}") },
                onPractice = { nav.navigate("practice") },
                onAssessment = { nav.navigate("assessment") },
                onSelectBand = { band -> scope.launch { container.preferences.setSelectedBand(band) } },
                onOpenPremium = { nav.navigate("premium") },
            )
        }
        composable(
            "session?mode={mode}",
            arguments = listOf(navArgument("mode") { type = NavType.StringType; defaultValue = "" }),
        ) { entry ->
            val mode = entry.arguments?.getString("mode")?.takeIf { it.isNotEmpty() }?.let { SessionMode.valueOf(it) }
            val vm = viewModel {
                StudyViewModel(
                    engine = SessionEngine(catalog, container.learning, container.preferences, premium, container.clock),
                    evaluator = container.evaluator,
                    contentVersion = catalog.content.manifest.contentVersion,
                    requestedMode = mode,
                )
            }
            StudySessionScreen(
                viewModel = vm,
                renderer = pack.grammarRenderer,
                specialCharacters = pack.specialCharacters,
                onPlay = play,
                onStopAudio = content.audio::stop,
                onExit = { nav.popBackStack() },
                onMorePractice = { nav.navigate("practice") { popUpTo(TopLevel.TODAY.route) } },
                nowMillis = container.clock::nowMillis,
            )
        }
        composable("practice") {
            val vm = viewModel { PracticeViewModel(catalog, container.learning, container.evaluator, premium) }
            PracticeScreen(vm, pack.specialCharacters, play, content.audio::stop, onExit = { nav.popBackStack() }, onOpenPremium = { nav.navigate("premium") })
        }
        composable("assessment") {
            val vm = viewModel { AssessmentViewModel(catalog, container.assessments, container.preferences, container.evaluator, premium, container.clock) }
            AssessmentScreen(vm, pack.specialCharacters, play, onDone = { nav.popBackStack() }, onOpenPremium = { nav.navigate("premium") })
        }
        composable(TopLevel.LIBRARY.route) {
            val vm = viewModel { LibraryViewModel(catalog, container.learning, premium) }
            LibraryScreen(vm, pack.grammarRenderer, catalog.bands, onOpenEntry = { id -> nav.navigate("entry/$id") })
        }
        composable("entry/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            val parent = androidx.compose.runtime.remember(entry) { nav.getBackStackEntry(TopLevel.LIBRARY.route) }
            val vm = viewModel(parent) { LibraryViewModel(catalog, container.learning, premium) }
            EntryDetailScreen(
                viewModel = vm,
                entryId = entry.arguments?.getString("id").orEmpty(),
                renderer = pack.grammarRenderer,
                onPlay = play,
                onStopAudio = content.audio::stop,
                onOpenPremium = { nav.navigate("premium") },
                onBack = { nav.popBackStack() },
            )
        }
        composable(TopLevel.PROGRESS.route) {
            val vm = viewModel { ProgressViewModel(catalog, container.learning, container.stats, premium, container.clock) }
            ProgressScreen(vm)
        }
        composable(TopLevel.SETTINGS.route) {
            val context = LocalContext.current
            val vm = viewModel {
                SettingsViewModel(
                    preferences = container.preferences,
                    reminders = container.reminderScheduler,
                    learning = container.learning,
                    entitlements = container.entitlements,
                    backupFactory = { container.progressBackup(catalog) },
                    resolver = context.applicationContext.contentResolver,
                    onLocalDataCleared = {},
                )
            }
            SettingsScreen(
                viewModel = vm,
                appInfo = AppInfo(BuildConfig.VERSION_NAME, catalog.content.manifest.contentVersion, !catalog.content.manifest.isReleasePack),
                onOpenPremium = { nav.navigate("premium") },
            )
        }
        composable("premium") {
            PremiumScreen(
                entitlements = container.entitlements,
                freeEntryLimit = catalog.content.manifest.freeEntryLimit,
                totalEntries = catalog.content.manifest.entryCount,
                onBack = { nav.popBackStack() },
            )
        }
    }
}
