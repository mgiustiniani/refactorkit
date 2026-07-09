pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "refactorkit"

include(
    ":modules:refactorkit-core",
    ":modules:refactorkit-java",
    ":modules:refactorkit-cli",
    ":modules:refactorkit-daemon",
    ":modules:refactorkit-lsp",
    ":modules:refactorkit-mcp",
    ":modules:refactorkit-web-importer",
    ":modules:refactorkit-tree-sitter",
    ":modules:refactorkit-testkit",
)
