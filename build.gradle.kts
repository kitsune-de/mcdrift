plugins {
    java
    application
}

group = "dev.mcdrift"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.ow2.asm:asm:9.10.1")
    implementation("org.ow2.asm:asm-tree:9.10.1")
    implementation("info.picocli:picocli:4.7.6")
    implementation("com.google.code.gson:gson:2.11.0")
    annotationProcessor("info.picocli:picocli-codegen:4.7.6")

    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        // Analyzer targets Java 21 so it runs on more machines, while ASM 9.7
        // still reads the Java 25 class files that 26.1 plugins are built with.
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application {
    mainClass = "dev.mcdrift.cli.Main"
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Aproject=${project.group}/${project.name}")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

// Runnable fat jar: `java -jar mcdrift.jar plugin.jar` with no classpath setup.
tasks.jar {
    manifest {
        attributes["Main-Class"] = "dev.mcdrift.cli.Main"
        attributes["Implementation-Version"] = project.version
    }
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith("jar") }
            .map { zipTree(it) }
    })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class")
    archiveFileName = "mcdrift.jar"
}
