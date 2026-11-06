plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.deve.assistant"
    compileSdk = 34


    sourceSets.getByName("main").assets.srcDir("$buildDir/generated/assets")

    defaultConfig {
        applicationId = "com.deve.assistant"
        minSdk = 29
        targetSdk = 34
        versionCode = 4
        versionName = "1.3"
    }
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}


dependencies {
    // Reconnaissance vocale locale (aucun service Google requis)
    implementation("com.alphacephei:vosk-android:0.3.75")
}

// Vosk StorageService attend un identifiant UUID dans le dossier du modèle.
val generateVoskModelUuid by tasks.registering {
    val uuidFile = file("$buildDir/generated/assets/model-fr/uuid")
    outputs.file(uuidFile)
    doLast {
        val file = uuidFile
        file.parentFile.mkdirs()
        file.writeText(java.util.UUID.randomUUID().toString())
    }
}
tasks.named("preBuild").configure {
    dependsOn(generateVoskModelUuid)
}
