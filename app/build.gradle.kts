import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.sy.music"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sy.music"
        minSdk = 26
        targetSdk = 36
        // GitHub entrega estos valores al publicar; en el PC se usan los de respaldo.
        val publishedCode = System.getenv("VERSION_CODE")?.toInt()
        versionCode = publishedCode ?: 9
        versionName = publishedCode?.let { "1.$it" } ?: "0.9"
        buildConfigField("String", "UPDATE_REPO", "\"${System.getenv("GITHUB_REPOSITORY").orEmpty()}\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true // NewPipeExtractor lo exige con minSdk < 33
    }

    signingConfigs {
        create("release") {
            val keys = Properties().apply {
                rootProject.file("keystore.properties").inputStream().use { load(it) }
            }
            storeFile = rootProject.file(keys.getProperty("storeFile"))
            storePassword = keys.getProperty("storePassword")
            keyAlias = keys.getProperty("keyAlias")
            keyPassword = keys.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")

    // La publicación automática pasa aquí la versión más reciente.
    implementation("com.github.teamnewpipe:NewPipeExtractor:${findProperty("extractorVersion") ?: "v0.27.0"}")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-session:1.8.0")

    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("io.coil-kt:coil-compose:2.7.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517") // en el PC no existe el org.json de Android
}
