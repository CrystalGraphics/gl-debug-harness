package io.github.somehussar.crystalgraphics.harness.scene.ui;

import com.crystalgraphics.api.render.CgRenderPipeline;
import com.crystalgraphics.platform.input.CgSystemInput;

import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.render.texture.svg.SvgDocument;
import com.crystalgui.render.texture.svg.SvgPath;

import io.github.somehussar.crystalgraphics.harness.FrameInfo;
import io.github.somehussar.crystalgraphics.harness.InteractiveSceneLifecycle;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>Every shipped icon, drawn at once, in a labelled grid.</b> Scroll to scale.
 *
 * <h3>What this is for</h3>
 *
 * <p><b>Drag to pan, scroll to zoom.</b> Zoom is unbounded and anchored on the cursor, so a suspect cell
 * can be pushed up to any size without first hunting for it — which is the whole point when the artefact
 * being chased is a hairline.</p>
 *
 * <p>Diagnosis, not demonstration. An icon that fails to render is invisible in a file tree — the row
 * still lays out, the label still draws, and there is simply a gap where the glyph should be. Which icon
 * it was, and whether it failed to load or loaded and drew nothing, is not recoverable from that picture.
 * Here every icon is on screen with its name under it, and the two failure modes are told apart: a
 * <b>red</b> cell could not be loaded at all, an <b>amber</b> one parsed to zero draw operations.</p>
 *
 * <h3>Three passes, not three-per-icon</h3>
 *
 * <p>The cells, the icons and the labels are drawn in three separate loops rather than cell by cell, and
 * that is deliberate: quads, vector fills and text are three different materials, and
 * {@code CgUiPaintContext} flushes whenever it switches between them. Interleaved, this scene would cost
 * three material binds per icon; batched by kind it costs three in total.</p>
 *
 * <p>That is also the bug this scene exists downstream of. Alternating an icon and a label per row — which
 * is exactly what a file tree does — used to leave {@code activePath} claiming the curve path while
 * {@code CgTextRenderer} had quietly bound {@code text.shader}, so from the second row on the icons drew
 * against the text shader and the labels against the stroke shader. Glyph quads evaluated by a stroke SDF
 * come out as solid boxes.</p>
 */
public class CgUiSvgIconScene implements InteractiveSceneLifecycle, CgSystemInput.Mouse {

    /**
     * Every file in {@code assets/crystalgui/ui/icons/filetypes/}.
     *
     * <p>Hand-written, and therefore able to go stale — which for a diagnostic scene is the right trade
     * only because a name that has been deleted shows up <b>red</b> rather than silently vanishing. There
     * is no directory listing through {@code CgIO}; a resource index would be the fix if this ever needed
     * to be authoritative.</p>
     */
    private static final String[] FILETYPES = {
            "Csharp", "Csharp_dark", "addAny", "any_type", "archive", "as", "aspectj", "binaryData",
            "binaryData_dark", "config", "contexts", "contextsModifier", "css", "custom", "diagram",
            "dtd", "folder", "folder_dark", "hprof", "htaccess", "html", "http", "i18n", "idl", "image",
            "java", "javaClass", "javaOutsideSource", "javaScript", "jfr", "json", "jsonSchema",
            "jsonSchema_dark", "json_dark", "jsp", "jspx", "jupyter", "manifest", "microsoftWindows",
            "moduleGroup", "package", "properties", "regexp", "text", "uiForm", "unknown", "wsdlFile",
            "xhtml", "xml", "xsdFile", "yaml", "IntelliJ_IDEA_Icon"};

    /** Feather (MIT) — the stroked, {@code currentColor} set, kept so both cases are on one screen. */
    private static final String[] CHROME = {"folder", "file-text", "image", "code", "package"};

    private static final float MARGIN = 8f;
    private static final float LABEL_HEIGHT = 12f;
    private static final float GAP = 6f;

    /** The cell at zoom 1. Everything below is this multiplied by {@link #zoom}. */
    private static final float BASE_ICON = 32f;
    private static final float BASE_CELL_W = 62f;
    private static final float BASE_CELL_H = BASE_ICON + LABEL_HEIGHT + GAP;

    /**
     * Fixed, rather than derived from the window width.
     *
     * <p>A grid that re-flows as you zoom moves every cell you are not looking at, and the one you
     * <em>are</em> looking at jumps out from under the cursor. Stable columns plus free panning is how
     * every map does it, and it is the difference between inspecting a cell and re-finding it.</p>
     */
    private static final int COLUMNS = 10;

    private record Entry(String name, String path, SvgDocument document) {
        boolean missing() {
            return document == null;
        }

