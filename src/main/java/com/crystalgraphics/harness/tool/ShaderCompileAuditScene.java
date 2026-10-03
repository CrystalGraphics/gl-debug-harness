package com.crystalgraphics.harness.tool;

import com.crystalgraphics.api.shader.CgShaderPreprocessor;
import com.crystalgraphics.api.shader.CgShaderStages;
import com.crystalgraphics.api.shader.CgShaderProgram;
import com.crystalgraphics.compute.emit.CgKernelEmitter;
import com.crystalgraphics.compute.emit.CgKernelTarget;
import com.crystalgraphics.compute.lower.CgLoweredEmitter;
import com.crystalgraphics.compute.lower.CgLoweredTarget;
import com.crystalgraphics.compute.lower.CgLowering;
import com.crystalgraphics.compute.parse.CgComputeParser;
import com.crystalgraphics.compute.source.CgBufferDecl;
import com.crystalgraphics.compute.source.CgComputeSource;
import com.crystalgraphics.compute.source.CgKernelDecl;
import com.crystalgraphics.gl.material.CgMaterialShader;
import com.crystalgraphics.gl.material.CgMaterialShaderRegistry;
import com.crystalgraphics.gl.material.parse.CgMaterialShaderCompiler;
import com.crystalgraphics.gl.material.parse.CgParsedPass;
import com.crystalgraphics.gl.material.parse.CgParsedShader;
import com.crystalgraphics.gl.material.parse.CgShaderParser;
import com.crystalgraphics.api.shader.CgShader;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.device.CgDeviceInfo;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.tracked.CgTrackedGLBackend;
import com.crystalgraphics.platform.gl.tracked.tracker.CgTracker;
import com.crystalgraphics.util.io.CgIO;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.HarnessExtension;
import com.crystalgraphics.harness.HarnessExtensions;
import com.crystalgraphics.harness.HarnessSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Compiles every shipped {@code .shader} and {@code .compute} — and every keyword variant of each — against the
 * real driver, and writes a single report instead of crashing on the first failure. A kernel compiles with the
 * context's subgroup operations and again with them emulated, where it has them.
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
 *   <li><b>Compiles every keyword combination.</b> Each is a separately compiled program, so a
 *       variant can fail while the base succeeds — which is exactly the case that hid behind the
 *       original crash.</li>
 *   <li><b>On a device ({@code --device=tracked|vulkan}), builds every variant's pipeline</b> in both
 *       clip conventions. Linking there stops at SPIR-V; a Vulkan driver compiles when a pipeline is
 *       built, so without this its compiler would see nothing. Validation errors are counted per
 *       variant.</li>
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

    /** Namespaces whose {@code assets/<ns>/shaders/*.shader} get audited: ours, then each extension's. */
    private static List<String> namespaces() {
        List<String> out = new ArrayList<>(List.of("crystalgraphics"));
        for (HarnessExtension extension : HarnessExtensions.all()) {
            out.addAll(extension.shaderNamespaces());
        }
        return out;
    }

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
    private int pipelines;

    @Override
    public void init(HarnessContext ctx) {
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        File outFile = new File(ctx.getOutputDir(), "report.txt");
        List<String> body = new ArrayList<>();

        LogCapture capture = LogCapture.install();
        // Empty, so every input a pipeline is built for reads (0, 0, 0, 1) and none needs a buffer.
        int vao = PlatformServiceHarness.tracked() == null ? 0 : CgGL.glGenVertexArrays();
        int previousVao = CgGL.glGetInteger(CgGL.GL_VERTEX_ARRAY_BINDING);
        if (vao != 0) CgGL.glBindVertexArray(vao);
        try {
            for (String namespace : namespaces()) {
                for (String path : shippedShaderPaths(namespace)) {
                    if (DOCUMENTATION_ONLY.contains(path)) {
                        body.add("SKIP  " + path + "  (documentation, not a compilable shader)");
                        continue;
                    }
                    auditOne(path, capture, body);
                }
                auditKernels(namespace, capture, body);
            }
        } catch (Throwable t) {
            body.add("!! The audit itself failed: " + t);
            for (StackTraceElement e : t.getStackTrace()) body.add("      at " + e);
        } finally {
            capture.remove();
            if (vao != 0) {
                CgGL.glBindVertexArray(previousVao);
                CgGL.glDeleteVertexArrays(vao);
            }
        }

        try (PrintWriter pw = new PrintWriter(new FileWriter(outFile))) {
            writeHeader(pw, ctx);
            pw.println();
            pw.println("=== Results ===");
            for (String line : body) pw.println(line);
            pw.println();
            pw.println("=== Summary ===");
            pw.println(checks + " compile checks, " + pipelines + " pipelines built, " + failures
                    + " failure(s), " + warnings + " non-fatal warning(s)");
            pw.println(failures == 0
                    ? "All shipped shaders, kernels and keyword variants compiled on this driver."
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

        // Driver compile: every keyword combination, the empty one being the base variant.
        // Each is a separately compiled program, so they are separate checks.
        List<String> features = parsed.featureNames();
        List<Set<String>> keywordSets = new ArrayList<>();
        for (int mask = 0; mask < 1 << features.size(); mask++) {
            Set<String> set = new LinkedHashSet<>();
            for (int i = 0; i < features.size(); i++) if ((mask & 1 << i) != 0) set.add(features.get(i));
            keywordSets.add(set);
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
        for (CgParsedPass pass : parsed.passes()) {
            buildPipelines(asset.getOrCompile(pass.name(), Collections.emptySet()),
                    "pass '" + pass.name() + "' base variant", body);
        }
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
                        buildPipelines(asset.getOrCompile(pass.name(), keywords),
                                "pass '" + pass.name() + "' keywords=" + keywords, body);
                    }
                } catch (Throwable t) {
                    failures++;
                    body.add(String.format(label, "FAIL") + " — threw: " + t);
                    appendCaptured(capture, body);
                }
            }
        }
    }

    /** Every kernel of every {@code .compute} under {@code shaders/}: on a device, linking builds its pipeline. */
    private void auditKernels(String namespace, LogCapture capture, List<String> body) {
        List<String> paths = CgIO.list(namespace, "shaders", ".compute");
        if (paths.isEmpty()) return;
        if (!CgCapabilities.detect().compute()) {
            body.add("");
            body.add("SKIP  " + namespace + " kernels  (this context runs no compute shaders)");
            return;
        }
        CgKernelTarget current = CgKernelTarget.current();
        List<CgKernelTarget> targets = current.nativeSubgroups()
                ? List.of(current, current.withSubgroups(0)) : List.of(current);
        for (String path : paths) {
            body.add("");
            body.add("── " + path);
            CgComputeSource source;
            try {
                source = CgComputeParser.parse(CgIO.loadSource(path), path);
            } catch (Throwable t) {
                fail(body, "  PARSE   FAIL — " + t.getMessage());
                continue;
            }
            body.add("  PARSE   ok   (" + source.kernels().size() + " kernel(s), features=" + source.features()
                    + ", buffers=" + source.engineBuffers() + ")");
            for (CgKernelDecl kernel : source.kernels()) {
                for (Set<String> keywords : keywordSets(source.features())) {
                    for (CgKernelTarget target : kernel.subgroups().isEmpty() ? List.of(current) : targets) {
                        checks++;
                        capture.clear();
                        String label = "  COMPILE %s kernel " + kernel.name() + " (" + kernel.shape().name().toLowerCase()
                                + ") keywords=" + keywords + (target == current ? "" : " subgroups emulated");
                        int validationBefore = PlatformServiceHarness.validationErrors();
                        try {
                            String glsl = new CgShaderPreprocessor().process(
                                    CgKernelEmitter.emit(source, kernel, keywords, target), path);
                            CgShaderProgram.compileCompute(glsl).delete();
                            int raised = PlatformServiceHarness.validationErrors() - validationBefore;
                            if (raised > 0) {
                                failures++;
                                body.add(String.format(label, "FAIL") + " — " + raised
                                        + " validation error(s), in the console as [vulkan] ERROR");
                            } else {
                                body.add(String.format(label, "ok  "));
                            }
                        } catch (Throwable t) {
                            failures++;
                            body.add(String.format(label, "FAIL") + " — " + t.getMessage());
                            appendCaptured(capture, body);
                        }
                    }
                    if (PlatformServiceHarness.deviceInfo() == null) auditLowered(source, kernel, keywords, path, capture, body);
                }
            }
        }
    }

    /** Each pass a kernel lowers to, as a context below compute runs it: GL only, since a device has no capture. */
    private void auditLowered(CgComputeSource source, CgKernelDecl kernel, Set<String> keywords, String path,
                              LogCapture capture, List<String> body) {
        if (CgLowering.refusal(source, kernel) != null) return;
        CgLoweredTarget target = CgLoweredTarget.current();
        for (CgLowering.Pass pass : CgLowering.passes(source, kernel)) {
            checks++;
            capture.clear();
            String label = "  LOWER   %s kernel " + kernel.name() + " " + pass.kind().name().toLowerCase() + " pass of "
                    + (pass.image() != null ? pass.image().name()
                            : pass.buffers().stream().map(CgBufferDecl::name).collect(Collectors.joining(", ")))
                    + (pass.op() != null ? " (" + pass.op().name().toLowerCase() + ")" : "") + " keywords=" + keywords;
            try {
                CgLoweredEmitter.Stages stages = CgLoweredEmitter.emit(source, kernel, keywords, pass, target);
                CgShaderPreprocessor pre = new CgShaderPreprocessor();
                CgShaderProgram.compileCapture(pre.process(stages.vertex(), path),
                        stages.geometry() == null ? null : pre.process(stages.geometry(), path),
                        stages.fragment() == null ? null : pre.process(stages.fragment(), path),
                        stages.varyings()).delete();
                body.add(String.format(label, "ok  "));
            } catch (Throwable t) {
                failures++;
                body.add(String.format(label, "FAIL") + " — " + t.getMessage());
                appendCaptured(capture, body);
            }
        }
    }

    private static List<Set<String>> keywordSets(List<String> features) {
        List<Set<String>> sets = new ArrayList<>();
        for (int mask = 0; mask < 1 << features.size(); mask++) {
            Set<String> set = new LinkedHashSet<>();
            for (int i = 0; i < features.size(); i++) if ((mask & 1 << i) != 0) set.add(features.get(i));
            sets.add(set);
        }
        return sets;
    }

    /**
     * On a device, the pipeline a triangle draw would bind with this program, in GL's clip convention and in
     * zero-to-one: the Vulkan driver's own compile. A validation error raised meanwhile fails the variant; its
     * text is in the console, as {@code [vulkan] ERROR}. Nothing on GL, where the link was the driver's compile.
     */
    private void buildPipelines(CgShader shader, String what, List<String> body) {
        CgTrackedGLBackend tracked = PlatformServiceHarness.tracked();
        if (tracked == null || shader == null) return;
        CgTracker tracker = tracked.tracker();
        boolean clip = tracker.zeroToOneClip();
        int validationBefore = PlatformServiceHarness.validationErrors();
        try {
            shader.bind();
            for (boolean zeroToOne : new boolean[] {false, true}) {
                tracker.setZeroToOneClip(zeroToOne);
                tracked.buildPipeline(CgGL.GL_TRIANGLES);
                pipelines++;
            }
        } catch (Throwable t) {
            failures++;
            body.add("  PIPELINE FAIL " + what + " — threw: " + t);
            return;
        } finally {
            tracker.setZeroToOneClip(clip);
            shader.unbind();
        }
        int raised = PlatformServiceHarness.validationErrors() - validationBefore;
        if (raised > 0) {
            failures++;
            body.add("  PIPELINE FAIL " + what + " — " + raised + " validation error(s), in the console as [vulkan] ERROR");
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
            String vertex = CgShaderStages.reachable(stripComments(
                    new CgShaderPreprocessor().process(cs.vertexSource(), path)), CgShaderStages.Stage.VERTEX);
            List<String> found = new ArrayList<>();
            for (String builtin : FRAGMENT_ONLY) {
                if (vertex.matches("(?s).*\\b" + builtin + "\\b.*")) found.add(builtin);
            }
            if (!found.isEmpty()) {
                fail(body, "  STATIC  FAIL pass '" + pass.name() + "' — fragment-only builtin(s) "
                        + found + " reach the VERTEX stage. Guard them with"
                        + " #if !defined(CG_VERTEX_STAGE) && !defined(CG_COMPUTE_STAGE) in the lib that defines them.");
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
        CgDeviceInfo device = PlatformServiceHarness.deviceInfo();
        if (device != null) {
            pw.println("-- Device --");
            pw.println("Name:   " + device.name());
            pw.println("Vendor: " + device.vendor());
            pw.println("Driver: " + device.driver());
        } else {
            pw.println("-- GL Context --");
            pw.println("GL_VERSION:  " + ctx.getGlVersion());
            pw.println("GL_VENDOR:   " + ctx.getGlVendor());
            pw.println("GL_RENDERER: " + ctx.getGlRenderer());
        }
        pw.println();
        pw.println("-- CgCapabilities --");
        try {
            CgCapabilities caps = CgCapabilities.detect();
            pw.println("ShaderBufferPath:  " + caps.shaderBufferPath()
                    + "   (decides #version 430 vs 330)");
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
     * Every {@code .shader} under {@code assets/<namespace>/shaders/}, subdirectories included. Handles both a directory on
     * disk (IDE / exploded run) and a jar entry (the normal harness run), because which one applies
     * depends on how the tester launched it and neither can be assumed.
     */
    private static List<String> shippedShaderPaths(String namespace) throws Exception {
        List<String> out = new ArrayList<>();
        URL dir = ShaderCompileAuditScene.class.getResource("/assets/" + namespace + "/shaders/");
        if (dir == null) return out;

        if ("file".equals(dir.getProtocol())) {
            Path root = Paths.get(dir.toURI());
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(f -> f.toString().endsWith(".shader"))
                        .forEach(f -> out.add(namespace + ":shaders/" + root.relativize(f).toString().replace('\\', '/')));
            }
        } else if ("jar".equals(dir.getProtocol())) {
            String spec = dir.getPath();
            String jarPath = spec.substring(5, spec.indexOf("!"));
            try (JarFile jar = new JarFile(new File(new URL("file:" + jarPath).toURI()))) {
                String prefix = "assets/" + namespace + "/shaders/";
                for (Enumeration<JarEntry> e = jar.entries(); e.hasMoreElements(); ) {
                    String n = e.nextElement().getName();
                    if (n.startsWith(prefix) && n.endsWith(".shader")) {
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
