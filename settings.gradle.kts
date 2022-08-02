plugins {
    id("com.gradle.enterprise").version("3.10")
    id("io.github.gradle.gradle-enterprise-conventions-plugin").version("0.7.6")
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        mavenLocal()
    }
}

gradleEnterprise {
    buildScan {
        val buildUrl = System.getenv("BUILD_URL") ?: ""
        if (buildUrl.isNotBlank()) {
            link("Build URL", buildUrl)
        }
    }
}

rootProject.name = "test-retry-plugin"

include("plugin")
include("sample-tests")
