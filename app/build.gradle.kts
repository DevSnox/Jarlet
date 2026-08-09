plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
    application
    id("org.graalvm.buildtools.native") version "1.1.8"
}

application {
    mainClass.set("me.devsnox.jarlet.MainKt")
}

// jarlet-sys.conf is the single source of truth for TEMPLATE_FILENAME
// (currently "jarlet.toml", see ServerPaths.templateFilename()) -- read it
// here too instead of a second hardcoded literal, so the native-image
// resource pattern below can't silently drift from the runtime filename.
val templateFilename = file("src/main/resources/jarlet-sys.conf")
    .readLines()
    .firstOrNull { it.startsWith("TEMPLATE_FILENAME=") }
    ?.substringAfter("=")
    ?: throw GradleException("TEMPLATE_FILENAME missing from src/main/resources/jarlet-sys.conf")

graalvmNative {
    toolchainDetection.set(true)
    binaries {
        named("main") {
            imageName.set("jarlet")
            mainClass.set("me.devsnox.jarlet.MainKt")
            buildArgs.add("--no-fallback")
            javaLauncher.set(
                javaToolchains.launcherFor {
                    languageVersion.set(JavaLanguageVersion.of(25))
                }
            )
            // Classpath resources aren't bundled into a native image unless
            // explicitly registered -- SysConfig.default() (jarlet-sys.conf)
            // reads its bundled file via getResourceAsStream at runtime, so
            // without this it would resolve fine on the JVM but be silently
            // missing from the native binary. jarlet.toml is registered
            // alongside it for the same reason. Both files live flat under
            // app/src/main/resources/; resource-config.json patterns match
            // the registered classpath resource NAME, which for a
            // root-level resource has no leading slash (GraalVM's own
            // examples match directory-relative paths like
            // ".*/Resource0.txt$" -- a root file's name is just
            // "jarlet.toml", never "/jarlet.toml"), so these patterns must
            // not anchor one either.
            resources {
                includedPatterns.add("^jarlet-sys\\.conf$")
                includedPatterns.add("^${Regex.escape(templateFilename)}$")
            }
        }
        // Dev-only, additive alongside "main" -- never used for release
        // artifacts. Invoke explicitly with `./gradlew nativeQuickCompile`;
        // output lands at build/native/nativeQuickCompile/jarlet-quick
        // (plugin derives both the task name and output dir from the
        // binary name "quick", separate from nativeCompile's "main"
        // output, so the two never collide or overwrite each other).
        //
        // Measured on this machine (Apple Silicon, 6 cores/8GB RAM,
        // GraalVM Oracle 25.0.4) via `time ./gradlew clean nativeCompile`
        // vs `time ./gradlew clean nativeQuickCompile`, both from a clean
        // build, both averaged from real runs (not estimates):
        //   release (nativeCompile):      2m 53s wall (native-image itself:
        //                                 2m 38s) -- 41.86MB binary
        //   quick   (nativeQuickCompile): 2m 01s wall (native-image itself:
        //                                 1m 40s) -- 34.75MB binary
        // ~37% faster native-image phase, ~29% faster wall clock. Per
        // -H:+BuildReport on the release build, "Compiling methods" (-O2)
        // was the dominant phase at 78.1s/~158s (~49%) of native-image
        // time; quickBuild cuts that phase to ~19-27s. The quick binary
        // also came out *smaller*, not larger as commonly assumed --
        // -O2 GraalVM builds do more inlining/specialization, which adds
        // code size here. Quick-build's real cost is slower *runtime*
        // performance (no PGO/-O2), not build artifact size -- acceptable
        // for local dev iteration, not for what we ship.
        //
        // Builder heap tuning (-J-Xmx...) was measured and NOT added:
        // both binaries already stay well under the auto-detected 80%-
        // of-RAM heap budget (peak RSS ~1.4-1.5GB out of 6.49GB available)
        // and GC overhead is ~9-10% of build time in both configurations,
        // so there's no GC pressure here for heap tuning to relieve.
        create("quick") {
            imageName.set("jarlet-quick")
            mainClass.set("me.devsnox.jarlet.MainKt")
            quickBuild.set(true)
            buildArgs.add("--no-fallback")
            javaLauncher.set(
                javaToolchains.launcherFor {
                    languageVersion.set(JavaLanguageVersion.of(25))
                }
            )
            // Same resource registration as "main" above -- required for
            // every binary independently, the plugin does not share this
            // config between named binaries.
            resources {
                includedPatterns.add("^jarlet-sys\\.conf$")
                includedPatterns.add("^${Regex.escape(templateFilename)}$")
            }
        }
    }
}

