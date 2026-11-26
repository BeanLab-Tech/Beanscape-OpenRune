plugins {
    id("base-conventions")
}

dependencies {
    implementation(projects.api.pluginCommons)
    implementation(projects.content.beanlab.beanlabCore)
    testImplementation(kotlin("test"))
}
