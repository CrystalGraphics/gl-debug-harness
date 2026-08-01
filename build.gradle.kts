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

    compileOnly("org.projectlombok:lombok:1.18.44")
    annotationProcessor("org.projectlombok:lombok:1.18.44")
    testCompileOnly("org.projectlombok:lombok:1.18.44")
    testAnnotationProcessor("org.projectlombok:lombok:1.18.44")

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

    // Forward every -Dcrystalgraphics.* from the Gradle invocation into the forked JVM.
    //
    // Without this they set properties on the Gradle daemon and never reach the harness, so every
    // documented debug flag (shader.devmode, redirector.verbose, profile.autoOrbit,
    // text.uploadDistanceFieldAsFloat, ...) silently does nothing when passed the obvious way:
    //   ./gradlew :gl-debug-harness:runHarness -Dcrystalgraphics.shader.devmode=true
    // Failing silently is the worst version of this, because the run looks like evidence the flag
    // had no effect rather than evidence it was never applied.
    System.getProperties().stringPropertyNames()
        .filter { it.startsWith("crystalgraphics.") }
        .forEach { systemProperty(it, System.getProperty(it)) }

    // Read assets from the SOURCE tree, so Ctrl+R (stylesheet hot reload) sees the file you just saved.
    //
    // Without this CgIO resolves from the classpath, which for a Gradle run is core/build/resources/main
    // -- a COPY that processResources made at build time. Editing core/src/main/resources/... would then
    // change nothing the running harness can see, and Ctrl+R would faithfully re-read the stale copy and
    // look broken.
    //
    // Only set when the caller has not chosen their own: -Dcrystalgraphics.shader.resourceOverrideDir=...
    // still wins, which is how a CrystalGraphics shader session already points this elsewhere. The
    // override is a single root, so it cannot serve both projects at once -- pointing it at CrystalGUI is
    // the useful default here because that is where the stylesheets are. Anything it does not contain
    // (assets/crystalgraphics/**) simply misses and falls through to the classpath, so this is additive.
    if (!System.getProperties().stringPropertyNames()
            .contains("crystalgraphics.shader.resourceOverrideDir")) {
        systemProperty("crystalgraphics.shader.resourceOverrideDir",
            project(":core").file("src/main/resources").absolutePath)
    }

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

    // Extra JVM flags for one-off diagnostics — e.g. GC logging while chasing a frame spike:
    //   ./gradlew :gl-debug-harness:runHarness --args="--mode=text-3d" -Pharness.jvmArgs="-Xlog:gc"
    (project.findProperty("harness.jvmArgs") as String?)?.let { extra ->
        jvmArgs(extra.split(" ").filter { it.isNotBlank() })
    }
}
