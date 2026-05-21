plugins {
    `java-library`
}

dependencies {
    api(project(":ringloom-framework-core"))
    api(platform(libs.opentelemetryBom))
    api(libs.opentelemetryApi)
    testImplementation(platform(libs.opentelemetryBom))
    testImplementation(libs.opentelemetrySdk)
    testImplementation(libs.opentelemetrySdkTesting)
}
