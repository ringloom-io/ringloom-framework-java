plugins {
    `java-library`
}

dependencies {
    api(project(":ringloom-framework-core"))
    implementation("org.snakeyaml:snakeyaml-engine:2.9")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.17")
}
