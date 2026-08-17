package io.github.somehussar.crystalgraphics.harness.scene.ui;

import com.crystalgraphics.gl.material.parse.CgShaderParseException;
import com.crystalgraphics.gl.material.parse.CgShaderParser;
import com.crystalgui.text.TextPoint;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.diagnostic.DiagnosticSeverity;
import com.crystalgui.text.diagnostic.SourceChecker;

import java.util.List;

/**
 * <b>M11 §24.6</b> — the shader compiler's own errors, as squiggles.
 *
 * <h3>Where this lives, and why not in {@code core/}</h3>
 *
 * <p>It names {@code CgShaderParser}, which is CrystalGraphics <b>core</b> — and {@code core/} may reach
 * that only inside a paint-method body, which is the rule {@code headlessTest} exists to enforce. So the
 * seam is a {@link SourceChecker}: {@code core/} declares an interface taking a {@code String} and
 * answering {@code Diagnostic}s, and whoever actually has a compiler implements it. A dedicated server
 * simply has no implementation, which is the same three-tier absence every language capability uses.</p>
 *
 * <h3>One error, and that is the parser rather than this adapter</h3>
 *
 * <p>{@code CgShaderParser} throws on the first violation — it has no collecting mode, and
 * {@code --mode=shader-compile-audit} collects across <em>files</em> rather than within one, which the
 * plan conflated. So a file with three problems reports the first, the author fixes it, and the second
 * appears. That is how every single-pass parser behaves and it is honest; what would not be honest is
 * reporting three positions from one exception.</p>
 *
 * <h3>What it cannot see</h3>
 *
 * <p><b>Real GLSL errors.</b> Those come from the driver, which means a GL context and the GL thread, and
 * this runs on a background check. What is checked here is the {@code .shader} <em>format</em> — the
 * structure, the pragmas, the render state, the property table — which is the half that is wrong most
 * often while a file is being written and the only half a text editor can answer for at all.</p>
 */
public final class ShaderSourceChecker implements SourceChecker {

    /** What these problems are filed under. Distinct from any language engine's, so both can speak. */
    public static final String OWNER = "shader";

    @Override
    public List<Diagnostic> check(String name, String source) {
        if (source == null || source.isBlank()) return List.of();
        try {
            CgShaderParser.parse(source, name);
            return List.of();
        } catch (CgShaderParseException refused) {
            return List.of(diagnosticFor(refused, source));
        } catch (RuntimeException unexpected) {
            // A PARSER FAULT IS NOT A CRASH ON A KEYSTROKE. This runs on every edit of every shader file,
            // so anything the parser throws that is not its own exception type is reported as a
            // whole-file problem rather than allowed to take the check job down.
            return List.of(wholeFile(String.valueOf(unexpected.getMessage())));
        }
    }

    /**
     * The exception as a mark on its line.
     *
     * <p>To the <b>end of the line</b>, which is {@code Diagnostic.onRow}'s convention: the parser knows
     * which construct it choked on and rarely which character, and a whole statement underlined is the
     * honest width for that. When it does give a column the mark starts there.</p>
     */
    private static Diagnostic diagnosticFor(CgShaderParseException refused, String source) {
        String message = clean(refused.getMessage());
        if (!refused.hasPosition()) return wholeFile(message);
        int row = refused.line() - 1;
        TextPoint start = new TextPoint(row, Math.max(0, refused.column() - 1));
        TextPoint end = new TextPoint(row, Integer.MAX_VALUE);
        return new Diagnostic(start, end, DiagnosticSeverity.ERROR, message, OWNER, null);
    }

    /**
     * A problem nothing could place, put on the first line.
     *
     * <p>The one case where line 1 is right rather than a guess: "no Pass blocks found" and "source is
     * empty" are statements about the file, not about a line in it, and the first row is where a reader
     * looks for a file-level complaint.</p>
     */
    private static Diagnostic wholeFile(String message) {
        return new Diagnostic(new TextPoint(0, 0), new TextPoint(0, Integer.MAX_VALUE),
                DiagnosticSeverity.ERROR, clean(message), OWNER, null);
    }

    /**
     * Drops the {@code [path]} prefix the parser puts on every message.
     *
     * <p>It is there because the parser's audience is a build log, where a message with no file is
     * useless. In an editor the file is the one on screen, so repeating it in every squiggle spends the
     * width that would otherwise show what is actually wrong.</p>
     */
    private static String clean(String message) {
        if (message == null) return "shader source could not be parsed";
        String text = message.startsWith("[") && message.indexOf(']') > 0
                ? message.substring(message.indexOf(']') + 1).trim() : message.trim();
        return text.isEmpty() ? "shader source could not be parsed" : text;
    }
}
