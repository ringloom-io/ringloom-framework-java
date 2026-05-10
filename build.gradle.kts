import com.diffplug.gradle.spotless.SpotlessExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.process.CommandLineArgumentProvider

plugins {
    `java-library`
    alias(libs.plugins.spotless) apply false
}

val javaVersion = JavaLanguageVersion.of(25)
val palantirJavaFormatVersion = libs.versions.palantirJavaFormat.get()
val junitBom = libs.junitBom
val testLibs = libs.bundles.testLibs
val junitPlatformLauncher = libs.junitPlatformLauncher
val mockitoCore = libs.mockitoCore
val mockitoCoreNotation = run {
    val dependency = mockitoCore.get()
    "${dependency.module.group}:${dependency.module.name}:${dependency.versionConstraint.requiredVersion}"
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "com.diffplug.spotless")

    val mockitoAgent = configurations.create("mockitoAgent") {
        isCanBeConsumed = false
    }

    group = "io.ringloom"
    version = "0.1.0-SNAPSHOT"

    java {
        toolchain {
            languageVersion.set(javaVersion)
        }
    }

    configure<SpotlessExtension> {
        java {
            target("src/*/java/**/*.java")
            palantirJavaFormat(palantirJavaFormatVersion)
            formatAnnotations()
        }
    }

    dependencies {
        "testImplementation"(platform(junitBom))
        "testImplementation"(testLibs)
        "testRuntimeOnly"(junitPlatformLauncher)

        add(mockitoAgent.name, mockitoCoreNotation) {
            isTransitive = false
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        dependsOn(tasks.named("spotlessApply"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        jvmArgs(
            "--enable-native-access=ALL-UNNAMED",
            "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
            "-Xshare:off",
            "-XX:+EnableDynamicAgentLoading",
        )
        jvmArgumentProviders.add(
            object : CommandLineArgumentProvider {
                override fun asArguments(): Iterable<String> {
                    return listOf("-javaagent:${mockitoAgent.singleFile.absolutePath}")
                }
            },
        )
    }
}
