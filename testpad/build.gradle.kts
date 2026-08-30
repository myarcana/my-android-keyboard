plugins {
    alias(libs.plugins.android.application)
}

/**
 * The test pad, which is not the keyboard.
 *
 * It was a second launcher activity inside the keyboard's APK for as long as there was only one
 * APK, and that was always a convenience rather than a reason: a scratch text field shares no
 * code with an input method, and being installed alongside one made it look like a feature of it.
 *
 * The Gesture Lab is the opposite case and stays where it is. It is not a separate concern
 * wearing the same icon -- it reads gestures the keyboard hands it through an in-process
 * singleton, and it reads the bank out of the keyboard's own sandbox. Those are the same
 * process by construction, and prising them apart would need an IPC bridge and a way for two
 * sandboxes to share one file, which is the thing this project has just decided not to do.
 */
android {
    namespace = "com.offlinekeyboard.testpad"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.offlinekeyboard.testpad"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
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

dependencies {
    implementation(libs.androidx.core.ktx)
}

/**
 * The same offline check the keyboard gets, for the same reason.
 *
 * This app has a text field in it that the keyboard's owner types into while testing, which is
 * enough to make "it cannot reach the network" worth enforcing mechanically rather than
 * remembering. Duplicated rather than shared because a buildSrc convention plugin to hold twenty
 * lines would be a worse trade.
 */
androidComponents.onVariants { variant ->
    val checkTask = tasks.register("check${variant.name.replaceFirstChar { it.uppercase() }}HasNoInternet") {
        val manifest = variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.MERGED_MANIFEST)
        inputs.file(manifest)
        doLast {
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
                    "Offline guarantee violated: merged manifest declares $banned."
                )
            }
            logger.lifecycle(
                "Offline guarantee OK (${variant.name}): no INTERNET permission. " +
                    "Declared permissions: ${if (declared.isEmpty()) "none" else declared.joinToString()}"
            )
        }
    }
    val assembleName = "assemble${variant.name.replaceFirstChar { it.uppercase() }}"
    tasks.matching { it.name == assembleName }.configureEach { dependsOn(checkTask) }
}
