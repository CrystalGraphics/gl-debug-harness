package io.github.somehussar.crystalgraphics.harness.scene.ui;

import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.notify.Notifications;
import com.crystalgui.language.java.JavaLanguage;
import com.crystalgui.language.run.ScriptCommands;
import com.crystalgui.language.run.ScriptHost;
import com.crystalgui.ui.elements.editor.TextEditor;
import com.crystalgui.ui.elements.workbench.Workbench;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Run and Stop for whatever `.java` file is in front — the harness's half of M7a.
 *
 * <h3>The source is read at INVOKE time, never captured</h3>
 *
 * <p>"The current script" is a question about the application, not about the engine, and the answer
 * changes with every tab switch and every keystroke. {@code ScriptCommands} takes suppliers for exactly
 * this reason: capturing a compiled script at registration would bind Run to whichever file happened to
 * be open at startup, and it would go on running that one forever while appearing to run the visible
 * file — the worst possible version of this, since the effect looks plausible.</p>
 *
 * <p>It is the <b>editor's buffer</b> rather than the file on disk, so Run executes what is on screen
 * including unsaved edits. That is what an author expects from a Run button and it is what every IDE
 * with a scratch file does; requiring a save first would make the loop slower for no gain in honesty.</p>
 *
 * <h3>Compiled fresh on every Run, and that is not wasteful</h3>
 *
 * <p>The compiled-script cache is keyed on the source, so an unchanged file is a cache hit and no
 * compile happens at all. A changed one has to be recompiled by definition. Asking every time is
 * therefore both correct and cheap, and it removes the one bug this shape can have: a stale compile
 * running after an edit.</p>
 */
final class HarnessScriptRunner {

    private final ScriptHost host;

    private HarnessScriptRunner(ScriptHost host) {
        this.host = host;
    }

    /**
     * Installs the commands, or returns null when no engine opened.
     *
     * <p>Null rather than a no-op host: registering a Run command that cannot run anything gives a menu
     * row and an accelerator that do nothing, which is worse than their absence — a dead affordance
     * teaches people the feature is broken rather than unavailable.</p>
     */
    static HarnessScriptRunner install(CommandRegistry registry, Workbench workbench, Path cacheRoot) {
        if (!JavaLanguage.isAvailable()) return null;

        ScriptHost host = new ScriptHost(JavaLanguage.engine(),
                com.crystalgui.language.run.ScriptCache.directory(cacheRoot),
                com.crystalgui.language.map.MappingSet.IDENTITY, "identity",
                HarnessScriptRunner.class.getClassLoader(), null);

        HarnessScriptRunner runner = new HarnessScriptRunner(host);
        ScriptCommands.register(registry, host,
                () -> runner.compileActive(workbench),
                Map::of,
                HarnessScriptRunner::report);
        return runner;
    }

    /** The file in front, compiled — or null, with a reason said out loud. */
    private ScriptHost.Compiled compileActive(Workbench workbench) {
        TextEditor editor = workbench.activeEditor();
        if (editor == null) {
            Notifications.warning("Run: no text file is open");
            return null;
        }
        String name = workbench.activeFilePath() == null ? "Script"
                : workbench.activeFilePath().name();
        if (!name.endsWith(".java")) {
            Notifications.warning("Run: " + name + " is not a Java file");
            return null;
        }

        String className = name.substring(0, name.length() - ".java".length());
        ScriptHost.Compiled compiled =
                host.compileSource(className, editor.buffer().document().toString(), Map.of());
        if (!compiled.successful()) {
            // THE DIAGNOSTICS ALREADY SAY WHAT IS WRONG, in the editor, on the line. This says only
            // that the run did not start, because a notification repeating a compiler message is a
            // second report of the same thing in a worse place.
            Notifications.error("Run: " + name + " has compile errors");
            return null;
        }
        return compiled;
    }

    private static void report(Throwable failure) {
        Notifications.error("Script failed: " + failure);
        failure.printStackTrace();
    }

    void close() throws IOException {
        host.close();
    }
}
