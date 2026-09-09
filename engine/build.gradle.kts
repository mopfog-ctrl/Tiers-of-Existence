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
    // Same mechanism for the experimental Phase 1B dynamic-reincarnation benchmark.
    systemProperty("toe.benchmark.dynamic", System.getProperty("toe.benchmark.dynamic", "false"))
    // Same mechanism for the experimental Phase 1C anti-stagnation benchmark.
    systemProperty("toe.benchmark.antistagnation", System.getProperty("toe.benchmark.antistagnation", "false"))
    // Same mechanism for the corrected-model (whole-game rarity-ceiling) dynamic-reincarnation baseline.
    systemProperty("toe.benchmark.dynamic.corrected", System.getProperty("toe.benchmark.dynamic.corrected", "false"))
}
