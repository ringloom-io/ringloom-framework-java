plugins {
    `java-library`
}

dependencies {
    api("io.ringloom:ringloom-java-bindings:0.1.2")
    api("org.slf4j:slf4j-api:2.0.17")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.17")
}
