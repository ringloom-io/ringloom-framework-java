pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "ringloom-framework"

include(
    "ringloom-framework-core",
    "ringloom-framework-yaml",
    "ringloom-framework-processor",
    "ringloom-serializer-sbe",
    "ringloom-serializer-fory",
    "ringloom-ioc-avaje",
    "samples:order-management",
    "samples:order-management-avaje",
    "samples:market-data-avaje-sbe",
    "samples:pricing-requests-avaje",
)
