plugins {
    id("base-conventions")
}

dependencies {
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)
    implementation(projects.api.db)
    implementation(projects.api.dbGateway)
    implementation(projects.api.invtx)
    implementation(projects.api.pluginCommons)
    implementation(projects.api.realm)
    implementation(projects.api.serverConfig)
    implementation(projects.api.utils.utilsSystem)
    implementation(projects.content.beanlab.beanlabCore)
    implementation(projects.content.beanlab.beanlabHub)
    testImplementation(kotlin("test"))
}
