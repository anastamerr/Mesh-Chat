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
    // Test-scoped while ADR 0003 remains provisional. No Tink type is allowed
    // into the protocol module's production API.
    testImplementation("com.google.crypto.tink:tink:1.23.0")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
