plugins {
    `java`
}

group = "io.github.somehussar.crystalgraphics"
version = "1.0.0-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
    // Disable automatic JVM version filtering so Gradle doesn't reject :core (JVM 21 toolchain)
    // and Taffy (JVM 17+) when resolving transitive dependencies from :mc1710.
    disableAutoTargetJvm()
}

repositories {
    maven {
        name = "GTNH Maven"
        url = uri("https://nexus.gtnewhorizons.com/repository/public/")
    }
    maven {
        name = "Minecraft Libraries"
        url = uri("https://libraries.minecraft.net/")
    }
    mavenCentral()
}

// ── Conditional dependency detection ──────────────────────────────────
// The harness has two dependency tiers:
//
//   HARD (always required):
//     :CrystalGraphics  — rendering API, capabilities, font/text system
//
//   SOFT (nice-to-have, classpath-only when present):
//     : (root project)  — parent mod classes (e.g. CrystalGUI). Only useful
//                          when the root project is a Minecraft mod (provides
//                          patched MC classes, mod-side wrappers, etc.)
//
// Detection strategy:
//   - CrystalGraphics is always present via composite build substitution.
//   - The root project (:) always exists in Gradle's project tree. We include
//     it UNLESS it IS CrystalGraphics itself (i.e. the harness is being built
//     directly inside the CrystalGraphics repo). CrystalGraphics explicitly
//     sets rootProject.name = "CrystalGraphics" in its settings.gradle.kts.
//     Any other root (CrystalGUI, OtherMod, etc.) is treated as a parent mod.
//   - freetype-msdfgen-harfbuzz-bindings is resolved via findProject() first,
//     falling back to a sibling directory scan relative to CrystalGraphics.
// ──────────────────────────────────────────────────────────────────────

// Detect whether a parent mod exists above CrystalGraphics.
// When the root project IS CrystalGraphics, there's no parent mod to include.
// Any other root project name means we're embedded in a mod that depends on
// CrystalGraphics (CrystalGUI, or any third-party mod).
val rootIsParentMod: Boolean by lazy {
    rootProject.name != "CrystalGraphics"
}

dependencies {
    implementation("org.lwjgl.lwjgl:lwjgl:2.9.4-nightly-20150209")
    implementation("org.lwjgl.lwjgl:lwjgl_util:2.9.4-nightly-20150209")
    runtimeOnly("org.lwjgl.lwjgl:lwjgl-platform:2.9.4-nightly-20150209:natives-windows")
    runtimeOnly("org.lwjgl.lwjgl:lwjgl-platform:2.9.4-nightly-20150209:natives-linux")
    runtimeOnly("org.lwjgl.lwjgl:lwjgl-platform:2.9.4-nightly-20150209:natives-osx")

    // ── Hard dependency: CrystalGraphics (rendering API, font system) ──
    // When standalone (root IS CrystalGraphics), use ":" since the root project
    // is CrystalGraphics itself. When embedded in a parent mod, use the Maven
    // coordinate — composite build substitution routes it to :mc1710, which
    // exposes :core and :platform via api() so their classes are visible here.
    // Cannot use project(":CrystalGraphics") — CrystalGraphics is an included
    // build, not a subproject, so that path doesn't exist in the parent's tree.
    if (rootIsParentMod) {
        implementation("com.crystalgraphics:crystalgraphics:1.0.0")
    } else {
        implementation(project(":"))
    }

    // ── JNI bindings: freetype-msdfgen-harfbuzz (com.crystalgraphics.msdfgen.*) ──
    // Harness sources import directly from the bindings (FreeTypeMSDFIntegration,
    // MSDFShape, etc.). CrystalGraphics only shadow-JARs them for Minecraft
    // distribution — they aren't exposed as a transitive compile dependency.
    // When standalone, the bindings are a direct sibling subproject.
    // When embedded in a parent mod, we use the Maven coordinate and rely on
    // composite build substitution in submodules.gradle.kts to route this
    // artifact to the local :freetype-msdfgen-harfbuzz-bindings project.
    if (rootIsParentMod) {
        implementation("com.crystalgraphics:freetype-msdfgen-harfbuzz-bindings:1.0.0-SNAPSHOT")
    } else {
        implementation(project(":freetype-msdfgen-harfbuzz-bindings"))
    }

    // ── Soft dependency: parent mod's mc1710 subproject (mod classes) ───────
    // Previously pointed at root project (:) when root was the Forge mod.
    // Now that Forge lives in :mc1710, reference it directly.
    if (rootIsParentMod) {
        implementation(project(":mc1710"))
    }

    // JOML for Matrix4f (used by PoseStack and world-text projection math)
    implementation("org.joml:joml-jdk8:1.10.1")

    // ── Transitive dependencies of the root project (needed when using raw classes) ──
    // The root project shadow-relocates these into its dev JAR, but the harness uses
    // raw (un-relocated) class output instead (see classpath assembly in runHarness),
    // so these must be available on the classpath directly.
    //
    // Taffy layout engine — referenced by UIElement, LayoutContext, etc.
    // Only included when the root project declares a taffy_version property (e.g. in
    // gradle.properties). This keeps the harness project-agnostic: parent mods that
    // don't use Taffy (or use a different layout engine) won't break the build.
    // Taffy targets JVM 17+ but the harness runs on JVM 25, so this is fine at runtime.
    if (rootIsParentMod && rootProject.properties.containsKey("taffy_version")) {
        implementation("dev.vfyjxf:taffy:${rootProject.properties["taffy_version"]}") {
            attributes {
                attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 17)
            }
        }
    }

    
    implementation("org.apache.logging.log4j:log4j-api:2.20.0")
    implementation("org.apache.logging.log4j:log4j-core:2.20.0")
    implementation("commons-io:commons-io:2.18.0")


    testImplementation("junit:junit:4.13.2")

    // ── Minecraft patched classes (compile-time only) ───────────────────
    // CrystalGraphics references MC types (ResourceLocation, IResourceManagerReloadListener)
    // in its API surface. The harness needs these on the compile classpath so javac can
    // resolve transitive type references. At runtime, the runHarness task assembles
    // patchedMc classes explicitly (and FIRST) on the classpath — see classpath assembly below.
    // Now that Forge lives in :mc1710, patchedMc classes are under mc1710/build/.
    // Cannot use project(":mc1710").file() inside dependencies {} — that resolves to
    // DependencyHandler.project() which returns ProjectDependency, not Project.
    // rootProject.file() is always available and resolves relative to root project dir.
    compileOnly(files(rootProject.file("mc1710/build/classes/java/patchedMc")))
}

