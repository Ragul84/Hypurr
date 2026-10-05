plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.services)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.ragul84.hypurr"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ragul84.hypurr"
        minSdk = 26
        targetSdk = 35
        // Same version as apps/project.yml MARKETING_VERSION and host/Cargo.toml.
        versionName = "2.4.0"
        versionCode = 20400
        // The push relay (relay/). Placeholder until it is deployed; matches SharedStore.relayURL on iOS.
        buildConfigField("String", "RELAY_URL", "\"https://hypurr-relay.example.workers.dev\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("robolectric.graphicsMode", "NATIVE")
                it.systemProperty("roborazzi.output.dir", rootProject.file("screenshots").path)
                // The protocol vectors are shared with the host and iOS tests.
                it.systemProperty("hypurr.vectors", rootProject.file("../../docs/reference/fixtures/remote-relay-vectors.json").path)
                it.systemProperty("hypurr.taskWire", rootProject.file("../../docs/reference/fixtures/task-wire.json").path)
                it.systemProperty("hypurr.workWire", rootProject.file("../../docs/reference/fixtures/work-wire.json").path)
                it.systemProperty("hypurr.adminWire", rootProject.file("../../docs/reference/fixtures/admin-wire.json").path)
                it.systemProperty("hypurr.chatWire", rootProject.file("../../docs/reference/fixtures/chat-wire.json").path)
                listOf("HYPURR_E2E_LINK", "HYPURR_E2E_BOT", "HYPURR_E2E_PROJECT", "HYPURR_E2E_AGENT").forEach { key ->
                    System.getenv(key)?.let { value -> it.environment(key, value) }
                }
            }
        }
    }
    packaging {
        resources.excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/{AL2.0,LGPL2.1}")
    }
}

roborazzi {
    outputDir.set(rootProject.file("screenshots"))
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.bouncycastle)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.zxing.embedded)
    // Remote screen: WebRTC (Google's libwebrtc, packaged by Stream), receive-only video + data channels.
    implementation(libs.webrtc)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit)
}
