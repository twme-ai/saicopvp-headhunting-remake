plugins {
    java
    checkstyle
    id("com.github.spotbugs") version "6.5.9"
    id("xyz.jpenilla.run-paper") version "3.0.2"
}

group = "dev.saicoremake"
version = "1.0.1"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    withSourcesJar()
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("com.github.spotbugs:spotbugs-annotations:4.10.3")

    testImplementation(platform("org.junit:junit-bom:6.1.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.xerial:sqlite-jdbc:3.53.2.0")
}

tasks.compileJava {
    options.encoding = "UTF-8"
    options.release.set(21)
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.compileTestJava {
    options.encoding = "UTF-8"
    options.release.set(21)
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveBaseName.set("SaicoPvP-Headhunting-Remake")
    manifest {
        attributes(
            "Implementation-Title" to project.name,
            "Implementation-Version" to project.version,
            "Build-Jdk-Spec" to "21"
        )
    }
}

checkstyle {
    toolVersion = "13.8.0"
    maxWarnings = 0
}

spotbugs {
    excludeFilter.set(file("config/spotbugs-exclude.xml"))
}

tasks.runServer {
    minecraftVersion("1.21.11")
    runDirectory.set(layout.projectDirectory.dir("run"))
}

tasks.check {
    dependsOn(tasks.spotbugsMain, tasks.spotbugsTest)
}
