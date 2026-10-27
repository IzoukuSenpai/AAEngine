import org.gradle.accessors.dm.LibrariesForLibs

plugins {
    id("aaengine.kotlin-library")
}

val libs = the<LibrariesForLibs>()

dependencies {
    compileOnly(libs.paper.api)
}

tasks.named<ProcessResources>("processResources") {
    val projectVersion = project.version.toString()
    inputs.property("version", projectVersion)
    filesMatching("paper-plugin.yml") {
        expand("version" to projectVersion)
    }
}
