package io.github.somehussar.crystalgraphics.harness.tool;

import com.crystalgraphics.api.shader.CgShaderPreprocessor;
import com.crystalgraphics.gl.material.CgMaterialShader;
import com.crystalgraphics.gl.material.CgMaterialShaderRegistry;
import com.crystalgraphics.gl.material.parse.CgMaterialShaderCompiler;
import com.crystalgraphics.gl.material.parse.CgParsedPass;
import com.crystalgraphics.gl.material.parse.CgParsedShader;
import com.crystalgraphics.gl.material.parse.CgShaderParser;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.util.io.CgIO;
import io.github.somehussar.crystalgraphics.harness.FrameInfo;
import io.github.somehussar.crystalgraphics.harness.HarnessSceneLifecycle;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Logger;

/**
 * Compiles every shipped {@code .shader} — and every keyword variant of each — against the real
 * driver, and writes a single report instead of crashing on the first failure.
 *
 * <p><b>Why this exists.</b> A tester on AMD could not start {@code cgui-gallery} at all: the very
 * first rounded rect aborted the run, so nothing downstream of it had <em>ever</em> been compiled on
 * that hardware. Finding the next problem meant another crash, another log, another round trip. This
 * scene turns that loop into one command and one file.</p>
 *
 * <p>It complements the {@code ShippedShaderStagePurityTest} unit tests rather than repeating them.
 * Those can only catch identifiers we already knew were fragment-only; a real driver catches
 * everything — implicit conversions, extension handling, resource limits, profile violations. That
 * is the whole point of running it on the machine that disagrees with ours.</p>
 *
 * <h3>Deliberate design choices</h3>
 * <ul>
 *   <li><b>Collects failures, never throws.</b> One report beats one crash.</li>
 *   <li><b>Compiles keyword variants individually and all-on.</b> Each keyword combination is a
 *       separately compiled program, so a variant can fail while the base succeeds — which is
 *       exactly the case that hid behind the original crash.</li>
 *   <li><b>Uses the engine's own compile path</b> ({@link CgMaterialShader}), not a reimplementation
 *       of it, so a green report means the engine works and not merely that the harness does.</li>
 *   <li><b>No system properties, no flags.</b> One copy-pasteable command; making a volunteer tester
 *       fight Gradle {@code -D} arguments is how you get no data back.</li>
 * </ul>
 *
 * <p>Registered as diagnostic mode {@code shader-compile-audit}. Writes
 * {@code harness-output/shader-compile-audit/report.txt}.</p>
 */
public final class ShaderCompileAuditScene implements HarnessSceneLifecycle {

    private static final Logger LOGGER = Logger.getLogger(ShaderCompileAuditScene.class.getName());

    /** Namespaces whose {@code assets/<ns>/shaders/*.shader} get audited. */
    private static final String[] NAMESPACES = {"crystalgui", "crystalgraphics"};

    /**
     * Documentation, not shaders — annotated reference files with commented-out {@code Pass} blocks
     * that no code loads and the parser cannot read.
     */
    private static final Set<String> DOCUMENTATION_ONLY = new LinkedHashSet<>(
            Collections.singletonList("crystalgraphics:shaders/example.shader"));

    /** Same list the stage-purity unit tests use — the static and driver checks must agree. */
    private static final String[] FRAGMENT_ONLY = {
            "fwidth", "fwidthFine", "fwidthCoarse",
            "dFdx", "dFdy", "dFdxFine", "dFdyFine", "dFdxCoarse", "dFdyCoarse",
            "discard",
            "gl_FragCoord", "gl_FrontFacing", "gl_PointCoord", "gl_FragDepth",
            "interpolateAtCentroid", "interpolateAtSample", "interpolateAtOffset",
            "gl_SampleID", "gl_SamplePosition", "gl_SampleMask", "gl_SampleMaskIn",
    };

    private int failures;
    private int warnings;
    private int checks;

