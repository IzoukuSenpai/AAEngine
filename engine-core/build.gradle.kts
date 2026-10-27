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
