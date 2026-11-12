import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The web app: a Ktor server that runs the shared sync code and renders HTML pages (with htmx).
// `./gradlew :server:installDist` puts a runnable copy in server/build/install/steamshelf-server.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

application {
    applicationName = "steamshelf-server"
    mainClass = "dev.steamshelf.server.MainKt"
    // Small and steady: the library state is a few MB even for big libraries. JAVA_OPTS overrides these.
    // Exiting on OOM lets Docker restart the server instead of leaving it half-working.
    applicationDefaultJvmArgs = listOf("-Xmx256m", "-XX:+UseSerialGC", "-XX:+ExitOnOutOfMemoryError", "-Dfile.encoding=UTF-8")
}

val versionName = providers.gradleProperty("versionName").orElse("dev")

// Lets the server show its version on the settings page.
val generateBuildInfo by tasks.registering {
    val output = layout.buildDirectory.dir("generated/buildinfo")
    val version = versionName
    inputs.property("version", version)
    outputs.dir(output)
    doLast {
        val file = output.get().file("steamshelf-build.properties").asFile
        file.parentFile.mkdirs()
        file.writeText("version=${version.get()}\n")
    }
}

sourceSets.main {
    resources.srcDir(generateBuildInfo)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.html.builder)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auto.head.response)
    implementation(libs.ktor.server.sessions)
    implementation(libs.ktor.server.sse)
    implementation(libs.ktor.server.forwarded.header)
    implementation(libs.ktor.server.compression)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.logback.classic)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.ktor.server.test.host)
}

// A local copy against a fake Steam/ProtonDB/HowLongToBeat with a sample library, for working on the UI.
// Open http://localhost:8080/dev/sign-in, then enter 0123456789ABCDEF0123456789ABCDEF as the API key.
tasks.register<JavaExec>("runDev") {
    group = "application"
    description = "Runs the web app on localhost:8080 against a fake backend (see DevServer.kt)."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "dev.steamshelf.server.DevServerKt"
    args(layout.buildDirectory.dir("dev-data").get().asFile.absolutePath)
}
