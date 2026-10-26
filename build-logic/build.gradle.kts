plugins {
    `kotlin-dsl`
}

dependencyLocking {
    lockAllConfigurations()
}

fun Provider<PluginDependency>.toDep(): Provider<String> =
    map { "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}" }

dependencies {
    implementation(libs.plugins.kotlin.jvm.toDep())
    implementation(libs.plugins.ktlint.toDep())
    // Exposes the generated version-catalog accessors (LibrariesForLibs) to precompiled script plugins.
    implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))
}
