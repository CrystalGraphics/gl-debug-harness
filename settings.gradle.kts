import java.io.File
import java.util.Properties

// The harness as a build of its own: CrystalGUI and CrystalGraphics from source, where local.properties says
// (README.md). Inside CrystalGUI's build the harness is one of its projects and this file is not read.
rootProject.name = "gl-debug-harness"

val local = Properties().apply {
    val file = file("local.properties")
    if (file.isFile) file.reader().use { load(it) }
}

fun dir(path: String): File = File(path).let { if (it.isAbsolute) it else settingsDir.resolve(path) }.canonicalFile

// Default: the harness checked out as CrystalGUI's submodule.
val crystalGui = dir(local.getProperty("crystalGuiDir") ?: "..")
if (!crystalGui.resolve("settings.gradle.kts").isFile) {
    throw GradleException("No CrystalGUI build at $crystalGui: set crystalGuiDir in local.properties (README.md)")
}
// Default: CrystalGUI's own submodule. CrystalGUI includes CrystalGraphics itself, so the directory goes to it, by
// the one channel that reaches an included build's settings.
local.getProperty("crystalGraphicsDir")?.let { path ->
    val crystalGraphics = dir(path)
    if (!crystalGraphics.resolve("settings.gradle.kts").isFile) {
        throw GradleException("No CrystalGraphics build at $crystalGraphics: fix crystalGraphicsDir in local.properties")
    }
    System.setProperty("crystalgraphics.dir", crystalGraphics.path)
    // A daemon outlives the build: the next one, CrystalGUI's own, must not inherit it.
    gradle.projectsLoaded { System.clearProperty("crystalgraphics.dir") }
}

includeBuild(crystalGui) {
    name = "CrystalGUI"
    dependencySubstitution { substitute(module("com.crystalgui:core")).using(project(":core")) }
}

// What the build script reads from CrystalGUI's checkout: the plan's kits, when it is there.
gradle.rootProject { extensions.extraProperties["crystalGuiDir"] = crystalGui.path }
