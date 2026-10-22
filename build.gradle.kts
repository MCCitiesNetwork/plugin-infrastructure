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

    // Tests need Paper on the classpath too (it is only compileOnly for main), plus an in-memory
    // JDBC database for the schema-migrator tests and Mockito for the module-lifecycle tests.
    testImplementation(libs.paper.api)
    testImplementation(libs.configurate.yaml)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.mockito.core)
    testImplementation(libs.h2)
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
    withSourcesJar()
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    repositories {
        maven {
            name = "deploy"
            // Route by version: snapshots and releases live under sibling paths on the same host.
            val snapshot = version.toString().endsWith("-SNAPSHOT")
            val defaultUrl = "https://maven.minecraftcitiesnetwork.com/" + if (snapshot) "snapshots" else "releases"
            url = uri(providers.gradleProperty("deployUrl").getOrElse(defaultUrl))
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
                url.set("https://github.com/MCCitiesNetwork/plugin-infrastructure")
                developers {
                    developer {
                        id.set("md5sha256")
                        name.set("Andrew Wong")
                        email.set("42793301+md5sha256@users.noreply.github.com")
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/MCCitiesNetwork/plugin-infrastructure.git")
                    developerConnection.set("scm:git:ssh://github.com/MCCitiesNetwork/plugin-infrastructure.git")
                    url.set("https://github.com/MCCitiesNetwork/plugin-infrastructure")
                }
            }
        }
    }
}