// Ensure patchedMcClasses are built before harness compilation
tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    dependsOn(":mc1710:patchedMcClasses")
}

// ── Extract LWJGL natives from platform JARs ─────────────────────────
// LWJGL 2 needs native DLLs on java.library.path (or org.lwjgl.librarypath).
// The natives-windows/linux/osx JARs contain .dll/.so/.dylib inside them;
// they must be extracted to a real directory before the JVM can load them.
val extractLwjglNatives by tasks.registering(Copy::class) {
    group = "harness"
    description = "Extract LWJGL native libraries from platform JARs"

    // Defer configuration resolution to execution time (not task registration time)
    // to avoid resolving :CrystalGraphics composite build during configure phase.
    from({
        configurations.runtimeClasspath.get().files.filter {
            it.name.startsWith("lwjgl-platform-") &&
                    (it.name.contains("natives-windows") || it.name.contains("natives-linux") || it.name.contains("natives-osx"))
        }.map { zipTree(it) }
    })
    into(file("build/lwjgl-natives"))
    // Only extract actual native libraries, skip META-INF
    include("*.dll", "*.so", "*.dylib", "*.jnilib")
}

// ── Resolve freetype-msdfgen-harfbuzz-bindings native directory ───────
// Strategy: try findProject() first (available via composite build substitution),
// then fall back to scanning well-known sibling paths relative to CrystalGraphics.
fun resolveJniNativeDir(osName: String, archName: String): File? {
    val relativePath = "src/main/resources/natives/$osName-$archName"

    // Option 1: Gradle project reference (composite build substitution)
    val bindingsProject = findProject(":freetype-msdfgen-harfbuzz-bindings")
    if (bindingsProject != null) {
        val dir = bindingsProject.file(relativePath)
        if (dir.isDirectory) return dir
    }

    // Option 2: Sibling directory relative to CrystalGraphics submodule
    // (works when CrystalGraphics is checked out as a submodule of CrystalGUI)
    val cgSubmoduleDir = rootProject.file("CrystalGraphics/freetype-msdfgen-harfbuzz-bindings")
    if (cgSubmoduleDir.isDirectory) {
        val dir = File(cgSubmoduleDir, relativePath)
        if (dir.isDirectory) return dir
    }

    return null
}

