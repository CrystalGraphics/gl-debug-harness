package com.crystalgraphics.harness.util;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordingFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Counts what the JIT and the class loader did to the engine's code in a window of a run: JIT compilations,
 * deoptimisations and class loads of {@code com.crystalgraphics} classes, the harness's own left out. A JFR recording
 * runs from {@link #start}; windows are marked as the run goes and counted once at {@link #stop}.
 *
 * <pre>{@code
 * HarnessJitWatch jit = HarnessJitWatch.start();
 * jit.open();                     // the window's first frame
 * ...
 * jit.close();                    // its last
 * HarnessJitWatch.Counts first = jit.stop().get(0);
 * System.out.println(first);      // "3 compilations, 0 deopts, 1 class load"
 * }</pre>
 *
 * <ul>
 *   <li>Counts come only after {@link #stop}: JFR writes its events in chunks, not as they happen.</li>
 *   <li>Every compilation is counted, however short: the recording's threshold is 0.</li>
 * </ul>
 */
public final class HarnessJitWatch {

    /** What happened in one window to the engine's classes: the counts, and each event as "type class.method". */
    public record Counts(int compilations, int deopts, int classLoads, List<String> named) {
        public boolean quiet() {
            return compilations == 0 && deopts == 0 && classLoads == 0;
        }

        @Override
        public String toString() {
            return compilations + " compilations, " + deopts + " deopts, " + classLoads + " class loads";
        }
    }

    private static final String ENGINE = "com.crystalgraphics.", HARNESS = "com.crystalgraphics.harness.";

    private final Recording recording = new Recording();
    private final List<Instant> opens = new ArrayList<>(), closes = new ArrayList<>();

    private HarnessJitWatch() {
        recording.enable("jdk.Compilation").withThreshold(Duration.ZERO);
        recording.enable("jdk.Deoptimization");
        recording.enable("jdk.ClassLoad");
        recording.start();
    }

    /** A recording started now. */
    public static HarnessJitWatch start() {
        return new HarnessJitWatch();
    }

    /** Opens a window now. */
    public void open() {
        opens.add(Instant.now());
    }

    /** Closes the window opened last. */
    public void close() {
        closes.add(Instant.now());
    }

    /** Ends the recording and counts each closed window, in the order they were opened. */
    public List<Counts> stop() {
        try {
            Path file = Files.createTempFile("harness-jit", ".jfr");
            recording.stop();
            recording.dump(file);
            recording.close();
            List<RecordedEvent> events = RecordingFile.readAllEvents(file);
            Files.deleteIfExists(file);
            List<Counts> counts = new ArrayList<>();
            for (int w = 0; w < closes.size(); w++) counts.add(count(events, opens.get(w), closes.get(w)));
            return counts;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Counts count(List<RecordedEvent> events, Instant from, Instant to) {
        int compiled = 0, deopts = 0, loads = 0;
        List<String> named = new ArrayList<>();
        for (RecordedEvent e : events) {
            Instant at = e.getStartTime();
            if (at.isBefore(from) || at.isAfter(to)) continue;
            String type = e.getEventType().getName(), name = subject(e, type);
            if (name == null || !name.startsWith(ENGINE) || name.startsWith(HARNESS)) continue;
            switch (type) {
                case "jdk.Compilation" -> compiled++;
                case "jdk.Deoptimization" -> deopts++;
                default -> loads++;
            }
            named.add(type.substring(4) + " " + name);
        }
        return new Counts(compiled, deopts, loads, named);
    }

    /** The class, or class and method, an event is about. */
    private static String subject(RecordedEvent e, String type) {
        if (type.equals("jdk.ClassLoad")) {
            RecordedClass c = e.getValue("loadedClass");
            return c == null ? null : c.getName();
        }
        RecordedMethod m = e.getValue("method");
        return m == null ? null : m.getType().getName() + "." + m.getName();
    }
}
