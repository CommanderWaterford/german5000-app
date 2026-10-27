pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "german5000"

include(
    ":apps:german5000",
    ":core:model",
    ":core:learning",
    ":core:content",
    ":core:database",
    ":core:designsystem",
    ":core:audio",
    ":core:billing",
    ":core:settings",
    ":features:onboarding",
    ":features:today",
    ":features:study",
    ":features:library",
    ":features:progress",
    ":features:premium",
    ":features:settings",
    ":languages:german",
    ":tools:content-pipeline",
)