    @Override
    public void init(HarnessContext ctx) {
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        File outFile = new File(ctx.getOutputDir(), "report.txt");
        List<String> body = new ArrayList<>();

        LogCapture capture = LogCapture.install();
        try {
            for (String namespace : NAMESPACES) {
                for (String path : shippedShaderPaths(namespace)) {
                    if (DOCUMENTATION_ONLY.contains(path)) {
                        body.add("SKIP  " + path + "  (documentation, not a compilable shader)");
                        continue;
                    }
                    auditOne(path, capture, body);
                }
            }
        } catch (Throwable t) {
            body.add("!! The audit itself failed: " + t);
            for (StackTraceElement e : t.getStackTrace()) body.add("      at " + e);
        } finally {
            capture.remove();
        }

        try (PrintWriter pw = new PrintWriter(new FileWriter(outFile))) {
            writeHeader(pw, ctx);
            pw.println();
            pw.println("=== Results ===");
            for (String line : body) pw.println(line);
            pw.println();
            pw.println("=== Summary ===");
            pw.println(checks + " compile checks, " + failures + " failure(s), "
                    + warnings + " non-fatal warning(s)");
            pw.println(failures == 0
                    ? "All shipped shaders and keyword variants compiled on this driver."
                    : "SEND THIS FILE BACK — the failures above are what we cannot reproduce here.");
            if (warnings > 0) {
                pw.println("Warnings are auto-generated passes the engine failed to build and carried"
                        + " on without. Not fatal, but worth sending back too.");
            }
        } catch (Exception e) {
            LOGGER.severe("[ShaderCompileAudit] Could not write report: " + e);
            return;
        }

        LOGGER.info("[ShaderCompileAudit] " + checks + " checks, " + failures
                + " failure(s) — wrote " + outFile.getAbsolutePath());
    }

    @Override
    public void dispose() {
    }

    // ── Per-shader audit ──────────────────────────────────────────────────────

    private void auditOne(String path, LogCapture capture, List<String> body) {
        body.add("");
        body.add("── " + path);

        CgParsedShader parsed;
        try {
            String source = CgIO.loadSource(path);
            if (source == null || source.isEmpty()) {
                fail(body, "  PARSE   FAIL — could not load source");
                return;
            }
            parsed = CgShaderParser.parse(source, path);
        } catch (Throwable t) {
            fail(body, "  PARSE   FAIL — " + t.getMessage());
            return;
        }
        body.add("  PARSE   ok   (" + parsed.passes().size() + " pass(es), features="
                + parsed.featureNames() + ", buffers=" + parsed.engineBuffers() + ")");

        staticChecks(path, parsed, body);

        // Driver compile: base variant, then each keyword alone, then all keywords together.
        // Each set is a separately compiled program, so they are separate checks.
        List<Set<String>> keywordSets = new ArrayList<>();
        keywordSets.add(Collections.<String>emptySet());
        for (String feature : parsed.featureNames()) {
            keywordSets.add(Collections.singleton(feature));
        }
        if (parsed.featureNames().size() > 1) {
            keywordSets.add(new LinkedHashSet<>(parsed.featureNames()));
        }

        CgMaterialShader asset;
        try {
            asset = CgMaterialShaderRegistry.get().getOrCreate(path);
            capture.clear();
            asset.recompile();
        } catch (Throwable t) {
            fail(body, "  COMPILE FAIL — base variant threw: " + t);
            appendCaptured(capture, body);
            return;
        }
        checks++;
        if (asset.hasCompileFailed() || asset.getLastParsed() == null) {
            failures++;
            body.add("  COMPILE FAIL — base variant");
            appendCaptured(capture, body);
            return;                       // variants cannot compile if the base did not
        }
        body.add("  COMPILE ok   base variant");
        // Auto-generated ShadowCaster/Depth passes fail non-fatally: the engine logs and carries on.
        // Report them anyway — a run that printed a GLSL error to the console while the report said
        // "0 failures" is exactly the kind of thing that gets a real problem dismissed.
        List<String> nonFatal = capture.drain();
        if (!nonFatal.isEmpty()) {
            warnings++;
            body.add("  WARN         base compile logged non-fatal error(s):");
            appendLines(nonFatal, body);
        }

        for (Set<String> keywords : keywordSets) {
            if (keywords.isEmpty()) continue;   // already covered by the base compile
            for (CgParsedPass pass : parsed.passes()) {
                checks++;
                capture.clear();
                String label = "  COMPILE %s pass '" + pass.name() + "' keywords=" + keywords;
                try {
                    if (asset.getOrCompile(pass.name(), keywords) == null) {
                        failures++;
                        body.add(String.format(label, "FAIL"));
                        appendCaptured(capture, body);
                    } else {
                        body.add(String.format(label, "ok  "));
                    }
                } catch (Throwable t) {
                    failures++;
                    body.add(String.format(label, "FAIL") + " — threw: " + t);
                    appendCaptured(capture, body);
                }
            }
        }
    }

