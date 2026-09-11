package io.github.somehussar.crystalgraphics.harness.util;

import java.io.File;

/**
 * Shared font discovery utility for harness scenes.
 */
public final class HarnessFontUtil {

    public static final String ARABIC_FONT = resolveFontPath("../CrystalGraphics/core/src/test/resources/fonts/IBMPlexSansArabic-Regular.ttf");
    public static final String JAPANESE_FONT = resolveFontPath("../CrystalGraphics/core/src/test/resources/fonts/MPLUS1p-Regular.ttf");
    public static final String LATIN_FONT = resolveFontPath("../CrystalGraphics/core/src/test/resources/fonts/IBMPlexSans-Regular.ttf");
    public static final String MINECRAFT_FONT = resolveFontPath("../core/src/main/resources/assets/crystalgui/ui/fonts/Minecraft.otf");

    /**
     * Resolves a font path from the given config, falling back to system font discovery.
     *
     * @param configFontPath the font path from config (may be null)
     * @return a valid font file path
     * @throws RuntimeException if no font is found
     */
    public static String resolveFontPath(String configFontPath) {
        if (configFontPath != null && !configFontPath.isEmpty()) {
            File f = new File(configFontPath);
            if (f.exists()) {
                return f.getAbsolutePath();
            }
            // Try relative to project root
            if (f.isAbsolute()) {
                throw new RuntimeException("Configured font path does not exist: " + configFontPath);
            }
        }

        // CrystalGraphics' own test font, beside the harness in this checkout.
        String[] preferredProjectFonts = {
            "../CrystalGraphics/core/src/test/resources/fonts/test-font.ttf"
        };
        for (String testFont : preferredProjectFonts) {
            File tf = new File(testFont);
            if (tf.exists()) {
                return tf.getAbsolutePath();
            }
        }

        return findSystemFont();
    }

    /**
     * Finds a system font. Shared across all scenes.
     */
   public static String findSystemFont() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            String winDir = System.getenv("WINDIR");
            if (winDir == null) winDir = "C:\\Windows";
            return winDir + "\\Fonts\\arial.ttf";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "/System/Library/Fonts/Helvetica.ttc";
        }
        String[] linuxFonts = {
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
            "/usr/share/fonts/TTF/DejaVuSans.ttf",
            "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
            "/usr/share/fonts/liberation-sans/LiberationSans-Regular.ttf"
        };
        for (String path : linuxFonts) {
            if (new File(path).exists()) {
                return path;
            }
        }
        throw new RuntimeException("No system font found. Set -Dharness.font.path=/path/to/font.ttf or --font-path=<path>");
    }

    private HarnessFontUtil() { }
}
