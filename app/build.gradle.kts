plugins { id("com.android.application") }

// -PflixPreview=true builds a side-by-side test copy ("Flix Town Preview", package suffix .preview)
// so it can be installed next to the working app without replacing it.
val flixPreview = (project.findProperty("flixPreview") as String?) == "true"
// -PflixVersionCode / -PflixVersionName build the same source as another release (update tests).
val flixVersionCode = (project.findProperty("flixVersionCode") as String?)?.toInt() ?: 10016
val flixVersionName = (project.findProperty("flixVersionName") as String?) ?: "3.7.1"
val panelUrl = "https://panelsandapps.com/panels/flixtown2027/api/"
// The permanent release key is never stored in the repository: CI decodes it from repository
// secrets into a file and passes its location and passwords through these environment variables.
val releaseKeystore = System.getenv("FLIXTOWN_RELEASE_KEYSTORE")?.let { file(it) }?.takeIf { it.isFile }

android {
    namespace = "com.flixtown.tv"
    compileSdk = 36
    signingConfigs {
        // Fixed key for the side-by-side Preview and QA test apps only (separate package IDs), so
        // each test build installs over the previous one. Never used for com.myflixtown.tv.native.
        create("test") {
            storeFile = rootProject.file("signing/flixtown-test.p12")
            storePassword = "flixtown-test"; keyAlias = "flixtown-test"; keyPassword = "flixtown-test"
        }
        if (releaseKeystore != null) create("release") {
            storeFile = releaseKeystore
            storePassword = System.getenv("FLIXTOWN_RELEASE_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("FLIXTOWN_RELEASE_KEY_ALIAS")
            keyPassword = System.getenv("FLIXTOWN_RELEASE_KEY_PASSWORD")
        }
    }
    defaultConfig {
        applicationId = "com.myflixtown.tv.native"
        minSdk = 23
        targetSdk = 35
        versionCode = flixVersionCode
        versionName = flixVersionName
        buildConfigField("String", "PANEL_URL", "\"$panelUrl\"")
        buildConfigField("String", "UPDATE_URL", "\"${panelUrl}app-update.php\"")
        buildConfigField("boolean", "PREVIEW", flixPreview.toString())
        buildConfigField("boolean", "DEMO", "false")
        resValue("string", "app_name", "Flix Town")
    }
    buildTypes {
        getByName("debug") {
            if (flixPreview) {
                applicationIdSuffix = ".preview"
                signingConfig = signingConfigs.getByName("test")
                resValue("string", "app_name", "Flix Town Preview")
            }
        }
        // Emulator QA only: offline demo catalog (no panel or Xtream server); only the update check
        // is real, against a test panel on the host.
        create("qa") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".qa"
            signingConfig = signingConfigs.getByName("test")
            buildConfigField("boolean", "DEMO", "true")
            // Emulator QA: the update check talks to a real panel on the host machine (10.0.2.2).
            buildConfigField("String", "UPDATE_URL", "\"${(project.findProperty("flixQaUpdateUrl") as String?) ?: "http://10.0.2.2:8787/api/app-update.php"}\"")
            resValue("string", "app_name", "Flix Town QA")
            matchingFallbacks += listOf("debug")
        }
        getByName("release") {
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { buildConfig = true; resValues = true }
    testOptions { unitTests.isReturnDefaultValues = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.leanback:leanback-grid:1.0.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.core:core:1.16.0")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