    /**
     * Two cheap checks the driver will not make for us: that no fragment-only builtin reached the
     * vertex stage, and that both stages agree on {@code #version}. Both were live risks in the AMD
     * report — the first is the bug itself, the second would make a link failure look like a compile
     * failure.
     */
    private void staticChecks(String path, CgParsedShader parsed, List<String> body) {
        for (CgParsedPass pass : parsed.passes()) {
            CgMaterialShaderCompiler.CompiledSource cs;
            try {
                cs = CgMaterialShaderCompiler.compile(parsed, pass, Collections.emptyList(), null,
                        CgMaterialShaderCompiler.CompileConfig.DEFAULT);
            } catch (Throwable t) {
                body.add("  STATIC  skipped for pass '" + pass.name() + "' — " + t.getMessage());
                continue;
            }

            // Resolve the stage conditionals first. CgShaderPreprocessor expands includes but leaves
            // #ifdef to the driver, so scanning its raw output would flag every correctly guarded
            // builtin — a line that fires on every run is noise, and noise in a report a volunteer
            // reads is worse than no line at all.
            String vertex = vertexStageReachableText(stripComments(
                    new CgShaderPreprocessor().process(cs.vertexSource(), path)));
            List<String> found = new ArrayList<>();
            for (String builtin : FRAGMENT_ONLY) {
                if (vertex.matches("(?s).*\\b" + builtin + "\\b.*")) found.add(builtin);
            }
            if (!found.isEmpty()) {
                fail(body, "  STATIC  FAIL pass '" + pass.name() + "' — fragment-only builtin(s) "
                        + found + " reach the VERTEX stage. Guard them with #ifndef CG_VERTEX_STAGE"
                        + " in the lib that defines them.");
            }

            String vv = firstLine(cs.vertexSource());
            String fv = firstLine(cs.fragmentSource());
            if (!vv.equals(fv)) {
                fail(body, "  STATIC  FAIL pass '" + pass.name() + "' — #version mismatch: vertex "
                        + vv + " vs fragment " + fv);
            }
        }
    }

    private void fail(List<String> body, String line) {
        failures++;
        checks++;
        body.add(line);
    }

