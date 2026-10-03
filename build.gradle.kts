import java.security.MessageDigest

plugins {
    `java-library`
}

group = "com.crystalgraphics"
version = "1.0.0-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    maven { url = uri("https://nexus.gtnewhorizons.com/repository/public/") }
    maven { url = uri("https://libraries.minecraft.net/") }
    mavenCentral()
}

val lwjglVersion = "3.4.1"
val lwjglModules = listOf("lwjgl", "lwjgl-glfw", "lwjgl-opengl", "lwjgl-vulkan", "lwjgl-shaderc", "lwjgl-spvc", "lwjgl-vma")
val lwjglNatives: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arm = System.getProperty("os.arch").let { it == "aarch64" || it.startsWith("arm") }
    when {
        os.contains("win") -> if (arm) "natives-windows-arm64" else "natives-windows"
        os.contains("mac") || os.contains("darwin") -> if (arm) "natives-macos-arm64" else "natives-macos"
        else -> if (arm) "natives-linux-arm64" else "natives-linux"
    }
}

// CrystalGraphics only. A project on top of it -- CrystalGUI -- adds its scenes as a HarnessExtension from
// its own module, which puts itself on runHarness' classpath (see hostAssetRoots below).
dependencies {
    // `api`: a scene an extension writes is handed CrystalGraphics and JOML types by HarnessContext.
    api("com.crystalgraphics:platform:1.0.0")
    api("com.crystalgraphics:freetype-msdfgen-harfbuzz-bindings:1.0.0")
    api("com.crystalgraphics:core:1.0.0")
    // Tier 1 for LWJGL 3: the GL backend, context, input and cursor, shared with every modern host.
    implementation("com.crystalgraphics:lwjgl3:1.0.0")
    // Tier 1 for Vulkan: the tracked backend's GLSL compiler, for --device=tracked.
    implementation("com.crystalgraphics:vulkan:1.0.0")

    api("org.joml:joml:${rootProject.findProperty("jomlVersion") ?: "1.10.8"}")
    implementation("com.google.code.findbugs:jsr305:3.0.2")
    // Core compiles against its mesh loaders and ships neither: CgObjLoader and CgGltfLoader, for mesh-test.
    runtimeOnly("de.javagl:obj:0.4.0")
    runtimeOnly("de.javagl:jgltf-model:2.0.4")

    // LWJGL 3, with the Vulkan, shaderc and VMA bindings the device seam needs. Natives for the OS this
    // runs on; LWJGL extracts them itself. lwjgl-vulkan has natives only on macOS (MoltenVK) -- elsewhere
    // the loader is the system's.
    implementation(platform("org.lwjgl:lwjgl-bom:$lwjglVersion"))
    for (module in lwjglModules) {
        implementation("org.lwjgl:$module")
        if (module != "lwjgl-vulkan" || lwjglNatives.startsWith("natives-macos")) {
            runtimeOnly("org.lwjgl:$module::$lwjglNatives")
        }
    }

    compileOnly("org.projectlombok:lombok:1.18.44")
    annotationProcessor("org.projectlombok:lombok:1.18.44")
    testCompileOnly("org.projectlombok:lombok:1.18.44")
    testAnnotationProcessor("org.projectlombok:lombok:1.18.44")

    implementation("org.apache.logging.log4j:log4j-api:2.26.1")
    implementation("org.apache.logging.log4j:log4j-core:2.26.1")
    implementation("commons-io:commons-io:2.18.0")
    testImplementation("junit:junit:4.13.2")
}

// UTF-8, stated rather than inherited -- the same rule core/ already keeps, and this module needed it
// for the same reason one build later.
//
// CrystalGUI's own gradle.properties puts -Dfile.encoding=UTF-8 on its daemon, so building from HERE
// works by luck. A consumer that includes this build brings its own daemon: RPG-Core's defaults to
// windows-1252, and every non-ASCII character in these sources became "unmappable character" the first
// time it recompiled the harness. Invisible until somebody builds from the other side.
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

// -Pharness.bytecode=8: run against the engine modules' Java 8 copies -- the bytecode the shipped jars
// carry -- instead of their Java 25 originals, on the same JVM. What FrameBench compares.
(findProperty("harness.bytecode") as String?)?.toInt()?.let { level ->
    configurations.named("runtimeClasspath") {
        attributes { attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, level) }
    }
    // A host module asking the same of ITS classpath reaches this project too, which is Java 25 only.
    // Advertised as `level` so it still resolves: it runs on the same Java 25 JVM either way.
    listOf("apiElements", "runtimeElements").forEach { name ->
        configurations.named(name) { attributes { attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, level) } }
    }
}

