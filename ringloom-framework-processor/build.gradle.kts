plugins {
    `java-library`
}

dependencies {
    compileOnly(project(":ringloom-framework-core"))
    implementation(libs.jmustache)
    testImplementation(project(":ringloom-framework-core"))
    testImplementation(project(":ringloom-serializer-sbe"))
}
