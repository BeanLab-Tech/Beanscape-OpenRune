plugins {
    id("base-conventions")
}

dependencies {
    api(projects.api.db)
    implementation(projects.api.pluginCommons)
}
