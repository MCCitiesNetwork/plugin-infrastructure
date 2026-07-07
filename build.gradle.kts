plugins {
    `java-library`
    `maven-publish`
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly(libs.paper.api)
    compileOnlyApi(libs.configurate.yaml)
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
    withSourcesJar()
}

publishing {
    repositories {
        maven {
            name = "deploy"
            url = uri(providers.gradleProperty("deployUrl").getOrElse("https://example.com"))
            credentials {
                username = providers.gradleProperty("deployUsername").orNull
                password = providers.gradleProperty("deployPassword").orNull
            }
        }
    }
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name.set("Plugin Infrastructure")
                description.set("Shared infrastructure and utilities for Paper plugins")
            }
        }
    }
}
