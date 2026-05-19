plugins {
    `java-library`
}

dependencies {
    api(libs.ringloomJavaBindings)
    api(libs.slf4jApi)
    implementation(libs.jctoolsCore)
    testRuntimeOnly(libs.slf4jSimple)
}
