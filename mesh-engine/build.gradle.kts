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
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
