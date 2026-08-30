package io.github.somehussar.crystalgraphics.harness.config;

/**
 * Which CrystalGUI engine a scene runs on: {@code --engine=old|new}.
 *
 * <p>The UI rewrite (CrystalGUI's {@code plan_m5.md}) builds a second engine beside the first and
 * keeps both runnable until the port is complete. The harness is the one place either can be looked
 * at, so the choice is a harness flag rather than a scene's: a scene that can draw its tree on both
 * says so by implementing {@link Aware}; every other scene runs on the old engine, and asked for the
 * new one it says so and exits rather than silently drawing the old tree.</p>
 *
 * <p>Default {@link #OLD}. Read by scenes through {@link #selected()}.</p>
 */
public enum HarnessEngine {
    OLD, NEW;

    private static HarnessEngine selected = OLD;

    public static HarnessEngine selected() {
        return selected;
    }

    public static boolean isNew() {
        return selected == NEW;
    }

    /** Parses the flag's value; anything but {@code new} is the old engine. */
    public static void select(String value) {
        selected = "new".equalsIgnoreCase(value) ? NEW : OLD;
    }

    /** A scene that can build its tree on more than one engine. */
    public interface Aware {
        boolean supportsEngine(HarnessEngine engine);
    }

    /** Whether {@code scene} can run on the selected engine. Every scene runs on the old one. */
    public static boolean canRun(Object scene) {
        if (selected == OLD) return true;
        return scene instanceof Aware && ((Aware) scene).supportsEngine(selected);
    }
}
