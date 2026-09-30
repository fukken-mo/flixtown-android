plugins { id("com.android.application") }

// -PflixPreview=true builds a side-by-side test copy ("Flix Town Preview", package suffix .preview)
// so it can be installed next to the working app without replacing it.
val flixPreview = (project.findProperty("flixPreview") as String?) == "true"

android {
    namespace = "com.flixtown.tv"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.myflixtown.tv.native"
        minSdk = 23
        targetSdk = 35
        versionCode = 10005
        versionName = "3.3.0-native-preview"
        buildConfigField("String", "PANEL_URL", "\"https://panelsandapps.com/panels/flixtown2027/api/\"")
        buildConfigField("boolean", "PREVIEW", flixPreview.toString())
        buildConfigField("boolean", "DEMO", "false")
        resValue("string", "app_name", "Flix Town")
    }
    buildTypes {
        getByName("debug") {
            if (flixPreview) {
                applicationIdSuffix = ".preview"
                resValue("string", "app_name", "Flix Town Preview")
            }
        }
        // Emulator QA only: offline demo catalog, never talks to the panel or Xtream server.
        create("qa") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".qa"
            buildConfigField("boolean", "DEMO", "true")
            resValue("string", "app_name", "Flix Town QA")
            matchingFallbacks += listOf("debug")
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