// ── runHarness task ────────────────────────────────────────────────────
// Launches the standalone debug harness outside Minecraft.
// Usage: ./gradlew.bat :gl-debug-harness:runHarness --args="--mode=triangle"
tasks.register<JavaExec>("runHarness") {
    group = "harness"
    description = "Run the standalone GL debug harness"

    // Always depend on LWJGL native extraction
    dependsOn(extractLwjglNatives)

    // Conditionally depend on parent mod tasks (compiled classes + patched MC classes)
    // only when a parent mod exists above CrystalGraphics.
    // We depend on :compileJava and :processResources because the harness classpath
    // uses raw class output (not the shadowed dev JAR) to avoid relocated-type mismatches.
    if (rootIsParentMod) {
        dependsOn(":mc1710:compileJava", ":mc1710:processResources", ":mc1710:patchedMcClasses", ":mc1710:processPatchedMcResources")
    } else {
        // Standalone CrystalGraphics: still need patchedMcClasses for MC class stubs
        // (e.g. IResourceManagerReloadListener referenced by CgShaderManagerImpl)
        dependsOn(":patchedMcClasses", ":processPatchedMcResources")
    }

    mainClass.set("io.github.somehussar.crystalgraphics.harness.FontDebugHarnessMain")

    // ── Classpath assembly ────────────────────────────────────────────
    // Base classpath: harness's own runtimeClasspath (includes CrystalGraphics
    // and optionally parent mod classes via the dependency block above).
    //
    // When a parent mod exists, also add its patched/deobfuscated Minecraft
    // classes so mod-side runtime references like ResourceLocation and
    // reload-listener interfaces are available.
    //
    // IMPORTANT: The root project's shadow plugin relocates dependencies
    // (e.g. org.joml -> com.crystalgui.shadow.org.joml) in its dev JAR.
    // The harness uses the original org.joml from joml-jdk8, so we must
    // replace the shadowed dev JAR with the root project's raw class output
    // to avoid NoSuchMethodError from relocated type mismatches.
    classpath = if (rootIsParentMod) {
        val mc1710Project = project(":mc1710")
        val rootMainClasses = mc1710Project.file("build/classes/java/main")
        val rootMainResources = mc1710Project.file("build/resources/main")
        val rootPatchedMcClasses = mc1710Project.file("build/classes/java/patchedMc")
        val rootPatchedMcResources = mc1710Project.file("build/resources/patchedMc")

        // Filter out the root project's shadowed dev JAR from the runtime classpath,
        // then add the raw (un-relocated) class output instead.
        val rootDevJarDir = mc1710Project.file("build/libs")
        val filteredClasspath = sourceSets["main"].runtimeClasspath.filter { file ->
            // Exclude any JAR from the root project's libs directory
            !file.absolutePath.startsWith(rootDevJarDir.absolutePath)
        }
        // patchedMc FIRST so MC stubs load before any CrystalGraphics static init
        files(rootPatchedMcClasses, rootPatchedMcResources) + filteredClasspath + files(rootMainClasses, rootMainResources)
    } else {
        // Standalone CrystalGraphics: patchedMc classes/resources FIRST so MC stubs
        // (e.g. IResourceManagerReloadListener) are loaded before any CrystalGraphics
        // code that eagerly references them during static initialization.
        val cgPatchedMcClasses = project(":").layout.buildDirectory.dir("classes/java/patchedMc")
        val cgPatchedMcResources = project(":").layout.buildDirectory.dir("resources/patchedMc")
        files(cgPatchedMcClasses, cgPatchedMcResources) + sourceSets["main"].runtimeClasspath
    }

    // Collect all native library directories
    val nativePaths = mutableListOf<String>()

    // 1. Extracted LWJGL natives directory
    val lwjglNativesDir = file("build/lwjgl-natives").absolutePath
    nativePaths.add(lwjglNativesDir)

    // 2. JNI binding natives (freetype, msdfgen, harfbuzz — all in one subproject)
    val os = System.getProperty("os.name", "").lowercase()
    val arch = System.getProperty("os.arch", "").lowercase()
    val osName = when {
        os.contains("win") -> "windows"
        os.contains("mac") || os.contains("darwin") -> "macos"
        os.contains("linux") || os.contains("nux") -> "linux"
        else -> null
    }
    val archName = when (arch) {
        "amd64", "x86_64" -> "x64"
        "aarch64", "arm64" -> "aarch64"
        "x86", "i386", "i686" -> "x86"
        else -> null
    }
    if (osName != null && archName != null) {
        val nativeDir = resolveJniNativeDir(osName, archName)
        if (nativeDir != null) { nativePaths.add(nativeDir.absolutePath) }
    }

    val existingLibPath = System.getProperty("java.library.path", "")
    val combinedLibPath = buildList {
        if (existingLibPath.isNotBlank()) add(existingLibPath)
        addAll(nativePaths)
    }.joinToString(File.pathSeparator)

    if (combinedLibPath.isNotEmpty()) {
        systemProperty("java.library.path", combinedLibPath)
    }

    // LWJGL 2 also checks org.lwjgl.librarypath for native loading
    systemProperty("org.lwjgl.librarypath", lwjglNativesDir)

    // Output directory for harness artifacts
    systemProperty("harness.output.dir", file("harness-output").absolutePath)

    // Shader hotswap: point to source resources so edits are picked up on R-key reload.
    // When embedded in a parent mod, CrystalGraphics is a composite included build at
    // rootProject/CrystalGraphics/ — findProject(":CrystalGraphics") returns null
    // because included builds are not subprojects. Use a filesystem path instead.
    // When standalone, root IS CrystalGraphics so project(":") resolves directly.
    val shaderOverrideDir = if (rootIsParentMod) {
        // Shaders live in core/src/main/resources within the CrystalGraphics submodule
        rootProject.file("CrystalGraphics/core/src/main/resources")
    } else {
        project(":").file("src/main/resources")
    }
    systemProperty("crystalgraphics.shader.resourceOverrideDir", shaderOverrideDir.absolutePath)

    // Forward system properties for native paths
    systemProperty("freetype.harfbuzz.native.path",
        System.getProperty("freetype.harfbuzz.native.path", ""))
}
