import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing is read from keystore.properties (never committed) or from the
// NIMER_KEYSTORE_* environment variables (CI secrets). Without either, the release
// build is produced unsigned and must not be published.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(prop: String, env: String): String? =
    (keystoreProps.getProperty(prop) ?: System.getenv(env))?.takeIf { it.isNotBlank() }

val ksFile = signingValue("storeFile", "NIMER_KEYSTORE_FILE")
val ksPassword = signingValue("storePassword", "NIMER_KEYSTORE_PASSWORD")
val ksAlias = signingValue("keyAlias", "NIMER_KEY_ALIAS")
val ksKeyPassword = signingValue("keyPassword", "NIMER_KEY_PASSWORD") ?: ksPassword
val hasReleaseKey = ksFile != null && ksPassword != null && ksAlias != null && rootProject.file(ksFile).exists()

android {
    namespace = "com.nimertv.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nimertv.app"
        minSdk = 23
        targetSdk = 35
        versionCode = (project.property("nimerVersionCode") as String).toInt()
        versionName = project.property("nimerVersionName") as String
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(ksFile!!)
                storePassword = ksPassword
                keyAlias = ksAlias
                keyPassword = ksKeyPassword
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    testOptions.unitTests {
        isReturnDefaultValues = true
        isIncludeAndroidResources = true
        all {
            it.systemProperty("robolectric.graphicsMode", "NATIVE")
            // Screenshots of the rendered screens are written to app/build/screens/.
            it.systemProperty("roborazzi.test.record", "true")
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")

    // Screen rendering and remote-navigation tests run on the JVM (Robolectric); no device needed.
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.32.2")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.32.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
