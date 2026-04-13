import java.net.URI

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

val umpireJsonVersion: String by project

// ---------------------------------------------------------------------------
// Conformance fixture download + extraction
// ---------------------------------------------------------------------------
// Pulls @umpire/json from npm and extracts the conformance/ directory into
// build/conformance/. No Node.js or npm required — the npm registry is plain
// HTTPS + gzip tar.
//
// Paths in index.json are relative to index.json itself (i.e. build/conformance/).
// Tests read the system property "umpire.conformance.dir" to locate index.json.
//
// Version is baked into the cached tarball filename so Gradle's UP-TO-DATE check
// automatically re-downloads when umpireJsonVersion changes in gradle.properties.

val conformanceTgz: Provider<RegularFile> =
    layout.buildDirectory.file("conformance-cache/umpire-json-$umpireJsonVersion.tgz")

val conformanceOut: Provider<Directory> =
    layout.buildDirectory.dir("conformance")

val downloadConformanceTarball by tasks.registering {
    group = "verification"
    description = "Downloads @umpire/json $umpireJsonVersion conformance fixtures from npm"
    outputs.file(conformanceTgz)
    doLast {
        val dest = conformanceTgz.get().asFile
        dest.parentFile.mkdirs()
        val url = "https://registry.npmjs.org/@umpire/json/-/@umpire/json-$umpireJsonVersion.tgz"
        logger.lifecycle("Downloading $url")
        URI(url).toURL().openStream().use { src -> dest.outputStream().use { src.copyTo(it) } }
    }
}

val extractConformanceFixtures by tasks.registering(Copy::class) {
    group = "verification"
    description = "Extracts conformance/ from the @umpire/json tarball into build/conformance/"
    dependsOn(downloadConformanceTarball)
    from(tarTree(resources.gzip(conformanceTgz.get().asFile))) {
        include("package/conformance/**")
        // Strip "package/conformance/" — output: index.json, fixtures/, failures/
        eachFile {
            relativePath = RelativePath(true, *relativePath.segments.drop(2).toTypedArray())
        }
    }
    into(conformanceOut)
    includeEmptyDirs = false
}

tasks.withType<Test> {
    dependsOn(extractConformanceFixtures)
    systemProperty("umpire.conformance.dir", conformanceOut.get().asFile.absolutePath)
}

// ---------------------------------------------------------------------------
// Dependencies
// ---------------------------------------------------------------------------

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
    testImplementation("io.kotest:kotest-assertions-core:5.9.1")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}