/**
 * Source resource roots a host build adds, so an edit to its assets is what Ctrl+R reads. Read after the
 * harness's own and before CrystalGraphics'.
 *
 *   (project(":gl-debug-harness").extra["hostAssetRoots"] as ConfigurableFileCollection)
 *       .from(file("src/main/resources"))
 */
val hostAssetRoots: ConfigurableFileCollection = files()
extra["hostAssetRoots"] = hostAssetRoots

// DOWNLEVEL CONTEXTS: Mesa's software GL in place of the driver, capped at a version and stripped of every
// extension that version lacks -- contexts genuinely without compute, which a forced tier on a 4.6 card is not,
// and Mesa's GLSL compiler, which refuses what NVIDIA lets through (gpu-compute C12):
//
//   ./gradlew :gl-debug-harness:runHarness --args="--mode=compute-tiers" -Pharness.downlevel=mac41   # a Mac's 4.1: G40
//   ./gradlew :gl-debug-harness:runHarness --args="--mode=compute-tiers" -Pharness.downlevel=gl33    # bare 3.3: G33
//
// downlevel/<name>.txt is what such a context lists. Whatever else Mesa lists (downlevel/mesa.txt) is removed,
// and the engine treats all of it as absent (-Dcrystalgraphics.gl.disableExtensions): Mesa cannot remove some, and
// removing others would take a kept one with them (downlevel/mesa-shared.txt). On Windows Mesa is fetched once,
// pinned; elsewhere the system's Mesa runs in software.
val mesaVersion = "26.2.3"
val mesaDir: File = gradle.gradleUserHomeDir.resolve("caches/crystalgraphics-harness/mesa-$mesaVersion")
val downlevels = mapOf("mac41" to "4.1", "gl33" to "3.3")
val fetchMesa = tasks.register("fetchMesa") {
    group = "harness"
    description = "Fetches Mesa $mesaVersion for Windows (pal1000/mesa-dist-win), for -Pharness.downlevel."
    onlyIf { !mesaDir.resolve("x64/opengl32.dll").isFile }
    doLast {
        fun fetch(url: String, sha256: String, to: File) {
            to.parentFile.mkdirs()
            uri(url).toURL().openStream().use { input -> to.outputStream().use { input.copyTo(it) } }
            val digest = MessageDigest.getInstance("SHA-256").digest(to.readBytes())
                .joinToString("") { "%02x".format(it) }
            if (digest != sha256) throw GradleException("$url hashed $digest, not the pinned $sha256")
        }
        val sevenZip = mesaDir.resolve("7zr.exe")
        val archive = mesaDir.resolve("mesa.7z")
        fetch("https://github.com/ip7z/7zip/releases/download/26.03/7zr.exe",
            "ad4c82fadcbdf93c03b4fc440f300509c7d60c5c2f4d183e35d9d70d6957037d", sevenZip)
        fetch("https://github.com/pal1000/mesa-dist-win/releases/download/$mesaVersion/mesa3d-$mesaVersion-release-msvc.7z",
            "3f3613adb43cfd0f2e665ce2400b130c275f0b3317cb3a05566320a3a67589ed", archive)
        val extract = ProcessBuilder(sevenZip.absolutePath, "x", "-y", "-o" + mesaDir.absolutePath, archive.absolutePath, "x64/*")
            .redirectErrorStream(true).start()
        val log = extract.inputStream.bufferedReader().readText()
        if (extract.waitFor() != 0) throw GradleException("7zr could not extract Mesa:\n$log")
        archive.delete()
    }
}

// CrystalGraphics' checkout: the included build of that name, or a sibling directory for a host that has
// it as a plain folder.
val crystalGraphicsDir: File = gradle.includedBuilds.firstOrNull { it.name == "CrystalGraphics" }?.projectDir
    ?: rootProject.file("CrystalGraphics")

