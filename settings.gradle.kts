pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "MFBooks"
include(":shared", ":server")
// The server's Docker build has no Android SDK.
if (System.getenv("MFBOOKS_SERVER_ONLY") == null) include(":app")
