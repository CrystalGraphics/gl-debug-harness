import xyz.wagyourtail.jvmdg.gradle.task.files.DowngradeFiles
import xyz.wagyourtail.jvmdg.gradle.task.files.ShadeFiles

plugins {
    `java-library`
    id("xyz.wagyourtail.jvmdowngrader") version "1.3.6"
}

group = "io.github.somehussar.crystalgraphics"
version = "1.0.0-SNAPSHOT"

// The harness is *mandatory* Java 8 at runtime (mirrors the MC 1.7.10 / LWJGL 2
// target environment). The toolchain below controls both compilation AND the
// JVM `runHarness` launches on.
java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(8))
    }
}

repositories {
    maven { url = uri("https://nexus.gtnewhorizons.com/repository/public/") }
    maven { url = uri("https://libraries.minecraft.net/") }
    mavenCentral()
}

// ── Dependency wiring ───────────────────────────────────────────────────────
//
// CrystalGraphics is brought in via `includeBuild("CrystalGraphics")` in the
// root settings.gradle.kts (a *composite* build). Composite-build projects do
// NOT get a project path inside this build (there is no such thing as
// `project(":CrystalGraphics:core")` here) — they can only be reached through
// normal module coordinates on a resolvable configuration, which Gradle then
// transparently substitutes for the real project via the substitution rules
// already declared in settings.gradle.kts. Gradle wires the task dependency
// (build CrystalGraphics's jar) automatically when the configuration resolves.
//
// `platform` and `freetype-msdfgen-harfbuzz-bindings` already compile straight
// to Java 8 (sourceCompatibility 1.8 in their own build files) — they can be
// consumed directly, no downgrade pass needed.
dependencies {
    implementation("com.crystalgraphics:platform:1.0.0")
    implementation("com.crystalgraphics:freetype-msdfgen-harfbuzz-bindings:1.0.0")
}

// `CrystalGraphics:core` compiles to real Java 17 bytecode, and this project's
// own `:core` compiles to real Java 21 bytecode — both must be bytecode-
// downgraded to run inside this mandatory-Java-8 harness process.
//
// IMPORTANT: these two are run through *separate* DowngradeFiles/ShadeFiles
// pipelines, not merged into one. Both source projects are literally named
// "core" (CrystalGraphics/core and CrystalGUI/core), so their default jar
// filenames both resolve to "core.jar" — merging them into a single task's
// input/output directory silently clobbers one with the other (confirmed:
// only CrystalGUI's core.jar survived in build/tmp/shadeDeps/ when they were
// merged). Giving each its own task name gives each its own output directory,
// which sidesteps the collision entirely.
val cgCoreToDowngrade by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}

val cguiCoreToDowngrade by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}

// Taffy (CGUI:core's layout engine dependency) ships modern bytecode and is
// only referenced by CrystalGUI:core, not CrystalGraphics:core.
val taffyToDowngrade by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}

// DowngradeFiles needs a classpath to resolve the types referenced by the
// bytecode it's rewriting (stack-map frames, API stub lookups). Its default is
// `sourceSets.main.runtimeClasspath`, which extends `implementation` — but
// `implementation` below also carries the shaded outputs. Using the default
// would create downgrade -> runtimeClasspath -> implementation -> shade ->
// downgrade, a genuine cycle. These configurations are deliberately kept
// independent of `implementation` to avoid that.
val cgDowngradeClasspath by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}
val cguiDowngradeClasspath by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}

dependencies {
    cgCoreToDowngrade("com.crystalgraphics:core:1.0.0")
    cguiCoreToDowngrade(project(":core"))
    taffyToDowngrade("dev.vfyjxf:taffy:${rootProject.properties["taffy_version"]}")

    // CrystalGraphics:core's own compileOnly deps — needed so the downgrader can
    // resolve types referenced in its bytecode (jgltf/obj model loading, jsr305
    // annotations, hotswap-agent-core hooks, platform/freetype-bindings APIs).
    cgDowngradeClasspath("com.crystalgraphics:platform:1.0.0")
    cgDowngradeClasspath("com.crystalgraphics:freetype-msdfgen-harfbuzz-bindings:1.0.0")
    cgDowngradeClasspath("org.lwjgl.lwjgl:lwjgl:2.9.4-nightly-20150209")
    cgDowngradeClasspath("org.joml:joml-jdk8:1.10.1")
    cgDowngradeClasspath("org.apache.logging.log4j:log4j-api:2.20.0")
    cgDowngradeClasspath("commons-io:commons-io:2.4")
    cgDowngradeClasspath("com.google.code.findbugs:jsr305:3.0.2")
    cgDowngradeClasspath("de.javagl:obj:0.4.0")
    cgDowngradeClasspath("de.javagl:jgltf-model:2.0.4")
    cgDowngradeClasspath("org.hotswapagent:hotswap-agent-core:1.4.1")

    // CrystalGUI:core's own compileOnly deps — NOTE: this is the *modern* JOML
    // artifact ("org.joml:joml"), not "joml-jdk8" — CGUI:core is real Java 21
    // bytecode and was compiled against the modern JOML API surface.
    cguiDowngradeClasspath("com.crystalgraphics:core:1.0.0")
    cguiDowngradeClasspath("com.crystalgraphics:platform:1.0.0")
    cguiDowngradeClasspath("dev.vfyjxf:taffy:${rootProject.properties["taffy_version"]}")
    cguiDowngradeClasspath("org.joml:joml:${rootProject.properties["jomlVersion"]}")
    cguiDowngradeClasspath("com.google.code.findbugs:jsr305:3.0.2")
    cguiDowngradeClasspath("org.lwjgl.lwjgl:lwjgl:2.9.4-nightly-20150209")
    cguiDowngradeClasspath("org.apache.logging.log4j:log4j-core:2.26.1")
}

