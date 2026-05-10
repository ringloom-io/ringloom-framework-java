plugins {
    `java-library`
}

dependencies {
    api(project(":ringloom-framework-core"))
    implementation(libs.snakeYamlEngine)
    testRuntimeOnly(libs.slf4jSimple)
}
