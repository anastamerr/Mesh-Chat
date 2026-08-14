plugins {
    id("com.android.application")
}

android {
    namespace = "chat.mesh.probe"
    //noinspection GradleDependency -- API 37 is still an Android 17 preview.
    compileSdk = 36

    defaultConfig {
        applicationId = "chat.mesh.probe"
        minSdk = 26
        //noinspection OldTargetApi -- API 36 is the latest stable platform.
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
    }

    sourceSets {
        getByName("androidTest").assets.directories.add(rootProject.file("test-vectors").absolutePath)
    }
}

kotlin {
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(project(":mesh-crypto"))
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
