plugins {
    `java-library`
}

val javaVersion = JavaLanguageVersion.of(25)

subprojects {
    apply(plugin = "java-library")

    group = "io.ringloom"
    version = "0.1.0-SNAPSHOT"

    java {
        toolchain {
            languageVersion.set(javaVersion)
        }
    }

    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:5.13.4"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        jvmArgs("--enable-native-access=ALL-UNNAMED")
    }
}
