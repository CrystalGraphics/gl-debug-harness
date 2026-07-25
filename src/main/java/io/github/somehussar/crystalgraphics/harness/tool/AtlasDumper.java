package io.github.somehussar.crystalgraphics.harness.tool;

import com.crystalgraphics.api.font.CgGlyphKey;
import com.crystalgraphics.api.font.CgGlyphPlacement;
import com.crystalgraphics.text.atlas.CgGlyphAtlasPage;
import io.github.somehussar.crystalgraphics.harness.util.ScreenshotUtil;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Logger;

/**
 * Agent-debug tool #7: Dump a {@link CgGlyphAtlasPage} texture to PNG
 * plus a metadata summary.
 */
public final class AtlasDumper {

    private static final Logger LOGGER = Logger.getLogger(AtlasDumper.class.getName());

    // ── Multi-page dump support ────────────────────────────────────────

    /**
     * Dumps paged-atlas pages: per-page PNGs plus a combined manifest.
     *
     * <p>Filename convention (from atlas overhaul plan §7.8):</p>
     * <ul>
     *   <li>{@code msdf-atlas-dump-128px-page-0.png}</li>
     *   <li>{@code msdf-atlas-dump-128px-page-1.png}</li>
     *   <li>{@code msdf-atlas-dump-128px-manifest.txt} (combined manifest)</li>
     * </ul>
     *
     * <p>Each page is a layer of the atlas family's shared
     * {@code GL_TEXTURE_2D_ARRAY} (see the atlas texture-array migration) —
     * captured via {@link ScreenshotUtil#captureArrayTextureLayer}, not a
     * standalone-texture read, since {@code page.getTextureId()} now returns
     * the same array id for every page and {@code page.getPageIndex()} is the
     * layer to read.</p>
     *
     * @param pages       list of populated atlas pages to dump
     * @param typePrefix  filename prefix: "msdf-atlas-dump" or "bitmap-atlas-dump"
     * @param pxSizeSuffix  pixel size suffix: e.g., "128px"
     * @param outputDir   output directory
     */
    public static void dumpAllPagedPages(List<CgGlyphAtlasPage> pages,
                                         String typePrefix, String pxSizeSuffix,
                                         String outputDir) {
        if (pages == null || pages.isEmpty()) {
            LOGGER.warning("[AtlasDumper] No paged atlas pages to dump for " + typePrefix + "-" + pxSizeSuffix);
            return;
        }

        String basePrefix = typePrefix + "-" + pxSizeSuffix;

        for (int i = 0; i < pages.size(); i++) {
            CgGlyphAtlasPage page = pages.get(i);
            if (page == null || page.isDeleted() || page.getTextureId() == 0) {
                LOGGER.warning("[AtlasDumper] Skipping null/deleted paged page " + i);
                continue;
            }

            String pagePng = basePrefix + "-page-" + i + ".png";
            ScreenshotUtil.captureArrayTextureLayer(page.getTextureId(), page.getPageIndex(),
                    page.getPageWidth(), page.getPageHeight(), outputDir, pagePng);
            LOGGER.info("[AtlasDumper] Dumped paged page " + i + " (array layer " + page.getPageIndex() + "): " + pagePng
                    + " (" + page.getPageWidth() + "x" + page.getPageHeight() + ")");
        }

        String manifestName = basePrefix + "-manifest.txt";
        writeMultiPageManifestForPagedPages(pages, basePrefix, outputDir, manifestName);
    }

    // ── Manifest writers ───────────────────────────────────────────────

