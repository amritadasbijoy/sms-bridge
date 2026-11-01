plugins {
    kotlin("jvm") version "1.9.24"
    kotlin("plugin.serialization") version "1.9.24"
    application
}

group = "app.smsbridge"
version = "0.1.0"

val ktor = "2.3.12"

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("app.smsbridge.server.ApplicationKt")
}

dependencies {
    implementation("io.ktor:ktor-server-core-jvm:$ktor")
    implementation("io.ktor:ktor-server-netty-jvm:$ktor")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktor")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktor")
    implementation("io.ktor:ktor-server-status-pages-jvm:$ktor")
    implementation("io.ktor:ktor-server-call-logging-jvm:$ktor")
    implementation("io.ktor:ktor-server-rate-limit-jvm:$ktor")
    implementation("io.ktor:ktor-server-default-headers-jvm:$ktor")
    implementation("io.ktor:ktor-server-forwarded-header-jvm:$ktor")

    implementation("org.postgresql:postgresql:42.7.4")
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("org.flywaydb:flyway-core:10.17.3")
    implementation("org.flywaydb:flyway-database-postgresql:10.17.3")

    // Argon2id password hashing (reviewed implementation).
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    implementation("ch.qos.logback:logback-classic:1.5.6")

    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktor")
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testImplementation("org.testcontainers:postgresql:1.20.1")
    testImplementation("org.testcontainers:junit-jupiter:1.20.1")
}

tasks.test {
    useJUnitPlatform()
    // Docker Engine 29 refuses API versions below 1.40; docker-java (used by Testcontainers)
    // otherwise starts with an older default. 1.44 is supported by Docker 25+.
    systemProperty("api.version", "1.44")
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
