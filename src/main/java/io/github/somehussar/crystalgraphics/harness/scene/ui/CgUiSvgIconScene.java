package io.github.somehussar.crystalgraphics.harness.scene.ui;

import com.crystalgraphics.api.render.CgRenderPipeline;
import com.crystalgraphics.gl.render.CgVectorRenderer;
import com.crystalgraphics.platform.input.CgSystemInput;

import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.render.texture.svg.SvgDocument;
import com.crystalgui.render.texture.svg.SvgPath;

import io.github.somehussar.crystalgraphics.harness.FrameInfo;
import io.github.somehussar.crystalgraphics.harness.InteractiveSceneLifecycle;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;

import java.util.List;

/**
 * A real SVG icon, parsed and drawn as vector strokes. <b>Scroll to scale.</b>
 *
 * <h3>What this is proving</h3>
 *
 * <p>{@code ICONS.md} step 0, and it answers the open question that document ends on: <em>can the
 * existing {@code ctx.curve()} path draw an icon directly, with no atlas and no bake at all?</em></p>
 *
 * <p>Nothing here touches a texture, a shader or a distance field. The geometry is flattened to points by
 * {@link SvgPath} and handed to {@code CgVectorRenderer} — strokes as straight segments, fills as
 * triangles. If this reads cleanly at every zoom then <b>icons are free</b>, and the MSDF pipeline
 * {@code ICONS.md} plans is not needed at all.</p>
 *
 * <p>The scale is the whole test. A raster icon degrades as you scroll in; a vector one is
 * resolution-independent by construction, and the difference is obvious within one turn of the wheel.</p>
 *
 * <h3>The icons, and why these ones</h3>
 *
 * <p>Five from Feather (MIT), verbatim, chosen to be <b>ordinary</b>: 24×24, {@code currentColor} strokes,
 * a spread of element kinds, and corners spelled with {@code a}. Arcs are the one command whose data is
 * not already in the form it is drawn from, and Feather, Lucide and Material all round with them.</p>
 *
 * <p>Then the JetBrains mark, which is the opposite of ordinary and is the reason the loader grew a real
 * scanner: nested {@code <g>}, four inline gradients, {@code style="fill:url(#…)"} on every shape, and
 * geometry that is <b>entirely filled polygons with no stroke anywhere</b>. Drawn beside the Feather set
 * it makes one frame answer both questions — a themed monochrome stroke set and full-colour filled
 * artwork, from one {@code render()} call each.</p>
 */
public class CgUiSvgIconScene implements InteractiveSceneLifecycle, CgSystemInput.Mouse {

    /**
     * Loaded from real {@code .svg} FILES now, not from a string in this class.
     *
     * <p>That is the difference that matters: an icon is an asset a resource pack ships, resolved through
     * {@code CgIO} like every other asset in the engine. Feather (MIT), and deliberately a spread of
     * element kinds -- {@code folder} is one path, {@code image} is a rounded rect plus a circle plus a
     * polyline, {@code package} mixes lines with a path. An icon set is not all {@code <path>}, and a
     * loader that pretends otherwise draws two thirds of one.</p>
     */
    private static final String[] ICONS =
            {"folder", "file-text", "image", "code", "package", "IntelliJ_IDEA_Icon"};

    private static final float GAP = 8f;

    private final java.util.List<SvgDocument> documents = new java.util.ArrayList<>();
    private float scale = 4f;

    @Override
    public void init(HarnessContext ctx) {
        for (String name : ICONS) {
            SvgDocument document = SvgDocument.load("crystalgui:ui/icons/" + name + ".svg");
            if (document != null) documents.add(document);
        }
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        CgRenderPipeline.getInstance().getFrameData().timeSecs = (float) frame.getElapsedTime();

        CgUiPaintContext paint = CgUiPaintContext.getInstance();
        paint.beginFrame(ctx.getScreenWidth(), ctx.getScreenHeight());

        // Laid out in a row, each scaled from its OWN viewBox rather than a shared constant: two icon sets
        // rarely agree on one, and an icon drawn against the wrong box is silently the wrong size.
        float totalWidth = 0f;
        for (SvgDocument document : documents) totalWidth += document.width() * scale + GAP;
        float x = (ctx.getScreenWidth() - Math.max(0f, totalWidth - GAP)) / 2f;

        int segments = 0;
        int triangles = 0;
        for (SvgDocument document : documents) {
            float y = (ctx.getScreenHeight() - document.height() * scale) / 2f;
            // The whole draw: the document already holds its geometry, so this is a loop over float[]s.
            // render() rather than renderMonochrome() so the file's OWN paint decides -- which is what
            // puts the Feather set in one tint (they are authored as currentColor) and the JetBrains mark
            // in its real colours, from the same call, with no per-icon branch here.
            document.render(paint, x, y, scale, 0xFFE0E0E0);
            segments += document.segmentCount();
            triangles += document.triangleCount();
            x += document.width() * scale + GAP;
        }
        paint.flush();

        paint.text().draw().at(0, 0)
                .text(String.format("SVG icons -- scroll to scale.  scale=%.2f  icons=%d  segments=%d"
                                + "  triangles=%d  (a 50-row tree would submit ~%d)",
                        scale, documents.size(), segments, triangles,
                        segments / Math.max(1, documents.size()) * 50))
                .font(paint.getFont().atSize(14)).submit();

        paint.endFrame();

        if (frame.getFrameNumber() == 5) ctx.getArtifactService().requestCapture("startup");
    }

    @Override
    public boolean consumeMouseEvent(CgSystemInput.Mouse.Event event) {
        float wheel = event.wheelDelta();
        if (wheel != 0f) {
            // A POSITIVE notch means the wheel rolled DOWN in this engine -- see ScrollerView, which is
            // the only statement of it. Taking the sign at face value here would zoom in on scroll-down.
            scale *= wheel > 0 ? 0.9f : 1.1f;
            scale = Math.max(0.5f, Math.min(80f, scale));
        }
        return true;
    }

    @Override
    public boolean isRunning() {
        return true;
    }

    @Override
    public boolean shouldShutdownOnComplete() {
        return false;
    }

    /** A 2D overlay scene: no world camera, so the harness must not install one. */
    @Override
    public boolean uses3DCamera() {
        return false;
    }

    @Override
    public void dispose() {
    }
}