tasks.register<JavaExec>("runHarness") {
    group = "harness"
    // GLFW must own the first thread on macOS.
    if (lwjglNatives.startsWith("natives-macos")) jvmArgs("-XstartOnFirstThread")
    // RenderDoc's in-app API is called through java.lang.foreign (--renderdoc).
    jvmArgs("--enable-native-access=ALL-UNNAMED")

    // A CONSUMER'S OWN CLASSES, so a scene can build that project's real screens instead of a copy:
    //
    //   -Pharness.extraClasspath=X:/projects/RPG-Core-NeoForge/build/classes/java/main
    //
    // A copy is what the alternative always becomes -- the harness cannot depend on a project that
    // depends on it, so without this every mod screen worth previewing gets re-implemented here and
    // drifts from the one that ships. Reflection at the scene's end keeps the compile dependency at
    // zero in both directions; see CrystalGUI's RpgConsoleScene.
    val extraClasspath = (project.findProperty("harness.extraClasspath") as String?)
        ?.split(File.pathSeparator)
        .orEmpty()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { file(it) }
    extraClasspath.filterNot { it.exists() }.forEach {
        // Loud, because the silent version is a scene that reports the consumer's class as absent --
        // which looks exactly like the consumer not having written it.
        logger.warn("[harness] -Pharness.extraClasspath names '${'$'}{it.absolutePath}', which does not exist")
    }
    classpath = sourceSets.main.get().runtimeClasspath + files(extraClasspath)
    mainClass.set("com.crystalgraphics.harness.FontDebugHarnessMain")

    systemProperty("harness.output.dir", file("harness-output").absolutePath)

    // Forward every -Dcrystalgraphics.* from the Gradle invocation into the forked JVM.
    //
    // Without this they set properties on the Gradle daemon and never reach the harness, so every
    // documented debug flag (shader.devmode, redirector.verbose, profile.autoOrbit,
    // text.uploadDistanceFieldAsFloat, ...) silently does nothing when passed the obvious way:
    //   ./gradlew :gl-debug-harness:runHarness -Dcrystalgraphics.shader.devmode=true
    // Failing silently is the worst version of this, because the run looks like evidence the flag
    // had no effect rather than evidence it was never applied.
    // A host forwards its own prefix the same way, from its own build.
    System.getProperties().stringPropertyNames()
        .filter { it.startsWith("crystalgraphics.") }
        .forEach { systemProperty(it, System.getProperty(it)) }

    // Read assets from the SOURCE trees, so an edit-and-save is visible to the running harness with no
    // rebuild -- Ctrl+R for stylesheets, and the existing reload paths for shaders and everything else.
    //
    // Without this CgIO resolves from the classpath, which for a Gradle run is */build/resources/main --
    // a COPY that processResources made at build time. Editing src/main/resources/... would then change
    // nothing the running harness can see, and a reload would faithfully re-read the stale copy and look
    // broken.
    //
    // EVERY PROJECT'S ROOT, because each keeps its own resources and CgIO tries them in order: the
    // harness's, each host's (hostAssetRoots), then CrystalGraphics'. One root can only ever serve one
    // project; the others would silently fall back to their build-time copies, which is the version of
    // this that looks like it works.
    //
    // Only set when the caller has not chosen their own, so an explicit
    // -Dcrystalgraphics.resourceOverrideDirs=... (or the older singular spelling) still wins outright.
    val alreadyChosen = System.getProperties().stringPropertyNames().any {
        it == "crystalgraphics.resourceOverrideDirs" || it == "crystalgraphics.shader.resourceOverrideDir"
    }
    // A CONSUMER'S resources, so a mod's theme and stylesheets can be authored here rather than in a
    // game client. Several roots may be given, separated by the platform path separator:
    //
    //   -Pharness.assetRoots=X:/projects/RPG-Core-NeoForge/src/main/resources
    //
    // APPENDED to the roots below, never replacing them: those are what makes Ctrl+R reach the engines'.
    // Appended LAST because CgIO takes the first root that answers, so a consumer cannot shadow an
    // engine asset by accident.
    val extraRoots = (project.findProperty("harness.assetRoots") as String?)
        ?.split(File.pathSeparator)
        .orEmpty()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { file(it) }

    if (alreadyChosen && extraRoots.isNotEmpty()) {
        logger.warn("[harness] -Pharness.assetRoots is ignored: -Dcrystalgraphics.resourceOverrideDirs " +
            "was set explicitly and takes the whole list. Add your root to that property instead.")
    }
    extraRoots.filterNot { it.isDirectory }.forEach {
        // Loud, because the silent version is a scene whose theme is simply never found -- which looks
        // exactly like a theme that failed to parse.
        logger.warn("[harness] -Pharness.assetRoots names '${it.absolutePath}', which is not a directory")
    }

    if (!alreadyChosen) {
        // At EXECUTION time: a host adds its roots after this script has run.
        jvmArgumentProviders.add(CommandLineArgumentProvider {
            val roots = (listOf(file("src/main/resources")) + hostAssetRoots.files
                + crystalGraphicsDir.resolve("core/src/main/resources") + extraRoots).filter { it.isDirectory }
            // Also under the ORIGINAL name, with the first root after the harness's own. A CgIO built before
            // multi-root support ignores the plural property entirely and would then have no override at all
            // -- hot reload silently stopping, with nothing on screen to say why.
            listOfNotNull(
                "-Dcrystalgraphics.resourceOverrideDirs=" + roots.joinToString(File.pathSeparator) { it.absolutePath },
                roots.drop(1).firstOrNull()?.let { "-Dcrystalgraphics.shader.resourceOverrideDir=" + it.absolutePath })
        })
    }

    // The scene, for a caller that cannot pass `--args` -- a task in another build can only
    // `dependsOn` this one, so a consumer including CrystalGUI has no other way to pick a scene.
    // Project properties are shared across a composite, which is what makes it work from either side.
    //
    // An argumentProvider, not `args(...)`: providers are asked at EXECUTION time, after Gradle has
    // applied `--args`, so this stands down when the caller passed a mode. Set eagerly it would append
    // AFTER theirs and win, since the parser takes the last `--mode=` -- an override that silently
    // does the opposite of what it says.
    val requestedMode = project.findProperty("harness.mode") as String?
    if (requestedMode != null) {
        argumentProviders.add(CommandLineArgumentProvider {
            // `args` on a JavaExec is a nullable List, not a Provider -- `.orNull` does not exist on it,
            // and the unresolved type cascades into the `startsWith` below reading as a second error.
            if (args.orEmpty().any { it.startsWith("--mode=") }) emptyList()
            else listOf("--mode=$requestedMode")
        })
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

    (findProperty("harness.downlevel") as String?)?.let { name ->
        val version = downlevels[name]
            ?: throw GradleException("-Pharness.downlevel=$name is not one of ${downlevels.keys}")
        fun names(path: String) = file(path).readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        val keep = names("downlevel/$name.txt").toSet()
        val shared = names("downlevel/mesa-shared.txt").map { it.split(Regex("\\s+")) }
            .filter { (_, owner) -> owner in keep }.map { (extension, _) -> extension }.toSet()
        val disabled = names("downlevel/mesa.txt").filterNot { it in keep }
        val removed = disabled.filterNot { it in shared }
        environment("MESA_GL_VERSION_OVERRIDE", version)
        environment("MESA_GLSL_VERSION_OVERRIDE", version.replace(".", "") + "0")
        environment("MESA_EXTENSION_OVERRIDE", removed.joinToString(" ") { "-$it" })
        systemProperty("crystalgraphics.gl.disableExtensions", disabled.joinToString(","))
        systemProperty("crystalgraphics.harness.gl", version)
        systemProperty("crystalgraphics.harness.downlevel", name)
        systemProperty("crystalgraphics.harness.downlevel.keep", keep.joinToString(","))
        if (System.getProperty("os.name").lowercase().contains("win")) {
            dependsOn(fetchMesa)
            val bin = mesaDir.resolve("x64")
            // Loaded by path before GLFW starts, so GLFW's and LWJGL's own load of opengl32 find Mesa's; its
            // libgallium_wgl.dll is found on PATH.
            systemProperty("crystalgraphics.harness.opengl32", bin.resolve("opengl32.dll").absolutePath)
            systemProperty("org.lwjgl.opengl.libname", bin.resolve("opengl32.dll").absolutePath)
            environment("PATH", bin.absolutePath + File.pathSeparator + System.getenv("PATH"))
            environment("GALLIUM_DRIVER", "llvmpipe")
        } else {
            environment("LIBGL_ALWAYS_SOFTWARE", "1")
        }
    }

    // Extra JVM flags for one-off diagnostics — e.g. GC logging while chasing a frame spike:
    //   ./gradlew :gl-debug-harness:runHarness --args="--mode=text-3d" -Pharness.jvmArgs="-Xlog:gc"
    (project.findProperty("harness.jvmArgs") as String?)?.let { extra ->
        jvmArgs(extra.split(" ").filter { it.isNotBlank() })
    }
}
