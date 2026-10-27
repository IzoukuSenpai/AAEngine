pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/") {
            name = "papermc"
        }
    }
}

rootProject.name = "AAEngine"

include(
    "engine-api",
    "engine-core",
    "engine-paper",
    "engine-items",
    "engine-mechanics",
    "engine-blocks",
    "engine-furniture",
    "engine-recipes",
    "engine-pack",
    "engine-fonts",
    "engine-version-common",
    "engine-integrations",
    "engine-testkit",
)
