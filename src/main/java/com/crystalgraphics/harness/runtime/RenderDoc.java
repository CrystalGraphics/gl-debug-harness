package com.crystalgraphics.harness.runtime;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * RenderDoc's in-app API: capture harness frames from code, to replay one with per-event GPU durations and every
 * resource's contents.
 *
 * <p>Attached by a launch argument, before the window exists (RenderDoc hooks context creation):</p>
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=cgui-desktop --renderdoc"
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=cgui-desktop --renderdoc=D:/tools/RenderDoc/renderdoc.dll"
 * }</pre>
 *
 * <p>Captures go to {@code harness-output/<scene>/renderdoc/}; F12 also captures, and a running RenderDoc UI lists them
 * under File &gt; Attach to Running Instance. From a scene or probe:</p>
 * <pre>{@code
 * RenderDoc.captureNextFrames(2);          // the next two frames, each its own .rdc
 *
 * RenderDoc.startFrameCapture();           // after the swap: bracket a frame yourself...
 * ... the frame ...
 * if (slow) RenderDoc.endFrameCapture();   // ...before the swap: keep it
 * else RenderDoc.discardFrameCapture();    // or throw it away
 *
 * for (Path rdc : RenderDoc.captures()) System.out.println(rdc);
 * }</pre>
 *
 * <ul>
 *   <li>Every method is a no-op when RenderDoc is not attached, so call sites need no guard; {@link #isAttached()}
 *       says which.</li>
 *   <li>Attached, every frame runs 2-4x slower, captured or not: a run with {@code --renderdoc} times nothing. Capture
 *       for what the replay shows, not to catch a slow frame.</li>
 *   <li>OpenGL captures a core-profile context only, which is what the harness creates.</li>
 * </ul>
 */
public final class RenderDoc {

    private static final Logger LOGGER = Logger.getLogger(RenderDoc.class.getName());

    /** eRENDERDOC_API_Version_1_4_1: the first with DiscardFrameCapture. */
    private static final int API_VERSION = 10401;

    // RENDERDOC_API_1_4_1's function table, by slot.
    private static final int GET_API_VERSION = 0;
    private static final int SET_CAPTURE_FILE_PATH_TEMPLATE = 11;
    private static final int GET_NUM_CAPTURES = 13;
    private static final int GET_CAPTURE = 14;
    private static final int START_FRAME_CAPTURE = 19;
    private static final int IS_FRAME_CAPTURING = 20;
    private static final int END_FRAME_CAPTURE = 21;
    private static final int TRIGGER_MULTI_FRAME_CAPTURE = 22;
    private static final int DISCARD_FRAME_CAPTURE = 24;
    private static final int SLOTS = 25;

    private static final Linker LINKER = Linker.nativeLinker();

    private static MethodHandle setCaptureFilePathTemplate;
    private static MethodHandle getNumCaptures;
    private static MethodHandle getCapture;
    private static MethodHandle startFrameCapture;
    private static MethodHandle isFrameCapturing;
    private static MethodHandle endFrameCapture;
    private static MethodHandle triggerMultiFrameCapture;
    private static MethodHandle discardFrameCapture;
    private static boolean attached;

    private RenderDoc() {
    }

    /** Attaches when {@code args} hold {@code --renderdoc} or {@code --renderdoc=<library>}. Call before the window. */
    public static void attachIfRequested(String[] args) {
        for (String arg : args) {
            if (arg.equals("--renderdoc")) attach(defaultLibrary());
            else if (arg.startsWith("--renderdoc=")) attach(Path.of(arg.substring("--renderdoc=".length())));
        }
    }

    /** Loads RenderDoc's library and its API. @return whether it is attached; a failure is logged, not thrown */
    public static boolean attach(Path library) {
        if (attached) return true;
        try {
            System.load(library.toAbsolutePath().toString());
            MemorySegment entry = SymbolLookup.loaderLookup().find("RENDERDOC_GetAPI")
                    .orElseThrow(() -> new IllegalStateException("no RENDERDOC_GetAPI in " + library));
            MethodHandle getApi = LINKER.downcallHandle(entry, FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS));
            MemorySegment api;
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment out = arena.allocate(ADDRESS);
                if ((int) getApi.invokeExact(API_VERSION, out) != 1) {
                    LOGGER.warning("[RenderDoc] " + library + " does not offer API 1.4.1; update RenderDoc");
                    return false;
                }
                api = out.get(ADDRESS, 0).reinterpret(ADDRESS.byteSize() * SLOTS);
            }
            setCaptureFilePathTemplate = function(api, SET_CAPTURE_FILE_PATH_TEMPLATE, FunctionDescriptor.ofVoid(ADDRESS));
            getNumCaptures = function(api, GET_NUM_CAPTURES, FunctionDescriptor.of(JAVA_INT));
            getCapture = function(api, GET_CAPTURE,
                    FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
            startFrameCapture = function(api, START_FRAME_CAPTURE, FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
            isFrameCapturing = function(api, IS_FRAME_CAPTURING, FunctionDescriptor.of(JAVA_INT));
            endFrameCapture = function(api, END_FRAME_CAPTURE, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
            triggerMultiFrameCapture = function(api, TRIGGER_MULTI_FRAME_CAPTURE, FunctionDescriptor.ofVoid(JAVA_INT));
            discardFrameCapture = function(api, DISCARD_FRAME_CAPTURE,
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
            attached = true;
            LOGGER.info("[RenderDoc] attached, API " + version(api) + ", from " + library);
            return true;
        } catch (Throwable e) {
            LOGGER.warning("[RenderDoc] not attached: " + e);
            return false;
        }
    }

    public static boolean isAttached() {
        return attached;
    }

    /** Where captures go: {@code prefix} plus RenderDoc's own {@code _frameN.rdc}. Its directory is created. */
    public static void captureTo(Path prefix) {
        if (!attached) return;
        try (Arena arena = Arena.ofConfined()) {
            Path absolute = prefix.toAbsolutePath();
            Files.createDirectories(absolute.getParent());
            setCaptureFilePathTemplate.invokeExact(arena.allocateFrom(absolute.toString()));
        } catch (Throwable e) {
            throw failed(e);
        }
    }

    /** Captures each of the next {@code frames} frames, as separate captures, from the next swap. */
    public static void captureNextFrames(int frames) {
        if (!attached) return;
        try {
            triggerMultiFrameCapture.invokeExact(frames);
        } catch (Throwable e) {
            throw failed(e);
        }
    }

    /** Opens a capture now; close it with {@link #endFrameCapture()} or {@link #discardFrameCapture()}. */
    public static void startFrameCapture() {
        if (!attached) return;
        try {
            startFrameCapture.invokeExact(MemorySegment.NULL, MemorySegment.NULL);
        } catch (Throwable e) {
            throw failed(e);
        }
    }

    public static boolean isFrameCapturing() {
        if (!attached) return false;
        try {
            return (int) isFrameCapturing.invokeExact() == 1;
        } catch (Throwable e) {
            throw failed(e);
        }
    }

    /** Closes the open capture and writes it. @return whether a capture was written */
    public static boolean endFrameCapture() {
        if (!attached) return false;
        try {
            return (int) endFrameCapture.invokeExact(MemorySegment.NULL, MemorySegment.NULL) == 1;
        } catch (Throwable e) {
            throw failed(e);
        }
    }

    /** Closes the open capture without writing it. */
    public static boolean discardFrameCapture() {
        if (!attached) return false;
        try {
            return (int) discardFrameCapture.invokeExact(MemorySegment.NULL, MemorySegment.NULL) == 1;
        } catch (Throwable e) {
            throw failed(e);
        }
    }

    /** Every capture this process has written, oldest first. */
    public static List<Path> captures() {
        List<Path> out = new ArrayList<>();
        if (!attached) return out;
        try (Arena arena = Arena.ofConfined()) {
            int count = (int) getNumCaptures.invokeExact();
            MemorySegment length = arena.allocate(JAVA_INT);
            for (int i = 0; i < count; i++) {
                if ((int) getCapture.invokeExact(i, MemorySegment.NULL, length, MemorySegment.NULL) != 1) continue;
                MemorySegment name = arena.allocate(length.get(JAVA_INT, 0));
                if ((int) getCapture.invokeExact(i, name, length, MemorySegment.NULL) == 1) {
                    out.add(Path.of(name.getString(0)));
                }
            }
        } catch (Throwable e) {
            throw failed(e);
        }
        return out;
    }

    private static Path defaultLibrary() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) return Path.of("C:\\Program Files\\RenderDoc\\renderdoc.dll");
        return Path.of("/usr/lib/librenderdoc.so");
    }

    private static MethodHandle function(MemorySegment api, int slot, FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(api.getAtIndex(ADDRESS, slot), descriptor);
    }

    private static String version(MemorySegment api) throws Throwable {
        MethodHandle get = function(api, GET_API_VERSION, FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment parts = arena.allocate(JAVA_INT, 3);
            get.invokeExact(parts, parts.asSlice(4), parts.asSlice(8));
            return parts.getAtIndex(JAVA_INT, 0) + "." + parts.getAtIndex(JAVA_INT, 1) + "." + parts.getAtIndex(JAVA_INT, 2);
        }
    }

    private static IllegalStateException failed(Throwable e) {
        return new IllegalStateException("RenderDoc call failed", e);
    }
}
