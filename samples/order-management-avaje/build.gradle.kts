// SPDX-License-Identifier: Apache-2.0
plugins {
    `java-library`
}

val sampleSourceSets = listOf("portfolio", "execution", "matching", "risk", "gateway", "simulator")

dependencies {
    implementation(project(":ringloom-framework-core"))
    implementation(project(":ringloom-framework-yaml"))
    implementation(project(":ringloom-ioc-avaje"))
    implementation(project(":ringloom-serializer-fory"))
    implementation(libs.slf4jSimple)
    annotationProcessor(libs.avajeInjectGenerator)
    annotationProcessor(project(":ringloom-framework-core"))
    annotationProcessor(project(":ringloom-framework-processor"))
}

sourceSets {
    sampleSourceSets.forEach { sourceSetName ->
        create(sourceSetName) {
            java.srcDir("src/$sourceSetName/java")
            compileClasspath += sourceSets.named("main").get().output
            runtimeClasspath += output + compileClasspath + sourceSets.named("main").get().runtimeClasspath
        }
    }
}

sampleSourceSets.forEach { sourceSetName ->
    configurations.named("${sourceSetName}Implementation") {
        extendsFrom(configurations.implementation.get())
    }
    configurations.named("${sourceSetName}AnnotationProcessor") {
        extendsFrom(configurations.annotationProcessor.get())
    }
    configurations.named("${sourceSetName}RuntimeOnly") {
        extendsFrom(configurations.runtimeOnly.get())
    }
}

tasks.named("build") {
    sampleSourceSets.forEach { sourceSetName ->
        dependsOn(tasks.named("${sourceSetName}Classes"))
    }
}

val applications = mapOf(
    "runPortfolioService" to ("portfolio" to "io.ringloom.samples.orders.portfolio.PortfolioServiceApp"),
    "runExecutionService" to ("execution" to "io.ringloom.samples.orders.execution.ExecutionServiceApp"),
    "runMatchingEngine" to ("matching" to "io.ringloom.samples.orders.matching.MatchingEngineApp"),
    "runRiskService" to ("risk" to "io.ringloom.samples.orders.risk.RiskServiceApp"),
    "runOrderGateway" to ("gateway" to "io.ringloom.samples.orders.gateway.OrderGatewayApp"),
    "runOrderSimulator" to ("simulator" to "io.ringloom.samples.orders.simulator.OrderSimulatorApp"),
)

applications.forEach { (taskName, app) ->
    val sourceSetName = app.first
    val mainClassName = app.second
    tasks.register<JavaExec>(taskName) {
        group = "application"
        description = "Run $mainClassName"
        classpath = sourceSets.named(sourceSetName).get().runtimeClasspath
        mainClass.set(mainClassName)
        workingDir = rootProject.projectDir
        dependsOn(tasks.named("${sourceSetName}Classes"))
        jvmArgs(
            "--enable-native-access=ALL-UNNAMED",
            "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
            "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
        )
    }
}
