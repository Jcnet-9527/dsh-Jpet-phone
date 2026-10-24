plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.dshpet.mobile"
    compileSdk = 34
    buildToolsVersion = "29.0.3"
    defaultConfig {
        applicationId = "io.github.dshpet.mobile.dsh"
        minSdk = 26
        targetSdk = 34
        versionCode = 20
        versionName = "0.6.3-dsh.20"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The voice runtime (sherpa-onnx) ships one .so per ABI; real phones
        // are all ARM, so the emulator ABIs stay out and the APK stays small.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    // Only the slim tree (core clips + the full manifest) travels in the APK;
    // the rest of the animations are downloaded as packs at runtime.
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/petAssetsSlim"))
    sourceSets["main"].res.srcDir(layout.buildDirectory.dir("generated/petRes"))
    testOptions { unitTests.isReturnDefaultValues = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

val exportPetAssets by tasks.registering(Exec::class) {
    val repository = rootProject.projectDir.parentFile
    val python = providers.gradleProperty("petPython").orElse("python")
    inputs.dir(repository.resolve("assets/characters/shenshen/videos"))
    inputs.dir(repository.resolve("assets/sounds/duck"))
    inputs.file(repository.resolve("scripts/export_android_assets.py"))
    inputs.file(repository.resolve("pet/frameseq_provision.py"))
    inputs.file(repository.resolve("pet/config.py"))
    inputs.file(repository.resolve("scripts/make_icon.py"))
    inputs.file(rootProject.file("NOTICE.txt"))
    inputs.file(rootProject.file("LICENSE-APACHE-2.0.txt"))
    inputs.file(repository.resolve("THIRD_PARTY_NOTICES.md"))
    outputs.dir(layout.buildDirectory.dir("generated/petAssets"))
    outputs.dir(layout.buildDirectory.dir("generated/petRes"))
    workingDir(repository)
    commandLine(python.get(), "scripts/export_android_assets.py", "--output",
        layout.buildDirectory.dir("generated/petAssets").get().asFile.absolutePath,
        "--res-output", layout.buildDirectory.dir("generated/petRes").get().asFile.absolutePath,
        "--webp-quality", "80")
}
tasks.named("preBuild").configure { dependsOn(exportPetAssets) }

val packPetAssets by tasks.registering(Exec::class) {
    dependsOn(exportPetAssets)
    val repository = rootProject.projectDir.parentFile
    val python = providers.gradleProperty("petPython").orElse("python")
    inputs.dir(layout.buildDirectory.dir("generated/petAssets"))
    inputs.file(repository.resolve("scripts/build_asset_packs.py"))
    outputs.dir(layout.buildDirectory.dir("generated/petAssetsSlim"))
    outputs.dir(repository.resolve("dist-remote"))
    workingDir(repository)
    commandLine(python.get(), "scripts/build_asset_packs.py", "--assets",
        layout.buildDirectory.dir("generated/petAssets").get().asFile.absolutePath,
        "--slim-out", layout.buildDirectory.dir("generated/petAssetsSlim").get().asFile.absolutePath,
        "--pack-out", repository.resolve("dist-remote").absolutePath)
}
tasks.named("preBuild").configure { dependsOn(packPetAssets) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // On-device ASR + TTS runtime; the models themselves download on demand.
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
}
