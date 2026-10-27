import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage

plugins {
    id("g5000.android.application")
}

/** Owner-controlled identity (spec §2.1). The default is a deliberate placeholder. */
val placeholderApplicationId = "invalid.placeholder.german5000"
val appId: String = providers.gradleProperty("g5000.applicationId").getOrElse(placeholderApplicationId)
val playLicenseKey: String = providers.gradleProperty("g5000.playLicenseKey").getOrElse("")
/** Internal-testing escape hatch only: lets a release-type build carry pilot content. */
val allowPilotContentInRelease: Boolean = providers.gradleProperty("g5000.allowPilotContentInRelease").map { it.toBoolean() }.getOrElse(false)

android {
    namespace = "com.vocab5000.german"

    defaultConfig {
        applicationId = appId
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "PLAY_LICENSE_KEY", "\"$playLicenseKey\"")
        buildConfigField("String", "CONTENT_PACK_ID", "\"german5000\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        // Upload keys never live in the repository; supply them via Gradle properties or env.
        val storeFile = providers.gradleProperty("g5000.signing.storeFile").orNull
        if (storeFile != null) {
            create("upload") {
                this.storeFile = file(storeFile)
                storePassword = providers.gradleProperty("g5000.signing.storePassword").orNull
                keyAlias = providers.gradleProperty("g5000.signing.keyAlias").orNull
                keyPassword = providers.gradleProperty("g5000.signing.keyPassword").orNull
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("upload")?.let { signingConfig = it }
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:learning"))
    implementation(project(":core:content"))
    implementation(project(":core:database"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:audio"))
    implementation(project(":core:billing"))
    implementation(project(":core:settings"))
    implementation(project(":languages:german"))
    implementation(project(":features:onboarding"))
    implementation(project(":features:today"))
    implementation(project(":features:study"))
    implementation(project(":features:library"))
    implementation(project(":features:progress"))
    implementation(project(":features:premium"))
    implementation(project(":features:settings"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.compose.material3.navigation.suite)
    implementation(libs.work.runtime)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.work.testing)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso)
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

// ---- Content pack generation -------------------------------------------------------------
// The read-only content pack is generated from content/german5000/source at build time and
// validated by the same rules the app applies at runtime. Release variants require a
// launch-ready pack (exactly 5,000 approved, rights-cleared entries with audio).

val contentPipeline: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
    }
}

dependencies {
    contentPipeline(project(":tools:content-pipeline"))
}

abstract class BuildContentPackTask : JavaExec() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDir: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val audioFiles: ConfigurableFileCollection

    @get:Input
    abstract val mode: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    override fun exec() {
        args(
            "build",
            sourceDir.get().asFile.absolutePath,
            outputDir.get().asFile.absolutePath,
            reportFile.get().asFile.absolutePath,
            mode.get(),
        )
        super.exec()
    }
}

abstract class CheckReleaseIdentityTask : DefaultTask() {
    @get:Input
    abstract val applicationId: Property<String>

    @get:Input
    abstract val placeholder: Property<String>

    @TaskAction
    fun check() {
        if (applicationId.get() == placeholder.get()) {
            throw GradleException(
                "Release builds need an owner-controlled application ID. Pass -Pg5000.applicationId=<your.package> " +
                    "(see docs/RELEASE.md). The placeholder '${placeholder.get()}' must never ship.",
            )
        }
    }
}

val contentSource = rootProject.layout.projectDirectory.dir("content/german5000/source")
val contentAudio = rootProject.layout.projectDirectory.dir("content/german5000/audio")

androidComponents {
    onVariants { variant ->
        val isRelease = variant.buildType == "release"
        val packMode = if (isRelease && !allowPilotContentInRelease) "release" else "pilot"
        val cap = variant.name.replaceFirstChar { it.uppercase() }
        val packTask = tasks.register<BuildContentPackTask>("build${cap}ContentPack") {
            group = "german5000"
            description = "Builds and validates the ${variant.name} content pack ($packMode mode)"
            classpath = contentPipeline
            mainClass.set("com.vocab5000.tools.content.ContentPipelineKt")
            sourceDir.set(contentSource)
            audioFiles.from(contentAudio.asFileTree)
            mode.set(packMode)
            outputDir.set(layout.buildDirectory.dir("generated/content/${variant.name}"))
            reportFile.set(layout.buildDirectory.file("reports/content/${variant.name}-validation.txt"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(packTask, BuildContentPackTask::outputDir)

        if (isRelease) {
            val identity = tasks.register<CheckReleaseIdentityTask>("check${cap}Identity") {
                group = "german5000"
                applicationId.set(appId)
                placeholder.set(placeholderApplicationId)
            }
            tasks.matching { it.name == "pre${cap}Build" }.configureEach { dependsOn(identity) }
        }
    }
}
