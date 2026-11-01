import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.github.achyuki.oautopin"
    // libxposed service 102 publishes minCompileSdk 37, so the app must compile
    // against 37 or AGP fails the build. Runtime behaviour is still bounded by
    // minSdk/targetSdk below.
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.achyuki.oautopin"
        minSdk = 35
        targetSdk = 36
        // Upstream "16" (versionCode 1) is the last achyuki release. This fork
        // adds the AFU gate (switchable), dual-SIM PINs and the libxposed 102
        // migration, all of which change runtime behaviour.
        versionCode = 2
        versionName = "17.0.0"
    }

    signingConfigs {
        create("release") {
            // The release keystore. There is deliberately no checked-in secret:
            // the CI workflows generate this keystore inside the workflow and
            // write the same paths/passwords to signing/signing.properties, so
            // the published APK always has a reproducible, installable signature.
            val propsFile = rootProject.file("signing/signing.properties")
            if (propsFile.isFile) {
                val props = Properties().apply {
                    propsFile.inputStream().use(::load)
                }
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Only attach a signing config when the keystore above was actually
            // resolved; otherwise the build still produces the unsigned APK that
            // CI signs explicitly with apksigner.
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile?.isFile == true) {
                signingConfig = releaseSigning
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)

    // libxposed public API. compileOnly: the framework injects it at runtime.
    compileOnly(libs.libxposed.api)
    // libxposed service. implementation: the settings Activity binds to the
    // framework through XposedServiceHelper, so these classes must be in the APK.
    implementation(libs.libxposed.service)
}
