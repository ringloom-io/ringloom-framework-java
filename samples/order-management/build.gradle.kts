// SPDX-License-Identifier: Apache-2.0
plugins {
    `java-library`
}

val sampleSourceSets = listOf("portfolio", "execution", "matching", "risk", "gateway", "simulator")
val codecGeneration by configurations.creating
val generatedSbeDir = layout.buildDirectory.dir("generated-src/sbe")
val codecsFile = layout.projectDirectory.file("src/main/resources/messages.xml")

dependencies {
    implementation(project(":ringloom-framework-core"))
    implementation(project(":ringloom-framework-yaml"))
    implementation(project(":ringloom-serializer-sbe"))
    implementation(libs.slf4jSimple)
    annotationProcessor(project(":ringloom-framework-core"))
    annotationProcessor(project(":ringloom-framework-processor"))
    codecGeneration(libs.sbeTool)
}

sourceSets {
    named("main") {
        java.srcDir(generatedSbeDir)
    }
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

tasks.register<JavaExec>("generateCodecs") {
    inputs.file(codecsFile)
    outputs.dir(generatedSbeDir)
    mainClass.set("uk.co.real_logic.sbe.SbeTool")
    classpath = codecGeneration
    jvmArgs("--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED")
    systemProperty("sbe.output.dir", generatedSbeDir.get().asFile.absolutePath)
    systemProperty("sbe.target.language", "Java")
    systemProperty("sbe.java.generate.interfaces", "true")
    systemProperty("sbe.java.generate.dtos", "true")
    systemProperty("sbe.validation.stop.on.error", "true")
    args(codecsFile.asFile.absolutePath)
}

tasks.named("compileJava") {
    dependsOn(tasks.named("generateCodecs"))
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
