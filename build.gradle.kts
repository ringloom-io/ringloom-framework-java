import com.diffplug.gradle.spotless.SpotlessExtension
import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SourcesJar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.process.CommandLineArgumentProvider

plugins {
    `java-library`
    alias(libs.plugins.spotless) apply false
    alias(libs.plugins.mavenPublish) apply false
}

val javaVersion = JavaLanguageVersion.of(25)

// Release builds pass -Pringloom.version=<version> (derived from the git tag in CI).
val ringloomVersion = providers.gradleProperty("ringloom.version").getOrElse("0.1.0-SNAPSHOT")

// Library modules published to Maven Central (samples are never published).
val publishedModules = mapOf(
    "ringloom-framework-core" to "RingLoom framework runtime, annotations and generated-code contracts.",
    "ringloom-framework-processor" to "Annotation processor generating RingLoom framework applications, clients and KV stores.",
    "ringloom-framework-yaml" to "YAML configuration loader for the RingLoom framework.",
    "ringloom-ioc-avaje" to "Avaje Inject integration for the RingLoom framework.",
    "ringloom-metrics-opentelemetry" to "OpenTelemetry metrics adapter for the RingLoom framework.",
    "ringloom-serializer-fory" to "Apache Fory message serializer for the RingLoom framework.",
    "ringloom-serializer-sbe" to "SBE message serializer for the RingLoom framework.",
    "ringloom-tracing-opentelemetry" to "OpenTelemetry tracing adapter for the RingLoom framework.",
)
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
    version = ringloomVersion

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

    val artifactName = project.name
    publishedModules[artifactName]?.let { moduleDescription ->
        apply(plugin = "com.vanniktech.maven.publish")

        configure<MavenPublishBaseExtension> {
            coordinates("io.ringloom", artifactName, ringloomVersion)
            configure(
                JavaLibrary(
                    javadocJar = JavadocJar.Javadoc(),
                    sourcesJar = SourcesJar.Sources(),
                ),
            )
            publishToMavenCentral(automaticRelease = true)
            signAllPublications()

            pom {
                name.set(artifactName)
                description.set(moduleDescription)
                inceptionYear.set("2026")
                url.set("https://github.com/ringloom-io/ringloom-framework-java")
                licenses {
                    license {
                        name.set("Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("ringloom-io")
                        name.set("RingLoom Devs")
                        url.set("https://github.com/ringloom-io")
                    }
                }
                scm {
                    url.set("https://github.com/ringloom-io/ringloom-framework-java")
                    connection.set("scm:git:https://github.com/ringloom-io/ringloom-framework-java.git")
                    developerConnection.set("scm:git:ssh://git@github.com/ringloom-io/ringloom-framework-java.git")
                }
            }
        }
    }
}
