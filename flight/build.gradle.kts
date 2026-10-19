plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    `maven-publish`
}

android {
    namespace = "io.github.angelomarzocchi.flight"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    explicitApi()
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.foundation)
    api(libs.androidx.compose.ui)
    api(libs.androidx.navigation3.runtime)
    api(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.runtime.compose)

    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = project.group.toString()
            artifactId = "flight"
            version = project.version.toString()
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("Flight")
                description.set("Interruptible shared-element flights and spring-driven page presence for Jetpack Compose and Navigation 3.")
                url.set("https://github.com/angelomarzocchi/compose-flight")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("angelomarzocchi")
                        url.set("https://github.com/angelomarzocchi")
                    }
                }
                scm {
                    url.set("https://github.com/angelomarzocchi/compose-flight")
                }
            }
        }
    }
}
