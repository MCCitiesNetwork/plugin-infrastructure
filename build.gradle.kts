plugins {
    `java-library`
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
}
