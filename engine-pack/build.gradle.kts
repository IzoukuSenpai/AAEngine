plugins {
    id("aaengine.kotlin-library")
}

dependencies {
    api(project(":engine-api"))
    implementation(project(":engine-core"))
}
