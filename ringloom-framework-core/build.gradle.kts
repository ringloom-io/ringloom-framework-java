plugins {
    `java-library`
}

dependencies {
    api(libs.ringloomJavaBindings)
    api(libs.slf4jApi)
    testRuntimeOnly(libs.slf4jSimple)
}
