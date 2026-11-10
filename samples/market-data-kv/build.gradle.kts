// SPDX-License-Identifier: Apache-2.0
plugins {
    `java-library`
}

val sampleSourceSets = listOf("writer", "reader", "terminal")
val codecGeneration by configurations.creating
val generatedSbeDir = layout.buildDirectory.dir("generated-src/sbe")
val codecsFile = layout.projectDirectory.file("src/main/resources/messages.xml")

dependencies {
    implementation(project(":ringloom-framework-core"))
    implementation(project(":ringloom-framework-yaml"))
    implementation(project(":ringloom-ioc-avaje"))
    implementation(project(":ringloom-serializer-sbe"))
    implementation(libs.slf4jSimple)
    annotationProcessor(libs.avajeInjectGenerator)
    annotationProcessor(project(":ringloom-framework-core"))
    annotationProcessor(project(":ringloom-framework-processor"))
    // The test source set declares its own @RingloomApplication with generated KV stores.
    testAnnotationProcessor(project(":ringloom-framework-core"))
    testAnnotationProcessor(project(":ringloom-framework-processor"))
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

// The generated-store integration and allocation tests need a KV-enabled test broker:
// -Pringloom.brokerBin=... -Pringloom.repoRoot=... (skipped when absent).
tasks.withType<Test>().configureEach {
    jvmArgs("--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED")
    systemProperty(
        "ringloom.brokerBin",
        providers.systemProperty("ringloom.brokerBin")
            .orElse(providers.gradleProperty("ringloom.brokerBin"))
            .orNull
    )
    systemProperty(
        "ringloom.repoRoot",
        providers.systemProperty("ringloom.repoRoot")
            .orElse(providers.gradleProperty("ringloom.repoRoot"))
            .orNull
    )
}

val applications = mapOf(
    "runPriceWriter" to ("writer" to "io.ringloom.samples.marketdatakv.writer.PriceWriterApp"),
    "runPriceReader" to ("reader" to "io.ringloom.samples.marketdatakv.reader.PriceReaderApp"),
    "runPriceTerminal" to ("terminal" to "io.ringloom.samples.marketdatakv.terminal.PriceTerminalApp"),
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
