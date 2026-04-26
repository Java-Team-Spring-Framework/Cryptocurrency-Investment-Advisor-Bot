import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.bundling.Jar

plugins {
    java
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "ru.spbstu"
version = "1.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework:spring-context:7.0.7")
    implementation("org.springframework:spring-webflux:7.0.7")
    implementation("org.springframework:spring-jdbc:7.0.7")
    implementation("org.springframework.amqp:spring-rabbit:3.0.14")
    implementation("org.jooq:jooq:3.19.26")
    implementation("io.projectreactor.netty:reactor-netty-http:1.1.10")
    implementation("org.telegram:telegrambots:6.8.0")
    implementation("org.postgresql:postgresql:42.7.7")
    implementation("org.slf4j:slf4j-api:2.0.11")
    implementation("ch.qos.logback:logback-classic:1.4.11")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.2")
    testImplementation("org.springframework:spring-test:7.0.7")
    testImplementation("org.springframework.restdocs:spring-restdocs-webtestclient:3.0.0")
    testImplementation("io.projectreactor:reactor-test:3.7.14")
}

application {
    mainClass.set("ru.spbstu.cryptoadvisor.Application")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.register<Jar>("fatJar") {
    archiveBaseName.set("crypto-investment-advisor-bot")
    archiveVersion.set("1.0-SNAPSHOT")
    archiveClassifier.set("fat")
    manifest {
        attributes["Main-Class"] = application.mainClass.get()
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map {
        if (it.isDirectory) it else zipTree(it).matching {
            exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "**/Log4j2Plugins.dat")
        }
    })
    with(tasks.jar.get() as CopySpec)
}

tasks.named("build") {
    dependsOn("fatJar")
}