        boolean blank() {
            return document != null && document.isEmpty();
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    private float zoom = 1f;
    private float panX;
    private float panY;
    private boolean dragging;
    private int lastMouseX;
    private int lastMouseY;

    @Override
    public void init(HarnessContext ctx) {
        for (String name : FILETYPES) load("filetypes/" + name, name);
        for (String name : CHROME) load(name, name + " (feather)");
    }

    private void load(String path, String label) {
        String resource = "crystalgui:ui/icons/" + path + ".svg";
        entries.add(new Entry(label, resource, SvgDocument.load(resource)));
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        CgRenderPipeline.getInstance().getFrameData().timeSecs = (float) frame.getElapsedTime();

        CgUiPaintContext paint = CgUiPaintContext.getInstance();
        paint.beginFrame(ctx.getScreenWidth(), ctx.getScreenHeight());

        float cellW = BASE_CELL_W * zoom;
        float cellH = BASE_CELL_H * zoom;
        float iconPx = BASE_ICON * zoom;

        // Pass 1 -- cell backgrounds, so a failure is visible rather than absent. Quad path.
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (!entry.missing() && !entry.blank()) continue;
            paint.fillRect(cellX(i, cellW), cellY(i, cellH), cellW - GAP * zoom, cellH - GAP * zoom,
                    entry.missing() ? 0x60FF0000 : 0x60FFAA00);
        }

        // Pass 2 -- every icon. One material bind for the lot; see the class note.
        int missing = 0;
        int blank = 0;
        int triangles = 0;
        int segments = 0;
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (entry.missing()) {
                missing++;
                continue;
            }
            if (entry.blank()) {
                blank++;
                continue;
            }
            SvgDocument document = entry.document();
            // Fitted from the icon's OWN viewBox: this set is 16px and Feather's is 24, and scaling both
            // by one constant would draw one of them at two thirds the other's size.
            float scale = Math.min(iconPx / document.width(), iconPx / document.height());
            float left = cellX(i, cellW) + (cellW - GAP * zoom - document.width() * scale) * 0.5f;
            document.render(paint, left, cellY(i, cellH), scale, 0xFFE0E0E0);
            triangles += document.triangleCount();
            segments += document.segmentCount();
        }
        paint.flush();

        // Pass 3 -- labels. One switch to the text path for all of them.
        float labelPx = Math.max(7f, Math.min(48f, 9f * zoom));
        for (int i = 0; i < entries.size(); i++) {
            paint.text().draw().at(cellX(i, cellW), cellY(i, cellH) + iconPx)
                    .text(entries.get(i).name())
                    .font(paint.getFont().atSize((int) labelPx)).submit();
        }

        paint.text().draw().at(MARGIN, ctx.getScreenHeight() - 16f)
                .text(String.format("%d icons  --  %d missing (red)  %d blank (amber)  "
                                + "%d triangles  %d segments  --  drag to pan, scroll to zoom (%.2fx)",
                        entries.size(), missing, blank, triangles, segments, zoom))
                .font(paint.getFont().atSize(12)).submit();

        paint.endFrame();

        if (frame.getFrameNumber() == 5) ctx.getArtifactService().requestCapture("startup");
    }

    private float cellX(int index, float cellW) {
        return panX + MARGIN + (index % COLUMNS) * cellW;
    }

    private float cellY(int index, float cellH) {
        return panY + MARGIN + (index / COLUMNS) * cellH;
    }

    @Override
    public boolean consumeMouseEvent(CgSystemInput.Mouse.Event event) {
        if (event.button() >= 0) {
            dragging = event.state();
            lastMouseX = event.x();
            lastMouseY = event.y();
            return true;
        }

        float wheel = event.wheelDelta();
        if (wheel != 0f) {
            // ANCHORED ON THE CURSOR. Scaling about the origin instead sends whatever you were looking at
            // off-screen within two notches, which at high zoom means panning back to find it every time.
            // Solve for the pan that keeps the point under the pointer fixed: it is on the same grid
            // coordinate before and after, so pan' = mouse - (mouse - pan) * (zoom'/zoom).
            float previous = zoom;
            // A POSITIVE notch means the wheel rolled DOWN in this engine -- see ScrollerView, which is
            // the only statement of it. Taking the sign at face value here would zoom in on scroll-down.
            zoom *= wheel > 0 ? 0.9f : 1.1f;
            // Bounded only where the maths stops working: a zoom of zero collapses every cell onto one
            // point and is not recoverable by scrolling back.
            zoom = Math.max(0.02f, Math.min(400f, zoom));

            float ratio = zoom / previous;
            panX = event.x() - (event.x() - panX) * ratio;
            panY = event.y() - (event.y() - panY) * ratio;
            return true;
        }

        if (dragging) {
            panX += event.x() - lastMouseX;
            panY += event.y() - lastMouseY;
        }
        lastMouseX = event.x();
        lastMouseY = event.y();
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
