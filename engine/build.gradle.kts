plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // Forwarded into the forked test JVM so `-Dtoe.benchmark=true` on the Gradle invocation
    // actually reaches PlayerCountBenchmarkTest's own assumeTrue gate (Gradle does not forward
    // arbitrary -D system properties to a forked test process by default).
    systemProperty("toe.benchmark", System.getProperty("toe.benchmark", "false"))
}
