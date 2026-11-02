package com.crystalgraphics.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.CompletableFuture;

/** The {@link HarnessExtension}s on the classpath, loaded once. */
public final class HarnessExtensions {

    private static List<HarnessExtension> all;
    private static List<CompletableFuture<?>> starts;

    private HarnessExtensions() {
    }

    public static synchronized List<HarnessExtension> all() {
        if (all == null) {
            all = ServiceLoader.load(HarnessExtension.class).stream().map(ServiceLoader.Provider::get).toList();
        }
        return all;
    }

    /** Every extension's {@link HarnessExtension#start}, once: at pre-init. */
    public static synchronized void start() {
        if (starts != null) return;
        starts = new ArrayList<>();
        for (HarnessExtension e : all()) starts.add(e.start());
    }

    /** Whether every extension's start has ended: what a scene's {@code init} waits for. */
    public static synchronized boolean started() {
        if (starts == null) return true;
        for (CompletableFuture<?> f : starts) if (!f.isDone()) return false;
        return true;
    }
}
