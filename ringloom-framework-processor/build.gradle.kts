plugins {
    `java-library`
}

dependencies {
    compileOnly(project(":ringloom-framework-core"))
    testImplementation(project(":ringloom-framework-core"))
}
