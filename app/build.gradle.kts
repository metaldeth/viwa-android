plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

import java.io.FileInputStream
import java.util.Properties

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "com.viwa.android"
    compileSdk = 35

    sourceSets {
        getByName("test") {
            assets.srcDir("$projectDir/schemas")
        }
    }

    signingConfigs {
        create("release") {
            val keystorePath =
                System.getenv("KEYSTORE_PATH")
                    ?: (project.findProperty("KEYSTORE_PATH") as String?)
            val storeFilePath =
                keystorePath?.let { rootProject.file(it) }
                    ?: rootProject.file("signing/release.jks")
            val storePassword =
                System.getenv("STORE_PASSWORD")
                    ?: (project.findProperty("STORE_PASSWORD") as String?)
            val keyAlias =
                System.getenv("KEY_ALIAS")
                    ?: (project.findProperty("KEY_ALIAS") as String?)
            val keyPassword =
                System.getenv("KEY_PASSWORD")
                    ?: (project.findProperty("KEY_PASSWORD") as String?)

            val credentialsPresent =
                storeFilePath.exists() &&
                    !storePassword.isNullOrBlank() &&
                    !keyAlias.isNullOrBlank() &&
                    !keyPassword.isNullOrBlank()

            if (credentialsPresent) {
                storeFile = storeFilePath
                this.storePassword = storePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "com.viwa.android"
        minSdk = 25
        targetSdk = 35
        versionCode = 215
        versionName = "26.08.12.07"

        testInstrumentationRunner = "com.viwa.android.ViwaHiltTestRunner"

        val localProps = Properties()
        val localPropsFile = rootProject.file("local.properties")
        if (localPropsFile.exists()) {
            FileInputStream(localPropsFile).use { localProps.load(it) }
        }
        val enrollmentKey =
            localProps.getProperty("telemetry.enrollmentKey")
                ?: System.getenv("VIWA_TELEMETRY_ENROLLMENT_KEY")
                ?: ""
        buildConfigField("String", "TELEMETRY_ENROLLMENT_KEY", "\"${enrollmentKey.replace("\"", "\\\"")}\"")
        val otaKeyId =
            localProps.getProperty("ota.signingKeyId")
                ?: System.getenv("VIWA_OTA_SIGNING_KEY_ID")
                ?: ""
        val otaPublicKeyPem =
            localProps.getProperty("ota.signingPublicKeyPem")
                ?: System.getenv("VIWA_OTA_SIGNING_PUBLIC_KEY_PEM")
                ?: ""
        buildConfigField("String", "OTA_SIGNING_KEY_ID", "\"${otaKeyId.replace("\"", "\\\"")}\"")
        buildConfigField(
            "String",
            "OTA_SIGNING_PUBLIC_KEY_PEM",
            "\"${otaPublicKeyPem.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\"",
        )
    }

    buildTypes {
        debug {
            val releaseSigning = signingConfigs.getByName("release")
            releaseSigning.storeFile?.let { signingConfig = releaseSigning }
            val localProps = Properties()
            val localPropsFile = rootProject.file("local.properties")
            if (localPropsFile.exists()) {
                FileInputStream(localPropsFile).use { localProps.load(it) }
            }
            val debugSerial =
                localProps.getProperty("telemetry.debug.serial")
                    ?: System.getenv("VIWA_TELEMETRY_DEBUG_SERIAL")
                    ?: "VIWA-TEST01"
            val debugRegKey =
                localProps.getProperty("telemetry.debug.regKey")
                    ?: System.getenv("VIWA_TELEMETRY_DEBUG_REG_KEY")
                    ?: ""
            val debugAutoConnect =
                localProps.getProperty("telemetry.debug.autoConnect")
                    ?: System.getenv("VIWA_TELEMETRY_DEBUG_AUTO_CONNECT")
                    ?: "false"
            buildConfigField("String", "TELEMETRY_DEBUG_SERIAL", "\"${debugSerial.replace("\"", "\\\"")}\"")
            buildConfigField("String", "TELEMETRY_DEBUG_REG_KEY", "\"${debugRegKey.replace("\"", "\\\"")}\"")
            buildConfigField(
                "boolean",
                "TELEMETRY_DEBUG_AUTO_CONNECT",
                debugAutoConnect.equals("true", ignoreCase = true).toString(),
            )
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            buildConfigField("String", "TELEMETRY_DEBUG_SERIAL", "\"\"")
            buildConfigField("String", "TELEMETRY_DEBUG_REG_KEY", "\"\"")
            buildConfigField("boolean", "TELEMETRY_DEBUG_AUTO_CONNECT", "false")
        }
    }

    gradle.taskGraph.whenReady {
        val needsReleaseSigning =
            allTasks.any {
                it.path == ":app:assembleRelease" ||
                    it.path == ":app:bundleRelease" ||
                    it.path == ":app:installRelease"
            }
        if (needsReleaseSigning && signingConfigs.getByName("release").storeFile == null) {
            throw GradleException(
                "Release signing is not configured. Set STORE_PASSWORD, KEY_ALIAS and KEY_PASSWORD " +
                    "(and optional KEYSTORE_PATH) via env or gradle.properties, " +
                    "or place signing/release.jks.",
            )
        }
    }

    applicationVariants.all {
        val variant = this
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "viwa-android-${variant.versionName}-${variant.buildType.name}.apk"
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
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

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            all {
                // Single fork: avoids OkHttp TaskRunner / coroutine accumulation across parallel JVMs on Windows.
                it.maxParallelForks = 1
                it.maxHeapSize = "1536m"
                it.systemProperty("junit.jupiter.execution.parallel.enabled", "false")
            }
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.foundation)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(libs.navigation.compose)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.okhttp)
    implementation(libs.java.websocket)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.timber)
    implementation(libs.usb.serial.android)
    implementation(libs.serial.port.android)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.coil.compose)
    implementation(libs.qrcode.kotlin)
    implementation(libs.security.crypto)
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.room.testing)
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")

    androidTestImplementation(libs.hilt.android.testing)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    kspAndroidTest(libs.hilt.compiler)
}
