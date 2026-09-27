plugins {
    java
}

group = "com.varlaam"
version = "2.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    // Downloaded by the server at runtime when needed (PyMCLoader), never shaded into the jar
    compileOnly("org.graalvm.polyglot:polyglot:25.4.4.1.1")
}

tasks.withType<JavaCompile> {
    options.release.set(21)
    options.encoding = "UTF-8"
}

tasks.processResources {
    filesMatching("paper-plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.jar {
    archiveFileName.set("PyMC-${project.version}.jar")
}
