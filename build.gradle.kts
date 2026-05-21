plugins {
    kotlin("jvm") version "2.2.21"
    application
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

application {
    mainClass.set("org.example.MainKt")
}

dependencies {
    implementation("dev.langchain4j:langchain4j:0.35.0")
    implementation("dev.langchain4j:langchain4j-open-ai:0.35.0")
    implementation("dev.langchain4j:langchain4j-embeddings-all-minilm-l6-v2-q:0.35.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.jsoup:jsoup:1.17.2")
    
    // Ktor Server
    val ktorVersion = "2.3.11"
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-serialization-gson-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-config-yaml:$ktorVersion")
    implementation("ch.qos.logback:logback-classic:1.4.14")

    testImplementation(kotlin("test"))
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")

}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}