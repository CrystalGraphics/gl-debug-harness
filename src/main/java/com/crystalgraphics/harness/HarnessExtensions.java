package com.crystalgraphics.harness;

import java.util.List;
import java.util.ServiceLoader;

/** The {@link HarnessExtension}s on the classpath, loaded once. */
public final class HarnessExtensions {

    private static List<HarnessExtension> all;

    private HarnessExtensions() {
    }

    public static synchronized List<HarnessExtension> all() {
        if (all == null) {
            all = ServiceLoader.load(HarnessExtension.class).stream().map(ServiceLoader.Provider::get).toList();
        }
        return all;
    }
}
