plugins {
    `java-library`
}

val jmhJvmArgs = listOf(
    "--enable-native-access=ALL-UNNAMED",
    "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
    "-Xshare:off",
)

sourceSets {
    val main by getting
    val jmh by creating {
        java.srcDir("src/jmh/java")
        resources.srcDir("src/jmh/resources")
        compileClasspath += main.output
        runtimeClasspath += output + compileClasspath
    }
}

configurations.named("jmhImplementation") {
    extendsFrom(configurations.api.get(), configurations.implementation.get())
}

configurations.named("jmhRuntimeOnly") {
    extendsFrom(configurations.runtimeOnly.get())
}

dependencies {
    api(libs.ringloomJavaBindings)
    api(libs.slf4jApi)
    implementation(libs.agrona)
    "jmhImplementation"(libs.jmhCore)
    "jmhAnnotationProcessor"(libs.jmhGeneratorAnnprocess)
    testRuntimeOnly(libs.slf4jSimple)
}

tasks.register<JavaExec>("jmh") {
    val jmhSourceSet = sourceSets.named("jmh")
    group = "verification"
    description = "Runs the ringloom-framework-core JMH benchmarks."
    dependsOn(tasks.named(jmhSourceSet.get().classesTaskName))
    classpath = jmhSourceSet.get().runtimeClasspath
    mainClass.set("org.openjdk.jmh.Main")
    jvmArgs(jmhJvmArgs)
    args("-jvmArgsAppend", jmhJvmArgs.joinToString(" "))
    doFirst {
        providers.gradleProperty("jmhArgs").orNull?.takeIf(String::isNotBlank)?.let { extraArgs ->
            args(extraArgs.split(Regex("\\s+")))
        }
    }
}
