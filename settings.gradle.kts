// Copyright 2026 wxxsfxyzm
// SPDX-License-Identifier: Apache-2.0

pluginManagement {
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

        // Same remote miuix repository as InstallerX; GitHub Packages requires authentication.
        // Set gpr.user/gpr.key in ~/.gradle/gradle.properties or GITHUB_ACTOR/GITHUB_TOKEN.
        val gprUser = providers.gradleProperty("gpr.user")
            .orElse(providers.environmentVariable("GITHUB_ACTOR"))
        val gprKey = providers.gradleProperty("gpr.key")
            .orElse(providers.environmentVariable("GITHUB_TOKEN"))
        maven {
            name = "GitHubPackagesMiuix"
            url = uri("https://maven.pkg.github.com/compose-miuix-ui/miuix")
            content { includeGroup("top.yukonga.miuix.kmp") }
            if (gprUser.isPresent && gprKey.isPresent) {
                credentials {
                    username = gprUser.get()
                    password = gprKey.get()
                }
            }
        }
    }
}

rootProject.name = "HyperSpectrum"
include(":app")

// Uncomment to use the sibling miuix checkout instead of the remote artifacts.
// val localMiuix = file("../miuix")
// require(localMiuix.resolve("settings.gradle.kts").isFile) {
//     "Missing local miuix checkout: ${localMiuix.absolutePath}"
// }
// includeBuild(localMiuix) {
//     dependencySubstitution {
//         listOf("core", "ui", "shader", "blur", "preference", "icons", "squircle", "glass", "glass-icons")
//             .forEach { component ->
//                 substitute(module("top.yukonga.miuix.kmp:miuix-$component-android"))
//                     .using(project(":miuix-$component"))
//             }
//     }
// }
