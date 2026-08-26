// Each module (app/, engine/) applies its own plugins directly via the version
// catalog (gradle/libs.versions.toml) instead of a shared `apply false` block here.
// This keeps `:engine` (pure Kotlin/JVM, no Android SDK needed) fully independent
// of the Android Gradle Plugin, so it configures and builds on its own.
