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

    // The real parsers. core/ ships word-list lexers so it can load with no natives at all, and they are
    // genuinely fine for keywords, strings and comments -- but a lexer calls any identifier before a "("
    // a function, so a constructor, an enum constant, a declaration and a call are one colour and no
    // scheme can separate them. Without this the editor looks plausible and cannot match any reference
    // palette, which is exactly how it went unnoticed through a round of scheme tuning.
    implementation(project(":language"))
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
    // crystalgui.* as well as crystalgraphics.*: CrystalGUI has its own debug flags now
    // (crystalgui.keymap.trace), and a filter naming only one project fails them in exactly the silent
    // way described above -- the flag is accepted on the command line and reaches nothing.
    System.getProperties().stringPropertyNames()
        .filter { it.startsWith("crystalgraphics.") || it.startsWith("crystalgui.") }
        .forEach { systemProperty(it, System.getProperty(it)) }

    // Read assets from the SOURCE trees, so an edit-and-save is visible to the running harness with no
    // rebuild -- Ctrl+R for stylesheets, and the existing reload paths for shaders and everything else.
    //
    // Without this CgIO resolves from the classpath, which for a Gradle run is */build/resources/main --
    // a COPY that processResources made at build time. Editing src/main/resources/... would then change
    // nothing the running harness can see, and a reload would faithfully re-read the stale copy and look
    // broken.
    //
    // ALL THREE ROOTS, because each project keeps its own resources and CgIO tries them in order:
    // assets/harness/** here, assets/crystalgui/** in core, assets/crystalgraphics/** in the composite
    // build. One root can only ever serve one of them; the others would silently fall back to their
    // build-time copies, which is the version of this that looks like it works.
    //
    // Only set when the caller has not chosen their own, so an explicit
    // -Dcrystalgraphics.resourceOverrideDirs=... (or the older singular spelling) still wins outright.
    val alreadyChosen = System.getProperties().stringPropertyNames().any {
        it == "crystalgraphics.resourceOverrideDirs" || it == "crystalgraphics.shader.resourceOverrideDir"
    }
    if (!alreadyChosen) {
        val roots = listOf(
            file("src/main/resources"),                                   // the harness's own
            project(":core").file("src/main/resources"),                  // CrystalGUI
            rootProject.file("CrystalGraphics/core/src/main/resources")   // CrystalGraphics (composite)
        ).filter { it.isDirectory }
        systemProperty("crystalgraphics.resourceOverrideDirs",
            roots.joinToString(File.pathSeparator) { it.absolutePath })
        // Also under the ORIGINAL name, with the single most useful root. A CgIO built before multi-root
        // support ignores the plural property entirely and would then have no override at all -- i.e. hot
        // reload would silently stop working, with nothing on screen to say why. The old name takes one
        // path, so it gets CrystalGUI's: the stylesheets are what this is for.
        project(":core").file("src/main/resources").takeIf { it.isDirectory }?.let {
            systemProperty("crystalgraphics.shader.resourceOverrideDir", it.absolutePath)
        }
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
