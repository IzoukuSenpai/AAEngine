plugins {
    id("aaengine.kotlin-library")
}

dependencies {
    api(project(":engine-api"))
    compileOnly(libs.paper.api)
    implementation(libs.configurate.yaml)
    implementation(libs.configurate.kotlin)
    implementation(libs.kotlinx.coroutines)
}

// Tests : types Paper/Adventure (Key, ItemStack) référencés par l'API
dependencies {
    testImplementation(libs.paper.api)
}
