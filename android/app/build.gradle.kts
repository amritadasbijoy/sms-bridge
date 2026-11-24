import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "app.smsbridge"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.smsbridge"
        minSdk = 26
        targetSdk = 36
        versionCode = 24
        versionName = "0.11.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        // Plain HTTP only to the emulator's view of the development machine (debug and releaseTest).
        buildConfigField("boolean", "ALLOW_LOCAL_HTTP", "false")
        // Shown to new users ("open … on your computer"); any server's QR code works.
        buildConfigField("String", "DEFAULT_SITE", "\"sms.techistbd.com\"")
    }

    // The release key lives outside the repository: ~/.smsbridge/keystore.properties
    // (or the file named by SMSBRIDGE_KEYSTORE_PROPERTIES). Without it, release builds stay unsigned.
    val keystoreFile = (System.getenv("SMSBRIDGE_KEYSTORE_PROPERTIES") ?: "${System.getProperty("user.home")}/.smsbridge/keystore.properties")
        .let { file(it) }
    val releaseSigning = if (keystoreFile.exists()) {
        val props = Properties().apply { keystoreFile.inputStream().use { load(it) } }
        signingConfigs.create("release") {
            storeFile = file(props.getProperty("storeFile"))
            storePassword = props.getProperty("storePassword")
            keyAlias = props.getProperty("keyAlias")
            keyPassword = props.getProperty("keyPassword")
        }
    } else {
        null
    }

    // "web": the APK from sms.techistbd.com and GitHub (self-updates, optional default SMS app).
    // "play": Google Play (Play updates it; no sending, no default SMS app). Same package and key,
    // so either can update the other.
    flavorDimensions += "store"
    productFlavors {
        create("web") {
            dimension = "store"
            buildConfigField("boolean", "PLAY", "false")
        }
        create("play") {
            dimension = "store"
            buildConfigField("boolean", "PLAY", "true")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("boolean", "ALLOW_LOCAL_HTTP", "true")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed only where the key exists (see docs/development.md); never committed.
            releaseSigning?.let { signingConfig = it }
        }
        // Test-only: exactly the release (package, key, minification) but allowed to reach the local
        // HTTP stack, to test self-updates on emulators. Never published (tools/publish-release.sh builds "release").
        create("releaseTest") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            buildConfigField("boolean", "ALLOW_LOCAL_HTTP", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // Robolectric's Conscrypt (used on Linux, e.g. CI) reads InetAddress internals for TLS.
            all { it.jvmArgs("--add-opens=java.base/java.net=ALL-UNNAMED") }
        }
    }
    sourceSets {
        // Exported Room schemas are used by MigrationTestHelper.
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.paging.compose)
    implementation(libs.kotlinx.coroutines.android)
    // Streaming AEAD for encrypted backups (reviewed implementation, no custom crypto).
    implementation(libs.tink.android)
    // QR pairing for device-to-device transfer (Apache-2.0, no Google services needed).
    implementation(libs.zxing.android.embedded)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.room.testing)
}
