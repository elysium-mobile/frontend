import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.secrets.gradle)
    id("com.google.devtools.ksp")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.elysium.softwork"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.elysium.softwork"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val properties = Properties()
        val localPropertiesFile = project.rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            properties.load(localPropertiesFile.inputStream())
        }
        // BuildConfig fallbacks. The Secrets Gradle Plugin overrides these at build time
        // by reading secrets.properties (gitignore) and falling back to
        // secrets.defaults.properties (committed). Empty defaults keep the build green
        // when a developer hasn't created secrets.properties yet.
        buildConfigField("String", "BACKEND_BASE_URL", "\"${System.getenv("BACKEND_BASE_URL") ?: properties.getProperty("backend.base.url", "")}\"")
        buildConfigField("String", "API_KEY_GEMINI", "\"${System.getenv("API_KEY_GEMINI") ?: properties.getProperty("api.key.gemini", "")}\"")
        buildConfigField("String", "GOOGLE_OAUTH_CLIENT", "\"${System.getenv("GOOGLE_OAUTH_CLIENT") ?: properties.getProperty("google.oauth.client", "")}\"")
        buildConfigField("String", "API_KEY_EXTERNAL_SERVICE", "\"${System.getenv("API_KEY_EXTERNAL_SERVICE") ?: properties.getProperty("api.key.external.service", "")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

secrets {
    propertiesFileName = "secrets.properties"
    defaultPropertiesFileName = "secrets.defaults.properties"
    ignoreList.add("sdk.*")
    ignoreList.add("keystore.*")
}

dependencies {
    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Retrofit + OkHttp (OkHttp pinned explicitly so the version is locked, and we can
    // gate the logging interceptor to debug builds only).
    implementation(libs.retrofit)
    implementation(libs.converter.gson)
    implementation(libs.okhttp)
    debugImplementation(libs.okhttp.logging.interceptor)

    // Room (ORM)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.room.compiler)
    annotationProcessor(libs.androidx.room.room.compiler)
    implementation(libs.androidx.room.ktx)


    // Images
    implementation(libs.coil.compose)

    // Fonts
    implementation(libs.androidx.compose.ui.text.google.fonts)

    // AppCompat (required for AppCompatDelegate.setApplicationLocales back-port)
    implementation(libs.androidx.appcompat)

    // Native Google Sign-In via the Credential Manager (the native Gmail identity handshake —
    // the only external identity path the client is permitted to touch directly). The
    // play-services-auth artifact provides the Google ID credential provider; `googleid` exposes
    // GetSignInWithGoogleOption / GoogleIdTokenCredential.
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)

    // Firebase BoM
    implementation(platform(libs.firebase.bom))

    // TODO: Add the dependencies for Firebase products you want to use
    // When using the BoM, don't specify versions in Firebase dependencies
    // https://firebase.google.com/docs/android/setup#available-libraries

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // Pinned explicitly so the runtime version on-device matches what
    // `kotlinx-coroutines-test` was compiled against. Without this pin, transitive
    // resolution can leave the APK with an older `kotlinx-coroutines-core` that
    // lacks `runBlockingK$default`, which the Compose UI test machinery requires.
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.lifecycle.runtime.testing)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}