import java.util.Properties

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
val scraperSecrets = Properties().apply {
    rootProject.file("screenscraper-local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun quotedBuildValue(key: String): String = "\"" + scraperSecrets.getProperty(key, "")
    .replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""
android {
    namespace = "au.orbit.frontend"
    compileSdk = 36
    defaultConfig {
        applicationId = "au.orbit.frontend"; minSdk = 29; targetSdk = 36; versionCode = 1; versionName = "0.1"
        buildConfigField("String", "SCREENSCRAPER_DEV_ID", quotedBuildValue("DEV_ID"))
        buildConfigField("String", "SCREENSCRAPER_DEV_PASSWORD", quotedBuildValue("DEV_PASSWORD"))
    }
    buildFeatures { buildConfig = true }
    // ES-DE themes keep their artwork/fonts/XML in _inc; Android's default asset filter skips it.
    androidResources { ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:CVS:thumbs.db:picasa.ini:*~" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("com.caverock:androidsvg-aar:1.4")
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
