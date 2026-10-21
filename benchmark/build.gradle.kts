// Plays the demo's script on a real phone, once per engine, and measures it: frame times, CPU on each
// thread, memory. Run it with ./gradlew :benchmark:connectedBenchmarkAndroidTest (see the README).
plugins {
    alias(libs.plugins.android.test)
}

android {
    namespace = "io.github.angelomarzocchi.flight.benchmark"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildTypes {
        // Matches the demo's: the app under test is its release-like build.
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    targetProjectPath = ":demo"
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

androidComponents {
    // Only the benchmark build measures anything worth having.
    beforeVariants(selector().all()) { it.enable = it.buildType == "benchmark" }
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
