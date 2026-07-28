package io.github.somehussar.crystalgraphics.harness.tool;

import com.crystalgraphics.api.font.CgGlyphKey;
import com.crystalgraphics.api.font.CgGlyphPlacement;
import com.crystalgraphics.text.atlas.CgGlyphAtlasPage;
import com.crystalgraphics.text.atlas.CgGlyphAtlas;
import com.crystalgraphics.text.cache.CgFontRegistry;
import io.github.somehussar.crystalgraphics.harness.util.HarnessOutputDir;
import io.github.somehussar.crystalgraphics.harness.util.ScreenshotUtil;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Agent-debug tool #7: Dump a {@link CgGlyphAtlasPage} texture to PNG
 * plus a metadata summary.
 */
public final class AtlasDumper {

    private static final Logger LOGGER = Logger.getLogger(AtlasDumper.class.getName());

    // ── Whole-atlas dump ─────────────────────────────────────────────────

    /**
     * Dumps every populated atlas page to {@code harnessOutputRoot/atlas/}, plus a manifest per
     * tier — for visually auditing atlas state (e.g. checking a many-page CJK atlas packs and
     * renders correctly) without hand-picking pages.
     *
     * <p><strong>This dumps the atlases, not a font.</strong> There is exactly one bitmap atlas and
     * one distance-field atlas process-wide and every font shares them, so there is no per-font
     * output to produce: a single page routinely holds glyphs from several faces at once. It used
     * to take a {@code CgFont} and write to {@code atlas/{font-name}/}, which was accurate while
     * atlases were keyed per font and became actively misleading once they were merged — the
     * directory named after one font contained every font's glyphs.
     *
     * <p>Filenames are still labelled with the tier's real raster size — {@code atlasScalePx} for
     * the distance-field atlas, which genuinely has exactly one. The bitmap label is the page
     * dimension rather than a glyph size, because bitmap glyphs of many sizes now share pages and
     * no single pixel size describes one.
     *
     * @param harnessOutputRoot the harness output root (e.g. {@code gl-debug-harness/harness-output}) —
     *                          pages land directly in {@code {harnessOutputRoot}/atlas/}, a sibling of
     *                          every scene's own output subdirectory, not inside one
     */
    public static void dumpAtlases(String harnessOutputRoot) {
        CgFontRegistry registry = CgFontRegistry.get();
        Map<Integer, List<CgGlyphAtlasPage>> bitmapBuckets = registry.findAllPopulatedBitmapPagesBySize(null);
        Map<Integer, List<CgGlyphAtlasPage>> msdfBuckets = registry.findAllPopulatedMSDFPagesBySize(null);
        if (bitmapBuckets.isEmpty() && msdfBuckets.isEmpty()) {
            LOGGER.warning("[AtlasDumper] No populated atlas pages to dump.");
            return;
        }

        String outputDir = new File(harnessOutputRoot, "atlas").getPath();
        HarnessOutputDir.ensureExists(outputDir);

        int totalBitmapPages = 0;
        for (Map.Entry<Integer, List<CgGlyphAtlasPage>> bucket : bitmapBuckets.entrySet()) {
            dumpAllPagedPages(bucket.getValue(), "bitmap-atlas-dump", bucket.getKey() + "px", outputDir);
            totalBitmapPages += bucket.getValue().size();
        }
        int totalMsdfPages = 0;
        for (Map.Entry<Integer, List<CgGlyphAtlasPage>> bucket : msdfBuckets.entrySet()) {
            dumpAllPagedPages(bucket.getValue(), "msdf-atlas-dump", bucket.getKey() + "px", outputDir);
            totalMsdfPages += bucket.getValue().size();
        }
        LOGGER.info("[AtlasDumper] Dumped atlases: " + totalBitmapPages + " bitmap page(s), "
                + totalMsdfPages + " msdf page(s) -> " + outputDir);
    }

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
                    // Distinguishes "looks empty in the dump image" from "has space a glyph
                    // could actually use". A page can read 75% utilised and still hold zero
                    // regions big enough for a real glyph, in which case the empty pixels are
                    // an inherent packing remainder rather than an allocator failure.
                    int avgGlyph = page.getType() == CgGlyphAtlas.Type.BITMAP ? 48 : 86;
                    pw.printf("  Free regions fitting %dpx: %d  (60px: %d, 40px: %d)%n",
                            avgGlyph, page.countFreeRegionsFitting(avgGlyph, avgGlyph),
                            page.countFreeRegionsFitting(60, 60),
                            page.countFreeRegionsFitting(40, 40));
                    int[][] freeRegions = page.describeFreeRegions();
                    StringBuilder fr = new StringBuilder("  Largest free regions (w x h @ x,y):");
                    for (int r = 0; r < Math.min(6, freeRegions.length); r++) {
                        int[] q = freeRegions[r];
                        fr.append(String.format("  %dx%d@%d,%d", q[2], q[3], q[0], q[1]));
                    }
                    if (freeRegions.length == 0) fr.append("  (none)");
                    pw.println(fr);
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