    static void writeMultiPageManifestForPagedPages(List<CgGlyphAtlasPage> pages,
                                                    String basePrefix,
                                                    String outputDir,
                                                    String manifestName) {
        File manifestFile = new File(outputDir, manifestName);
        try {
            PrintWriter pw = new PrintWriter(new FileWriter(manifestFile));
            try {
                pw.println("=== Atlas Multi-Page Manifest ===");
                pw.println("Base: " + basePrefix);
                pw.println("Page Count: " + pages.size());
                pw.println();

                int totalGlyphs = 0;
                long totalPackedArea = 0;
                long totalPageArea = 0;

                for (int i = 0; i < pages.size(); i++) {
                    CgGlyphAtlasPage page = pages.get(i);
                    int glyphs = page.getSlotCount();
                    int w = page.getPageWidth();
                    int h = page.getPageHeight();
                    long packedArea = page.getPackedArea();
                    float utilization = page.getUtilization();

                    pw.println("--- Page " + i + " ---");
                    pw.println("  Texture ID: " + page.getTextureId());
                    pw.println("  Dimensions: " + w + "x" + h);
                    pw.println("  Type: " + page.getType());
                    pw.println("  Glyph Count: " + glyphs);
                    pw.println("  Packed Area: " + packedArea + " px");
                    pw.printf("  Utilization: %.1f%%%n", utilization * 100.0f);
                    writeGlyphListing(pw, page);
                    pw.println();

                    totalGlyphs += glyphs;
                    totalPackedArea += packedArea;
                    totalPageArea += (long) w * h;
                }

                pw.println("--- Totals ---");
                pw.println("  Total Glyphs: " + totalGlyphs);
                pw.println("  Total Packed Area: " + totalPackedArea + " px");
                pw.println("  Total Page Area: " + totalPageArea + " px");
                if (totalPageArea > 0) {
                    pw.printf("  Overall Utilization: %.1f%%%n",
                            (float) totalPackedArea / totalPageArea * 100.0f);
                }
                pw.println();
                pw.println("=== End Atlas Multi-Page Manifest ===");
            } finally {
                pw.close();
            }
            LOGGER.info("[AtlasDumper] Wrote paged multi-page manifest to " + manifestFile.getAbsolutePath());
        } catch (IOException e) {
            LOGGER.warning("[AtlasDumper] Failed to write paged manifest: " + e.getMessage());
        }
    }

    private static void writeGlyphListing(PrintWriter pw, CgGlyphAtlasPage page) {
        java.util.Set<CgGlyphKey> keys = page.getGlyphKeys();
        if (keys.isEmpty()) {
            return;
        }
        pw.println("  Glyph IDs: " + formatGlyphIds(keys));
        List<CgGlyphKey> sorted = new ArrayList<>(keys);
        Collections.sort(sorted, Comparator.comparingInt(CgGlyphKey::getGlyphId));
        pw.println("  Glyph Metadata:");
        for (CgGlyphKey key : sorted) {
            CgGlyphPlacement placement = page.get(key, 0L);
            if (placement == null) {
                continue;
            }
            pw.println(String.format(
                    "    glyphId=%d codePoint=U+%04X plane=[%.4f, %.4f, %.4f, %.4f] atlas=[%d, %d, %d, %d] size=%dx%d pxRange=%.2f type=%s",
                    key.getGlyphId(),
                    key.getGlyphId(),
                    placement.planeLeft(),
                    placement.planeBottom(),
                    placement.planeRight(),
                    placement.planeTop(),
                    placement.atlasLeft(),
                    placement.atlasBottom(),
                    placement.atlasRight(),
                    placement.atlasTop(),
                    placement.atlasRight() - placement.atlasLeft(),
                    placement.atlasTop() - placement.atlasBottom(),
                    placement.pxRange(),
                    placement.atlasType()));
        }
    }

    private static String formatGlyphIds(java.util.Set<CgGlyphKey> keys) {
        java.util.List<Integer> ids = new java.util.ArrayList<Integer>();
        for (CgGlyphKey key : keys) {
            ids.add(key.getGlyphId());
        }
        java.util.Collections.sort(ids);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(ids.get(i));
        }
        return sb.toString();
    }

    private AtlasDumper() { }
}