// Jarlet's own version lives in gradle.properties (jarletVersion*) -- the
// only source of truth. generateVersion below writes it into a single
// generated Kotlin constant (me.devsnox.jarlet.config.JarletVersion) instead
// of a bundled resource file that would need runtime parsing/validation:
// Gradle already knows these values are well-formed at build time, so
// there's nothing left to validate when the app reads them back.
version = run {
    val core = "${property("jarletVersionMajor")}.${property("jarletVersionMinor")}.${property("jarletVersionPatch")}"
    val stage = property("jarletVersionStage").toString()
    if (stage.equals("STABLE", ignoreCase = true)) core else "$core-${stage.lowercase()}.${property("jarletVersionRoll")}"
}

val generatedVersionDir = layout.buildDirectory.dir("generated/source/version/kotlin")

val generateVersion by tasks.registering {
    val outputDir = generatedVersionDir
    val versionValue = version.toString()
    outputs.dir(outputDir)

    doLast {
        val file = outputDir.get().file("me/devsnox/jarlet/config/JarletVersion.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            package me.devsnox.jarlet.config

            // Generated by app/build.gradle.kts's generateVersion task from
            // gradle.properties (jarletVersion*) -- do not edit directly.
            object JarletVersion {
                const val VERSION = "$versionValue"
            }
            """.trimIndent() + "\n"
        )
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
        vendor.set(JvmVendorSpec.ORACLE)
    }
}

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
        vendor.set(JvmVendorSpec.ORACLE)
    }
}

sourceSets {
    main {
        kotlin.srcDir(generateVersion.map { generatedVersionDir.get() })
    }
    // Separate from `test` on purpose: these hit real third-party APIs
    // (Hangar/Spiget/GitHub/PaperMC), which `test` must never do implicitly
    // -- agent.md's "no internet without explicit permission" rule. Kept as
    // its own source set + Test task (below) so plain `./gradlew test`
    // (and `gradle build`, which depends on `test`) never touches the
    // network; run these explicitly via `./gradlew integrationTest`.
    //
    // Sources live under src/main/test/kotlin/integrationTest/ rather than a
    // top-level src/integrationTest/ so they travel alongside the main
    // sourceSet in tooling/IDE views instead of looking like a second,
    // sibling module -- "integrationTest" here is just a path segment under
    // kotlin.srcDir, not a package; Kotlin resolves classes by the package
    // declarations beneath it (me.devsnox.jarlet.adapter.*), same as always.
    create("integrationTest") {
        kotlin.srcDir("src/main/test/kotlin/integrationTest")
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }
}

val integrationTestImplementation by configurations.getting {
    extendsFrom(configurations.testImplementation.get())
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.github.ajalt.clikt:clikt:5.1.0")
    implementation("com.github.ajalt.mordant:mordant:3.0.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.tomlj:tomlj:1.1.1")
    implementation("org.snakeyaml:snakeyaml-engine:3.1.1")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<Test>("integrationTest") {
    description = "Smoke-tests each source adapter against its real external API (Hangar/Spiget/GitHub/PaperMC). Not run by `test` or `build` -- invoke explicitly."
    group = "verification"
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
    useJUnitPlatform()
}