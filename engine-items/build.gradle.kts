plugins {
    id("aaengine.kotlin-library")
}

dependencies {
    api(project(":engine-api"))
    implementation(project(":engine-core"))
    compileOnly(libs.paper.api)
    implementation(libs.configurate.yaml)
}

// Tests purs (sans serveur) : types Adventure/Bukkit référencés par les définitions.
dependencies {
    testImplementation(libs.paper.api)
}
