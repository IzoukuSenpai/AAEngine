plugins {
    id("aaengine.paper-plugin")
}

dependencies {
    implementation(project(":engine-core"))
    implementation(project(":engine-items"))
    implementation(project(":engine-mechanics"))
    implementation(project(":engine-blocks"))
    implementation(project(":engine-furniture"))
    implementation(project(":engine-recipes"))
    implementation(project(":engine-pack"))
    implementation(project(":engine-fonts"))
    implementation(project(":engine-version-common"))
    implementation(project(":engine-integrations"))
}

// Tests : serveur simulé MockBukkit (composants de données, PDC, événements).
dependencies {
    testImplementation(libs.paper.api)
    testImplementation(libs.mockbukkit)
}