    /**
     * Blanks out comments before scanning. Not optional: the guard in {@code sdf.glsl} is documented
     * with a comment that names {@code fwidth} and quotes the AMD error verbatim, so a scan of raw
     * text reports the very code that fixed the bug.
     */
    private static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '/') {
                while (i < src.length() && src.charAt(i) != '\n') i++;
                out.append('\n');
            } else if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < src.length() && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) {
                    if (src.charAt(i) == '\n') out.append('\n');
                    i++;
                }
                i++; // land on '/', the loop's i++ steps past it
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Strips text the driver's preprocessor would discard for the vertex stage, resolving only
     * {@code CG_VERTEX_STAGE} / {@code CG_FRAGMENT_STAGE}. Every other conditional keeps both
     * branches, so an inactive keyword cannot hide a banned builtin from the scan.
     *
     * <p>Mirrors {@code ShippedShaderStagePurityTest.stripInactiveStageBlocks} in both projects —
     * this check and those tests must agree, or a green report and a red build mean nothing.</p>
     */
    private static String vertexStageReachableText(String src) {
        StringBuilder out = new StringBuilder(src.length());
        // frame[0] = resolved stage conditional, frame[1] = this branch is emitting
        java.util.Deque<boolean[]> stack = new java.util.ArrayDeque<>();

        for (String line : src.split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("#ifdef ") || t.startsWith("#ifndef ")) {
                boolean negated = t.startsWith("#ifndef ");
                String name = t.substring(negated ? 8 : 7).trim();
                Boolean defined = "CG_VERTEX_STAGE".equals(name) ? Boolean.TRUE
                        : "CG_FRAGMENT_STAGE".equals(name) ? Boolean.FALSE : null;
                stack.push(defined == null
                        ? new boolean[]{false, true}
                        : new boolean[]{true, negated != defined});
                continue;
            }
            if (t.startsWith("#if")) { stack.push(new boolean[]{false, true}); continue; }
            if (t.startsWith("#elif")) {
                if (!stack.isEmpty()) { stack.peek()[0] = false; stack.peek()[1] = true; }
                continue;
            }
            if (t.equals("#else")) {
                if (!stack.isEmpty()) {
                    boolean[] f = stack.peek();
                    f[1] = f[0] ? !f[1] : true;
                }
                continue;
            }
            if (t.startsWith("#endif")) { if (!stack.isEmpty()) stack.pop(); continue; }

            boolean emitting = true;
            for (boolean[] f : stack) {
                if (!f[1]) { emitting = false; break; }
            }
            if (emitting) out.append(line).append('\n');
        }
        return out.toString();
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        return (nl < 0 ? s : s.substring(0, nl)).trim();
    }

    private static void appendCaptured(LogCapture capture, List<String> body) {
        List<String> lines = capture.drain();
        if (lines.isEmpty()) {
            body.add("          (no error text captured — see the console output of this run)");
            return;
        }
        appendLines(lines, body);
    }

    private static void appendLines(List<String> lines, List<String> body) {
        for (String line : lines) {
            for (String part : line.split("\n")) {
                if (!part.trim().isEmpty()) body.add("          " + part.trim());
            }
        }
    }

    // ── Report header ─────────────────────────────────────────────────────────

    private static void writeHeader(PrintWriter pw, HarnessContext ctx) {
        pw.println("=== CrystalGraphics Shader Compile Audit ===");
        pw.println("Every shipped .shader and keyword variant, compiled on this machine's driver.");
        pw.println();
        pw.println("-- GL Context --");
        pw.println("GL_VERSION:  " + ctx.getGlVersion());
        pw.println("GL_VENDOR:   " + ctx.getGlVendor());
        pw.println("GL_RENDERER: " + ctx.getGlRenderer());
        pw.println();
        pw.println("-- CgCapabilities --");
        try {
            CgCapabilities caps = CgCapabilities.detect();
            pw.println("ShaderBufferPath:  " + caps.shaderBufferPath()
                    + "   (decides #version 430 vs 330)");
            pw.println("Core FBO:          " + caps.isCoreFbo());
            pw.println("Core Shaders:      " + caps.isCoreShaders());
            pw.println("VAO:               " + caps.isVaoSupported());
            pw.println("Max Texture Size:  " + caps.getMaxTextureSize());
            pw.println("Max Draw Buffers:  " + caps.getMaxDrawBuffers());
        } catch (Throwable t) {
            pw.println("ERROR: " + t);
        }
        pw.println();
        pw.println("-- Environment --");
        pw.println("java.version: " + System.getProperty("java.version"));
        pw.println("os.name:      " + System.getProperty("os.name"));
        pw.println("os.arch:      " + System.getProperty("os.arch"));
    }

    // ── Resource enumeration ──────────────────────────────────────────────────

    /**
     * Every {@code .shader} under {@code assets/<namespace>/shaders/}. Handles both a directory on
     * disk (IDE / exploded run) and a jar entry (the normal harness run), because which one applies
     * depends on how the tester launched it and neither can be assumed.
     */
    private static List<String> shippedShaderPaths(String namespace) throws Exception {
        List<String> out = new ArrayList<>();
        URL dir = ShaderCompileAuditScene.class.getResource("/assets/" + namespace + "/shaders/");
        if (dir == null) return out;

        if ("file".equals(dir.getProtocol())) {
            File[] files = new File(dir.toURI()).listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isFile() && f.getName().endsWith(".shader")) {
                        out.add(namespace + ":shaders/" + f.getName());
                    }
                }
            }
        } else if ("jar".equals(dir.getProtocol())) {
            String spec = dir.getPath();
            String jarPath = spec.substring(5, spec.indexOf("!"));
            try (JarFile jar = new JarFile(new File(new URL("file:" + jarPath).toURI()))) {
                String prefix = "assets/" + namespace + "/shaders/";
                for (Enumeration<JarEntry> e = jar.entries(); e.hasMoreElements(); ) {
                    String n = e.nextElement().getName();
                    if (n.startsWith(prefix) && n.endsWith(".shader")
                            && n.indexOf('/', prefix.length()) < 0) {
                        out.add(namespace + ":shaders/" + n.substring(prefix.length()));
                    }
                }
            }
        }
        Collections.sort(out);
        return out;
    }

    // ── Log capture ───────────────────────────────────────────────────────────

    /**
     * Siphons ERROR-level log output into the report.
     *
     * <p>The engine reports GLSL errors by logging them and returning null, so without this the
     * report would say only <i>which</i> shader failed and never <i>why</i> — leaving the tester to
     * hand-copy stack traces out of a console. Failing to attach is survivable: the report then says
     * so and points at the console.</p>
     */
    private static final class LogCapture extends AbstractAppender {

        private final List<String> lines = Collections.synchronizedList(new ArrayList<>());
        private boolean attached;

        private LogCapture() {
            super("CgShaderCompileAudit", null, null, true, Property.EMPTY_ARRAY);
        }

        static LogCapture install() {
            LogCapture c = new LogCapture();
            try {
                c.start();
                LoggerContext lc = (LoggerContext) LogManager.getContext(false);
                lc.getConfiguration().getRootLogger().addAppender(c, Level.ERROR, null);
                lc.updateLoggers();
                c.attached = true;
            } catch (Throwable t) {
                // Qualified: AbstractAppender inherits a LOGGER of its own, which shadows ours.
                ShaderCompileAuditScene.LOGGER.warning(
                        "[ShaderCompileAudit] Could not capture log output: " + t);
            }
            return c;
        }

        void remove() {
            if (!attached) return;
            try {
                LoggerContext lc = (LoggerContext) LogManager.getContext(false);
                lc.getConfiguration().getRootLogger().removeAppender(getName());
                lc.updateLoggers();
            } catch (Throwable ignored) {
                // Best effort — the process is about to exit anyway.
            }
            stop();
        }

        void clear() {
            lines.clear();
        }

        List<String> drain() {
            List<String> copy = new ArrayList<>(lines);
            lines.clear();
            return copy;
        }

        @Override
        public void append(LogEvent event) {
            if (event.getLevel().isMoreSpecificThan(Level.ERROR)) {
                lines.add(String.valueOf(event.getMessage().getFormattedMessage()));
            }
        }
    }
}
