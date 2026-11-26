plugins {
    id("base-conventions")
}

dependencies {
    implementation(projects.api.pluginCommons)
    implementation(projects.api.gameProcess)
    implementation(projects.content.beanlab.beanlabHub.beanlabHubPack)
    testImplementation(kotlin("test"))
}
