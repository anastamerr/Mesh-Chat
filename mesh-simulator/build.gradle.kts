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
    api(project(":mesh-engine"))

    // Cryptography remains test-scoped until ADR 0003 is accepted.
    testImplementation("com.google.crypto.tink:tink:1.23.0")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
