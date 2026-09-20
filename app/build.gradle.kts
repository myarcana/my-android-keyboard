plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.offlinekeyboard.ime"
    compileSdk = 36
    ndkVersion = "27.3.13750724"

    defaultConfig {
        applicationId = "com.offlinekeyboard.ime"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.2.0-phase1"

        // Phone only for a device deploy. The emulator's x86_64 copies of the native runtimes
        // are ~40 MB that a physical phone can never load; drop them when the models and libs
        // are in the pack anyway. A self-contained build keeps both, as it always did.
        ndk {
            abiFilters += if (project.hasProperty("pack")) listOf("arm64-v8a")
            else listOf("arm64-v8a", "x86_64")
        }

        // Whether this APK expects a model pack, read at runtime by
        // `com.offlinekeyboard.ime.pack.ModelPack.expected`. It lives in `defaultConfig` because
        // `buildConfigField` is a `defaultConfig`/`buildType` method and not an `android {}` one --
        // calling it from the enclosing block resolves against `android` and fails.
        buildConfigField("boolean", "MODEL_PACK", if (project.hasProperty("pack")) "true" else "false")
    }

    buildFeatures { buildConfig = true }

    /**
     * The payload split, and why it is done by relocating the files rather than excluding them.
     *
     * `jniLibs` drops out of a `-Ppack` build by simply not adding its `srcDir`. Assets have no
     * equivalent switch in this AGP version: `AndroidSourceDirectorySet` exposes only
     * `srcDir(s)`/`setSrcDirs` and no `exclude`, and the variant-level `Sources.assets` has no
     * filter either (`ResourcesPackaging.excludes` covers `res/`, not `assets/`). An
     * `exclude("swipe")` call on the assets source set therefore does not compile -- it resolves
     * to Gradle's `Configuration.exclude(group, module)` and fails with a receiver mismatch,
     * which is the error this block used to produce.
     *
     * So the payload lives in `src/payload/` and is added as an extra asset source directory only
     * for a self-contained build. Relocating rather than filtering has the property that matters:
     * what goes in the APK is decided by which directory is listed, not by a pattern that can
     * silently stop matching when a path moves. Both locations are git-ignored fetched runtimes.
     *
     * `pinyin.bin`, the lexicons and the emoji index stay in the normal `assets/` and stay out of
     * the pack on purpose: they are small, they change with the code that reads them, and moving
     * them into the pack would mean reinstalling the pack whenever a lexicon is rebuilt.
     */
    if (!project.hasProperty("pack")) {
        // A self-contained build ships and reads its own models, exactly as before the split.
        sourceSets["main"].assets.srcDir("src/payload")
    }

    /**
     * The dictation runtime, fetched by tools/fetch_asr_runtime.sh rather than committed.
     *
     * Taken as raw .so files and Kotlin sources instead of an AAR on purpose. An AAR brings a
     * manifest, and a manifest can merge permissions into ours -- the offline guarantee is the
     * one thing in this project that must not depend on a dependency behaving well. This way
     * there is nothing to merge.
     */
    sourceSets["main"].kotlin.srcDir("../third_party/sherpa-onnx/kotlin-api")
    // In a `-Ppack` build the sherpa native libraries ship in the :modelpack APK instead, so this
    // APK stops carrying 60 MB of `.so` on every deploy. The Kotlin binding above still comes
    // from third_party, which is why the sources stay unconditional.
    if (!project.hasProperty("pack")) {
        sourceSets["main"].jniLibs.srcDir("../third_party/sherpa-onnx/jniLibs")
    }

    /**
     * Glide decoding: FUTO's swipe-library, taken the same way and for the same reason.
     *
     * Built rather than downloaded -- it compiles ExecuTorch, which is why it lives behind
     * tools/fetch_swipe_runtime.sh rather than in the repository. The app is written to run
     * without it: when the native library is absent, glide typing falls back to our own Kotlin
     * decoder, which is also the switch the A/B comparison flips.
     */
    sourceSets["main"].kotlin.srcDir("../third_party/swipe-library/kotlin-api")
    if (!project.hasProperty("pack")) {
        sourceSets["main"].jniLibs.srcDir("../third_party/swipe-library/jniLibs")
    }

    androidResources {
        // SenseVoice is read straight out of the APK by the native runtime, which mmaps it and
        // needs it stored uncompressed. Compressing would also force a 239 MB copy to disk on
        // first run, which is exactly what reading from assets avoids.
        //
        // The .pte models cannot be read in place -- ExecuTorch opens a path, not an asset -- so
        // they are copied out on first run and left uncompressed only to keep that copy cheap.
        noCompress += listOf("onnx", "pte")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

/**
 * Unit tests here are as much a reporting tool as a gate: GestureBankReplayTest prints a scored
 * sweep of the gesture thresholds against the collected bank, and a report nobody can see is not
 * a report. There are few enough tests that the noise costs nothing.
 */
tasks.withType<Test>().configureEach {
    // -Dgesture.bank=/path/to/other.jsonl scores a bank that is not the one in data/.
    providers.systemProperty("gesture.bank").orNull?.let { systemProperty("gesture.bank", it) }
    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)

    /**
     * Inline autofill chip styling -- the one thing the platform API cannot do on its own.
     *
     * The style bundle handed to the password manager has to be built by this library on both
     * sides: it carries a UI version that the remote renderer checks before it will draw
     * anything, so a hand-rolled Bundle gets silently ignored rather than styled badly.
     *
     * This is an AAR, which the third_party runtimes deliberately are not -- see the sourceSets
     * above. The reason that rule exists is manifest merging, and this is the case it was
     * written to survive rather than forbid: checkHasNoInternet reads the *merged* manifest, so
     * an AAR that tried to merge INTERNET in would fail the build instead of shipping.
     */
    implementation(libs.androidx.autofill)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
}

/**
 * Requirement 1: the keyboard is 100% offline.
 *
 * This is enforced structurally, not by policy: the app declares no INTERNET permission, so it
 * is incapable of network I/O. This task fails the build if that ever stops being true --
 * including if a transitive dependency's manifest tries to merge one in.
 */
androidComponents.onVariants { variant ->
    val checkTask = tasks.register("check${variant.name.replaceFirstChar { it.uppercase() }}HasNoInternet") {
        val manifest = variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.MERGED_MANIFEST)
        inputs.file(manifest)
        doLast {
            // Parse the XML rather than grepping: the manifest's own explanatory comment mentions
            // the INTERNET permission by name, and a text search matches that comment.
            val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(manifest.get().asFile)
            val declared = doc.getElementsByTagName("uses-permission").let { nodes ->
                (0 until nodes.length).mapNotNull { i ->
                    (nodes.item(i) as org.w3c.dom.Element)
                        .getAttribute("android:name")
                        .takeIf { it.isNotEmpty() }
                }
            }
            val banned = declared.filter { it == "android.permission.INTERNET" }
            if (banned.isNotEmpty()) {
                throw GradleException(
                    "Offline guarantee violated: merged manifest declares $banned. " +
                        "This keyboard must never be able to reach the network."
                )
            }
            logger.lifecycle(
                "Offline guarantee OK (${variant.name}): no INTERNET permission. " +
                    "Declared permissions: ${if (declared.isEmpty()) "none" else declared.joinToString()}"
            )
        }
    }
    // matching{} is lazy and also catches tasks AGP registers after this block runs.
    val assembleName = "assemble${variant.name.replaceFirstChar { it.uppercase() }}"
    tasks.matching { it.name == assembleName }.configureEach { dependsOn(checkTask) }
}
