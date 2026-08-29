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

        // Phone + emulator. Native engines land in Phase 2/3/4.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildFeatures { buildConfig = true }

    /**
     * The dictation runtime, fetched by tools/fetch_asr_runtime.sh rather than committed.
     *
     * Taken as raw .so files and Kotlin sources instead of an AAR on purpose. An AAR brings a
     * manifest, and a manifest can merge permissions into ours -- the offline guarantee is the
     * one thing in this project that must not depend on a dependency behaving well. This way
     * there is nothing to merge.
     */
    sourceSets["main"].kotlin.srcDir("../third_party/sherpa-onnx/kotlin-api")
    sourceSets["main"].jniLibs.srcDir("../third_party/sherpa-onnx/jniLibs")

    androidResources {
        // SenseVoice is read straight out of the APK by the native runtime, which mmaps it and
        // needs it stored uncompressed. Compressing would also force a 239 MB copy to disk on
        // first run, which is exactly what reading from assets avoids.
        noCompress += listOf("onnx")
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
