plugins {
    `java-library`
}

group = "io.github.somehussar.crystalgraphics"
version = "1.0.0-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    maven { url = uri("https://nexus.gtnewhorizons.com/repository/public/") }
    maven { url = uri("https://libraries.minecraft.net/") }
    mavenCentral()
}

dependencies {
    implementation("com.crystalgraphics:platform:1.0.0")
    implementation("com.crystalgraphics:freetype-msdfgen-harfbuzz-bindings:1.0.0")
    implementation("com.crystalgraphics:core:1.0.0")
    implementation(project(":core")) // CrystalGUI:core, via composite build substitution
    implementation("dev.vfyjxf:taffy:${rootProject.properties["taffy_version"]}")

    implementation("org.joml:joml:${rootProject.properties["jomlVersion"]}")
    implementation("com.google.code.findbugs:jsr305:3.0.2")

    implementation("org.lwjgl.lwjgl:lwjgl:2.9.4-nightly-20150209")
    implementation("org.lwjgl.lwjgl:lwjgl_util:2.9.4-nightly-20150209")
    runtimeOnly("org.lwjgl.lwjgl:lwjgl-platform:2.9.4-nightly-20150209:natives-windows")
    runtimeOnly("org.lwjgl.lwjgl:lwjgl-platform:2.9.4-nightly-20150209:natives-linux")
    runtimeOnly("org.lwjgl.lwjgl:lwjgl-platform:2.9.4-nightly-20150209:natives-osx")

    implementation("org.apache.logging.log4j:log4j-api:2.26.1")
    implementation("org.apache.logging.log4j:log4j-core:2.26.1")
    implementation("commons-io:commons-io:2.18.0")
    testImplementation("junit:junit:4.13.2")
}

val extractLwjglNatives by tasks.registering(Copy::class) {
    group = "harness"
    from(configurations.runtimeClasspath.get().incoming.files.elements.map { elements ->
        elements.filter { it.asFile.name.startsWith("lwjgl-platform-") }
            .map { zipTree(it) }
    })
    into(file("build/lwjgl-natives"))
    include("*.dll", "*.so", "*.dylib", "*.jnilib")
}

tasks.register<JavaExec>("runHarness") {
    group = "harness"
    dependsOn(extractLwjglNatives)

    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.somehussar.crystalgraphics.harness.FontDebugHarnessMain")

    val lwjglNativesDir = file("build/lwjgl-natives").absolutePath
    systemProperty("org.lwjgl.librarypath", lwjglNativesDir)
    systemProperty("harness.output.dir", file("harness-output").absolutePath)

    if (project.hasProperty("harness.debug")) {
        debugOptions {
            enabled.set(true)
            server.set(true)
            suspend.set(false)
            port.set(5005)
        }
    }

    (project.findProperty("harness.hotswapAgent") as String?)?.let { agentPath ->
        jvmArgs("-javaagent:$agentPath")
    }
}