import java.util.Properties

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
val perArchitecture = providers.gradleProperty("perArchitecture").orNull == "true"
val privateAdmin = providers.gradleProperty("privateAdmin").orNull == "true"
val localConfig = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val buildAbis = providers.gradleProperty("targetAbi").orNull?.let { listOf(it) }
    ?: listOf("arm64-v8a", "armeabi-v7a", "x86_64")
require(buildAbis.all { it in listOf("arm64-v8a", "armeabi-v7a", "x86_64") }) { "Unsupported targetAbi" }
android {
    namespace = "app.pulso.music"
    compileSdk = 36
    defaultConfig {
        applicationId = "app.pulso.music"
        minSdk = 29
        targetSdk = 35
        versionCode = 34
        versionName = "0.7.22"
        buildConfigField("String", "METRICS_URL", "\"${localConfig.getProperty("pulso.metrics.url", "")}\"")
        buildConfigField("String", "METRICS_KEY", "\"${localConfig.getProperty("pulso.metrics.key", "")}\"")
        buildConfigField("String", "UPDATE_REPOSITORY", "\"ShagySoaD/pulso-releases\"")
        if (!perArchitecture) ndk { abiFilters += buildAbis }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    splits {
        abi {
            isEnable = perArchitecture
            reset()
            include(*buildAbis.toTypedArray())
            isUniversalApk = false
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    ndkVersion = "28.2.13676358"
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    buildFeatures { compose = true; buildConfig = true }
    sourceSets.getByName("main").java.srcDir(if (privateAdmin) "src/privateAdmin/java" else "src/publicAdmin/java")
    testOptions.unitTests.all { it.systemProperty("pulso.liveSpotify", providers.gradleProperty("liveSpotify").orNull == "true") }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            isShrinkResources = false
            // Preserve update compatibility with the installed private beta.
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    packaging { jniLibs { useLegacyPackaging = true }; resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1") }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-session:1.8.0")
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
