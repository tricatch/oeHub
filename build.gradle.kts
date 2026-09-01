plugins {
    java
    id("com.gradleup.shadow") version "8.3.5"
}

group = "tricatch.oe.hub"
version = "0.9.3"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

val oelinkSrcDir = file("oelink")
val oelinkStaticDir = file("src/main/resources/static/oelink")

tasks.register<Zip>("zipOelinkMac") {
    from(oelinkSrcDir) { include("install_macos_oelink_scheme.sh") }
    archiveFileName = "install_macos_oelink_scheme.sh.zip"
    destinationDirectory = oelinkStaticDir
}

tasks.register<Zip>("zipOelinkWindows") {
    from(oelinkSrcDir) {
        include("install_windows_oelink_scheme.ps1")
        include("install_windows_oelink_scheme.cmd")
    }
    archiveFileName = "install_windows_oelink_scheme.zip"
    destinationDirectory = oelinkStaticDir
}

tasks.register("zipOelinkScripts") {
    dependsOn("zipOelinkMac", "zipOelinkWindows")
}

tasks.named("processResources") {
    dependsOn("zipOelinkScripts")
}

tasks.register<JavaExec>("run") {
    dependsOn("zipOelinkScripts")
    // src/main/resources first so Pebble reads templates directly without processResources
    classpath = files("src/main/resources") + sourceSets.main.get().runtimeClasspath
    mainClass = "tricatch.oe.hub.OeHubApplication"
    val port = project.findProperty("port")?.toString() ?: "36912"
    jvmArgs("-Ddev=true", "-Dapp.version=${project.version}", "-Dport=${port}", "-Djava.net.preferIPv4Stack=true")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.javalin:javalin:7.2.0")
    implementation("ch.qos.logback:logback-classic:1.5.18")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.21.3")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.21.3")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.21.3")
    implementation("io.javalin:javalin-rendering-pebble:7.2.0")
    implementation("io.pebbletemplates:pebble:4.1.1")
    implementation("org.mybatis:mybatis:3.5.19")
    implementation("com.h2database:h2:2.4.240")
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    implementation("io.github.tricatch:gotpache-keytool:0.1.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.79")
    implementation("org.bouncycastle:bcutil-jdk18on:1.79")
    implementation("io.github.azagniotov:ant-style-path-matcher:1.0.0")
    implementation("io.github.littleproxy:littleproxy:2.9.0")

    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    testImplementation("io.javalin:javalin-testtools:7.2.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.4")
    testImplementation("org.assertj:assertj-core:3.27.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.jar {
    enabled = false
}

tasks.shadowJar {
    archiveBaseName = "oeHub"
    archiveClassifier = ""
    archiveVersion = project.version.toString()
    manifest {
        attributes["Main-Class"] = "tricatch.oe.hub.OeHubApplication"
        attributes["Implementation-Version"] = project.version
    }
    mergeServiceFiles()
}

tasks.assemble {
    dependsOn(tasks.test)
    dependsOn(tasks.shadowJar)
}
