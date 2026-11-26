plugins {
    id("base-conventions")
}

dependencies {
    implementation(rootProject.libs.or2.tools)
    implementation(libs.jackson.databind)
    testImplementation(kotlin("test"))
    testImplementation(libs.netty.buffer)
    testImplementation(projects.engine.map)
    testImplementation(libs.fastutil)
}
