plugins {
    id("aaengine.kotlin-library")
}

dependencies {
    api(project(":engine-api"))
    implementation(project(":engine-core"))
    implementation(libs.mockbukkit)
    implementation(platform(libs.junit.bom))
    implementation(libs.junit.jupiter)
}
