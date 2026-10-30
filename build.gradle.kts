import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.11.0"
}

// Latest released Rider. Verified on every PR (see `pluginVerification` below).
val CURRENT_RIDER = "2026.2"

group = "com.github.iamr8"
// Single source of truth for the plugin version (also consumed by CI / releases).
// CI overrides it for EAP dev builds via -PpluginVersion=<date+build> (see build.yml eap job).
version = providers.gradleProperty("pluginVersion").orNull?.takeIf { it.isNotBlank() }
    ?: file("VERSION").readText().trim()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Locally: build against the installed Rider (no big download, exact API match).
        // Elsewhere (CI, no local install): download the matching Rider SDK.
        // NOTE: a local Rider 2026.2 (build 262) install cannot be resolved by this plugin version —
        // it fails with "Could not find a field for name … ModuleDescriptor". Fixing that needs
        // IntelliJ Platform Gradle Plugin >= 2.12, which requires Gradle 9 and a JDK 25 toolchain
        // (build 262 ships Java-25 bytecode). Tracked as a follow-up toolchain upgrade; until then
        // build against the downloaded 2026.1.4 SDK.
        if (file("/Applications/Rider.app").exists()) {
            local("/Applications/Rider.app")
        } else {
            // useInstaller = false: IPGP doesn't support Rider *installer* distributions and warns
            // on every build otherwise ("Using Rider … with `useInstaller = true` is currently not
            // supported"). Same flag the pluginVerification IDEs use.
            rider("2026.1.4", useInstaller = false)
        }
        testFramework(TestFrameworkType.Platform)
    }

    // Bundled with the plugin; used by the pure JSON parser.
    implementation("com.google.code.gson:gson:2.15.0")

    testImplementation("junit:junit:4.13.2")
}

// The .NET helper engine (helper/). Published framework-dependent; shipped under <plugin>/helper/.
val helperOut = layout.buildDirectory.dir("helper")
val buildHelper by tasks.registering(Exec::class) {
    group = "build"
    description = "Publishes the .NET helper engine into build/helper."
    // Exclude bin/obj: their own build output would otherwise make this task's up-to-date check
    // never settle (every dotnet build touches them, even when nothing under source changed).
    inputs.files(fileTree("helper/Core") { exclude("bin/**", "obj/**") })
    inputs.files(fileTree("helper/Helper") { exclude("bin/**", "obj/**") })
    inputs.file("helper/Directory.Build.props")
    outputs.dir(helperOut)
    doFirst {
        // A stale file from a previous publish (e.g. one -p:UseAppHost=false flip) must never
        // survive into the zip just because dotnet publish doesn't remove outputs on its own.
        delete(helperOut)
    }
    commandLine(
        "dotnet", "publish", "helper/Helper/Helper.csproj",
        "-c", "Release",
        "-o", helperOut.get().asFile.absolutePath,
        "-p:Version=${project.version.toString().substringBefore('-')}",
        // The CI-built native apphost is platform-specific; the plugin always runs it via
        // `dotnet Helper.dll`, so the apphost is dead weight, not a usable entry point.
        "-p:UseAppHost=false",
        "--nologo",
    )
}

tasks.named<org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask>("prepareSandbox") {
    dependsOn(buildHelper)
    // <sandbox>/plugins/<pluginName>/helper - HelperService looks exactly there.
    from(helperOut) { into(pluginName.map { "$it/helper" }) }
}

intellijPlatform {
    // No custom settings/searchable options in this plugin.
    buildSearchableOptions = false

    pluginConfiguration {
        ideaVersion {
            // 243 = Rider 2024.3, the oldest release on JBR 21 (our Java-21 bytecode needs it).
            sinceBuild = "243"
            // No upper bound: stay compatible with future IDE builds (Marketplace-friendly).
            untilBuild = provider { null }
        }
    }

    // `publishPlugin` uses this token (JetBrains Marketplace); provided via env in CI.
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }

    // `verifyPlugin` (IntelliJ Plugin Verifier). Two scopes, because every extra IDE is a
    // multi-GB download:
    //   -PverifierIdes=current -> the current Rider only. Used by the PR check in build.yml, so
    //                             API breakage surfaces on the PR instead of a weekly run.
    //   (default)              -> the whole supported range: the floor (2024.3), a mid build and
    //                             both current releases. This is what keeps since-build 243 honest,
    //                             and is what compatibility.yml runs.
    pluginVerification {
        ides {
            // useInstaller = false: Rider is verified from the non-installer distribution.
            if (providers.gradleProperty("verifierIdes").orNull == "current") {
                create(IntelliJPlatformType.Rider, CURRENT_RIDER) { useInstaller = false }
            } else {
                create(IntelliJPlatformType.Rider, "2024.3.6") { useInstaller = false }
                create(IntelliJPlatformType.Rider, "2025.2.4") { useInstaller = false }
                create(IntelliJPlatformType.Rider, "2026.1.4") { useInstaller = false }
                create(IntelliJPlatformType.Rider, CURRENT_RIDER) { useInstaller = false }
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        // Emit real Java default methods instead of Kotlin delegating overrides, so implementing
        // platform interfaces (e.g. ToolWindowFactory) doesn't generate usages of their
        // deprecated/experimental default methods (Plugin Verifier warnings).
        // Stable flag replacing -Xjvm-default=all; `all` maps to `no-compatibility`
        // (the flag accepts only disable|enable|no-compatibility).
        freeCompilerArgs.add("-jvm-default=no-compatibility")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.test {
    useJUnit()
}
