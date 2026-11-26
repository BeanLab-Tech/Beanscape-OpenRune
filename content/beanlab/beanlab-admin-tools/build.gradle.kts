plugins {
    id("base-conventions")
}

dependencies {
    implementation(projects.api.db)
    implementation(projects.api.dbGateway)
    implementation(projects.api.invtx)
    implementation(projects.api.pluginCommons)
    implementation(projects.api.utils.utilsSystem)
    implementation(projects.content.beanlab.beanlabCore)
    implementation(projects.content.quest)
    testImplementation(kotlin("test"))
}
