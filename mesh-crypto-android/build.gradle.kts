plugins {
    id("com.android.library")
}

android {
    namespace = "chat.mesh.crypto.android"
    //noinspection GradleDependency -- API 37 is still an Android 17 preview.
    compileSdk = 36

    defaultConfig {
        minSdk = 26
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
}

kotlin {
    explicitApi()
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    api(project(":mesh-crypto"))
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
