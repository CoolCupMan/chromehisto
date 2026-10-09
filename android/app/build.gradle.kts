plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The report template is shared with the desktop tool; copy it into the APK assets.
val copyTemplate by tasks.registering(Copy::class) {
    from(rootDir.resolve("../chromehisto/template.html"))
    into(layout.buildDirectory.dir("generated/sharedAssets"))
}

// Optional release key, supplied by CI from repository secrets. Without it the
// APK is signed with the standard Android debug key (installable, but updates
// across differently-signed builds need an uninstall first).
val keystorePath: String? = System.getenv("SIGNING_KEYSTORE_PATH")

android {
    namespace = "com.chromehisto.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.chromehisto.app"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystorePath != null) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
        }
    }

    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/sharedAssets"))

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

tasks.named("preBuild") { dependsOn(copyTemplate) }
