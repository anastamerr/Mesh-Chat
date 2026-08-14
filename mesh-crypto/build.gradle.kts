plugins {
    kotlin("jvm")
}

kotlin {
    explicitApi()
    jvmToolchain(17)

    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    api(project(":mesh-protocol"))
    implementation("com.google.crypto.tink:tink:1.23.0")
    testImplementation(kotlin("test"))
}

sourceSets.test {
    resources.srcDir(rootProject.file("test-vectors"))
}

tasks.test {
    useJUnitPlatform()
}
