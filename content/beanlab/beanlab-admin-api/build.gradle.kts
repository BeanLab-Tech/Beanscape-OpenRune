plugins {
    id("base-conventions")
}

dependencies {
    implementation(libs.jackson.databind)
    implementation(projects.api.db)
    implementation(projects.api.dbGateway)
    implementation(projects.api.invtx)
    implementation(projects.api.pluginCommons)
    implementation(projects.api.pwHash)
    implementation(projects.api.realm)
    implementation(projects.api.serverConfig)
    implementation(projects.api.utils.utilsSystem)
    implementation(projects.content.beanlab.beanlabCore)
    testImplementation(kotlin("test"))
}
