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

    testImplementation(project(":mesh-crypto"))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