// Eager task creation (not `tasks.registering`) is deliberate: DowngradeFiles /
// ShadeFiles compute `outputCollection` lazily on first read, and that getter
// registers a dynamic task output (TaskOutputs.dirs(...)). The plugin's own
// docs say to read `outputCollection` only *after the task is configured* —
// reading it through a deferred `Provider.map {}` (as with `tasks.registering`)
// let Gradle evaluate it after the task had already started executing, which
// is illegal. Creating eagerly and reading the property immediately keeps that
// read at configuration time, where it belongs.
val downgradeCgCore = tasks.create<DowngradeFiles>("downgradeCgCore") {
    inputCollection = cgCoreToDowngrade
    classpath = cgDowngradeClasspath
    downgradeTo = JavaVersion.VERSION_1_8
}
val shadeCgCore = tasks.create<ShadeFiles>("shadeCgCore") {
    inputCollection = downgradeCgCore.outputCollection
    downgradeTo = JavaVersion.VERSION_1_8
}

val downgradeCguiCore = tasks.create<DowngradeFiles>("downgradeCguiCore") {
    inputCollection = cguiCoreToDowngrade + taffyToDowngrade
    classpath = cguiDowngradeClasspath
    downgradeTo = JavaVersion.VERSION_1_8
}
val shadeCguiCore = tasks.create<ShadeFiles>("shadeCguiCore") {
    inputCollection = downgradeCguiCore.outputCollection
    downgradeTo = JavaVersion.VERSION_1_8
}

dependencies {
    implementation("org.lwjgl.lwjgl:lwjgl:2.9.4-nightly-20150209")
    implementation("org.lwjgl.lwjgl:lwjgl_util:2.9.4-nightly-20150209")
    runtimeOnly("org.lwjgl.lwjgl:lwjgl-platform:2.9.4-nightly-20150209:natives-windows")
    runtimeOnly("org.lwjgl.lwjgl:lwjgl-platform:2.9.4-nightly-20150209:natives-linux")
    runtimeOnly("org.lwjgl.lwjgl:lwjgl-platform:2.9.4-nightly-20150209:natives-osx")

    implementation("org.joml:joml-jdk8:1.10.1")
    implementation("org.apache.logging.log4j:log4j-api:2.20.0")
    implementation("org.apache.logging.log4j:log4j-core:2.20.0")
    implementation("commons-io:commons-io:2.18.0")
    testImplementation("junit:junit:4.13.2")

}
dependencies {
    implementation(shadeCgCore.outputCollection.asFileTree.matching { include("**/*.jar") })
    implementation(shadeCguiCore.outputCollection.asFileTree.matching { include("**/*.jar") })
}

// ── Lazy Native Extraction ──
val extractLwjglNatives by tasks.registering(Copy::class) {
    group = "harness"
    from(configurations.runtimeClasspath.get().incoming.files.elements.map { elements ->
        elements.filter { it.asFile.name.startsWith("lwjgl-platform-") }
            .map { zipTree(it) }
    })
    into(file("build/lwjgl-natives"))
    include("*.dll", "*.so", "*.dylib", "*.jnilib")
}

// ── runHarness Task ──
//
// Hotswap workflow:
//   1. `./gradlew :gl-debug-harness:runHarness` launches the harness on the
//      Java 8 toolchain above (mandatory — this must be a genuine Java 8 JVM,
//      not just Java-8-shaped bytecode running on a newer JVM).
//   2. Attach a debugger (`-Pharness.debug=true` opens a JDWP socket on port
//      5005 for IntelliJ's "Attach to Process").
//   3. Edit CrystalGraphics:core / :core source, then re-run
//      `:gl-debug-harness:downgradeDeps :gl-debug-harness:shadeDeps` (or just
//      `runHarness` again — Gradle's up-to-date checking means only the
//      changed module gets recompiled + re-downgraded + re-shaded).
//   4. With a debugger attached, IntelliJ pushes matching method-body changes
//      into the already-running process automatically (standard JDI hotswap).
//      This works for method-body edits on a stock Java 8 JVM. Structural
//      changes (new/removed methods or fields) additionally require a DCEVM-
//      patched JDK 8 + the HotSwapAgent javaagent — set `-Pharness.hotswapAgent=
//      /path/to/hotswap-agent.jar` (from https://github.com/HotswapProjects/HotswapAgent)
//      once installed; it is optional and silently skipped if unset.
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