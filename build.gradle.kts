import java.io.ByteArrayOutputStream

plugins {
    java
}

group = "org.smokeslate"
version = "0.1.0"

val gitCommit: String by lazy {
    try {
        val output = ByteArrayOutputStream()
        exec {
            commandLine("git", "rev-parse", "HEAD")
            standardOutput = output
        }
        output.toString().trim().ifBlank { "unknown" }
    } catch (_: Exception) {
        "unknown"
    }
}

val gitBranch: String by lazy {
    try {
        val output = ByteArrayOutputStream()
        exec {
            commandLine("git", "rev-parse", "--abbrev-ref", "HEAD")
            standardOutput = output
        }
        output.toString().trim().ifBlank { "unknown" }
    } catch (_: Exception) {
        "unknown"
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.processResources {
    val props = mapOf(
        "version" to project.version,
        "commit" to gitCommit,
        "branch" to gitBranch
    )
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching(listOf("plugin.yml", "build-info.properties")) {
        expand(props)
    }
    from("sasquatchresourcepack") {
        into("bundled/resourcepack")
        exclude("**/.DS_Store")
    }
    from("datapack") {
        into("bundled/datapack")
        exclude("**/.DS_Store")
    }
    from("nexo") {
        into("bundled/nexo")
        exclude("**/.DS_Store")
    }
}
