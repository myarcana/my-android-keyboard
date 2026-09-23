plugins {
    alias(libs.plugins.android.application)
}

/**
 * The demo recorder's hands, and nothing else.
 *
 * An empty app whose only content is an instrumentation test that injects touches. It exists
 * because of one rule with no way around it: `am instrument` kills the process of the app it
 * targets, both when it starts and when it finishes. Injecting from the keyboard's own package
 * closed the keyboard; injecting from the test pad's restarted the test pad, taking the keyboard
 * down with it and landing the first gestures on the launcher. Injecting from a third package
 * that owns nothing on screen leaves both of them alone.
 *
 * It has no activity, no permissions and no code in `src/main` -- the instrumentation borrows
 * `UiAutomation`'s INJECT_EVENTS, which is the only permission involved. See
 * `tools/demo/demo.sh` and the skill in `.claude/skills/demo-video/`.
 */
android {
    namespace = "com.offlinekeyboard.demodriver"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.offlinekeyboard.demodriver"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
