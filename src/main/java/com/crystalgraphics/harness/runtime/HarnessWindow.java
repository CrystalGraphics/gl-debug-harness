package com.crystalgraphics.harness.runtime;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgraphics.platform.input.CgSystemInput;

import org.lwjgl.glfw.Callbacks;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * The harness's one window and GL context, on GLFW: creation, the frame's swap, poll and pacing, resize,
 * the cursor grab, and the input the runner hands to scenes.
 *
 * <pre>{@code
 * HarnessWindow.create(1920, 1080, "CrystalGraphics Debug Harness");
 * while (!HarnessWindow.shouldClose()) {
 *     HarnessWindow.pollEvents();
 *     for (CgSystemInput.Keyboard.Event key : HarnessWindow.drainKeyboard()) scene.consumeKeyboardEvent(key);
 *     for (CgSystemInput.Mouse.Event pointer : HarnessWindow.drainMouse()) scene.consumeMouseEvent(pointer);
 *     render();
 *     HarnessWindow.swapBuffers();
 *     HarnessWindow.sync(120);
 * }
 * HarnessWindow.destroy();
 * }</pre>
 *
 * <ul>
 *   <li>Static, like the LWJGL 2 {@code Display} it replaces: the harness has one window, and the platform
 *       services take {@link #handle()} as a supplier before the window exists (0 until {@link #create}).</li>
 *   <li>Sizes and pointer coordinates are the FRAMEBUFFER's, top-left origin, which is what every scene
 *       and {@code glViewport} expect; on a HiDPI display the window's own size is smaller.</li>
 *   <li>A key and its character arrive as two events, as GLFW reports them and as CrystalGUI's modern hosts
 *       deliver them: the key with no character, then the character with {@link CgKeyCodes#KEY_NONE}.</li>
 *   <li>Key state is read through {@code CgPlatform.input()}, not from here.</li>
 * </ul>
 */
public final class HarnessWindow {

    private static long window;
    /** False for a window a Vulkan device presents to: no GL context, and no buffers of its own to swap. */
    private static boolean glContext = true;
    private static int framebufferWidth;
    private static int framebufferHeight;
    private static boolean resized;

    private static final ArrayDeque<CgSystemInput.Keyboard.Event> keyboard = new ArrayDeque<>();
    private static final ArrayDeque<CgSystemInput.Mouse.Event> mouse = new ArrayDeque<>();

    private static double cursorX;
    private static double cursorY;
    /** Pointer travel since {@link #takeMouseDelta} last read it, for a grabbed-cursor camera. */
    private static double travelX;
    private static double travelY;
    private static boolean grabbed;
    /** Wheel notches since {@link #takeWheel} last read them, positive away from the user. */
    private static double wheel;

    private static long nextFrameNanos;

    /**
     * Asked for newest first. A driver grants the version asked rather than its newest, so asking for 3.3 on
     * a 4.6 card hides every 4.x path from {@code CgCapabilities}. 4.1 is macOS's ceiling.
     */
    private static final int[][] CORE_VERSIONS = {{4, 6}, {4, 5}, {4, 3}, {4, 1}, {3, 3}};

    private HarnessWindow() {}

    /**
     * Opens the window and makes current the newest OpenGL core context the driver grants, 4.6 down to 3.3.
     *
     * @throws IllegalStateException when GLFW cannot start or the driver refuses the context
     */
    public static void create(int width, int height, String title) {
        create(width, height, title, true);
    }

    /** @param gl false for a Vulkan device's window: no context, and {@link #swapBuffers()} does nothing */
    public static void create(int width, int height, String title, boolean gl) {
        GLFWErrorCallback.createPrint(System.err).set();
        if (!GLFW.glfwInit()) throw new IllegalStateException("GLFW failed to initialise");
        glContext = gl;
        if (!gl) {
            GLFW.glfwDefaultWindowHints();
            GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API);
            GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, GLFW.GLFW_TRUE);
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
            window = GLFW.glfwCreateWindow(width, height, title, 0L, 0L);
            if (window == 0L) {
                GLFW.glfwTerminate();
                throw new IllegalStateException("GLFW could not create a " + width + "x" + height + " window");
            }
            afterCreate();
            return;
        }

        // A refused version is expected on the way down; only the last refusal is worth printing.
        GLFWErrorCallback print = GLFW.glfwSetErrorCallback(null);
        for (int[] version : CORE_VERSIONS) {
            GLFW.glfwDefaultWindowHints();
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, version[0]);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, version[1]);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
            // macOS hands out a core context above 2.1 only when it is forward-compatible.
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
            GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, GLFW.GLFW_TRUE);
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
            if (version == CORE_VERSIONS[CORE_VERSIONS.length - 1]) GLFW.glfwSetErrorCallback(print);
            window = GLFW.glfwCreateWindow(width, height, title, 0L, 0L);
            if (window != 0L) break;
        }
        GLFW.glfwSetErrorCallback(print);
        if (window == 0L) {
            GLFW.glfwTerminate();
            throw new IllegalStateException("GLFW could not create a " + width + "x" + height
                    + " window with an OpenGL core context of 3.3 or newer");
        }
        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();
        // Unsynchronised, as LWJGL 2's Display was: sync(fps) paces the loop.
        GLFW.glfwSwapInterval(0);
        afterCreate();
    }

    /** The size and input callbacks, the same for either kind of window. */
    private static void afterCreate() {
        int[] w = new int[1];
        int[] h = new int[1];
        GLFW.glfwGetFramebufferSize(window, w, h);
        framebufferWidth = w[0];
        framebufferHeight = h[0];

        GLFW.glfwSetFramebufferSizeCallback(window, (win, fw, fh) -> {
            if (fw == 0 || fh == 0) return;   // minimised: keep drawing at the last real size
            framebufferWidth = fw;
            framebufferHeight = fh;
            resized = true;
        });
        GLFW.glfwSetKeyCallback(window, (win, key, scancode, action, mods) -> {
            int local = CgPlatform.input().translateKeyboardCodes(key);
            keyboard.add(new CgSystemInput.Keyboard.Event((char) 0, local, action != GLFW.GLFW_RELEASE,
                    action == GLFW.GLFW_REPEAT, System.currentTimeMillis()));
        });
        GLFW.glfwSetCharCallback(window, (win, codepoint) -> {
            for (char c : Character.toChars(codepoint)) {
                keyboard.add(new CgSystemInput.Keyboard.Event(c, CgKeyCodes.KEY_NONE, true, false,
                        System.currentTimeMillis()));
            }
        });
        GLFW.glfwSetCursorPosCallback(window, (win, x, y) -> {
            double scale = framebufferScale();
            double fx = x * scale;
            double fy = y * scale;
            travelX += fx - cursorX;
            travelY += fy - cursorY;
            int dx = (int) Math.round(fx - cursorX);
            int dy = (int) Math.round(fy - cursorY);
            cursorX = fx;
            cursorY = fy;
            mouse.add(new CgSystemInput.Mouse.Event((int) fx, (int) fy, dx, dy, CgMouseCodes.NONE, false, 0f, -1L));
        });
        GLFW.glfwSetMouseButtonCallback(window, (win, button, action, mods) ->
                mouse.add(new CgSystemInput.Mouse.Event((int) cursorX, (int) cursorY, 0, 0,
                        CgPlatform.input().translateMouseCodes(button), action == GLFW.GLFW_PRESS, 0f,
                        System.currentTimeMillis())));
        GLFW.glfwSetScrollCallback(window, (win, dx, dy) -> {
            wheel += dy;
            // GLFW reports a roll away from the user as positive; the event's convention is the reverse.
            mouse.add(new CgSystemInput.Mouse.Event((int) cursorX, (int) cursorY, 0, 0, CgMouseCodes.NONE, false,
                    (float) -dy, -1L));
        });

        GLFW.glfwShowWindow(window);
    }

    /** The GLFW handle, or 0 before {@link #create}. What the platform's input and cursor services read. */
    public static long handle() {
        return window;
    }

    public static boolean shouldClose() {
        return window != 0L && GLFW.glfwWindowShouldClose(window);
    }

    public static void pollEvents() {
        GLFW.glfwPollEvents();
    }

    public static void swapBuffers() {
        if (glContext) GLFW.glfwSwapBuffers(window);
    }

    /** Whether the window has a GL context: false when a Vulkan device presents to it. */
    public static boolean hasGlContext() {
        return glContext;
    }

    /**
     * Sleeps until the next frame is due at {@code fps}, as LWJGL 2's {@code Display.sync} did. A loop that
     * has fallen behind starts again from now rather than racing to catch up.
     */
    public static void sync(int fps) {
        long frame = 1_000_000_000L / fps;
        long now = System.nanoTime();
        if (nextFrameNanos == 0L || now - nextFrameNanos > frame) nextFrameNanos = now;
        nextFrameNanos += frame;
        long wait;
        while ((wait = nextFrameNanos - System.nanoTime()) > 0L) {
            if (wait > 2_000_000L) {
                try {
                    Thread.sleep((wait - 1_000_000L) / 1_000_000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            } else {
                Thread.onSpinWait();
            }
        }
    }

    public static void setTitle(String title) {
        if (window != 0L) GLFW.glfwSetWindowTitle(window, title);
    }

    /** Framebuffer width in pixels. */
    public static int width() {
        return framebufferWidth;
    }

    /** Framebuffer height in pixels. */
    public static int height() {
        return framebufferHeight;
    }

    /** Whether the framebuffer changed size since the last call, which clears it. */
    public static boolean takeResized() {
        boolean was = resized;
        resized = false;
        return was;
    }

    /** The keyboard events since the last drain, oldest first. */
    public static List<CgSystemInput.Keyboard.Event> drainKeyboard() {
        List<CgSystemInput.Keyboard.Event> events = new ArrayList<>(keyboard);
        keyboard.clear();
        return events;
    }

    /** The pointer events since the last drain, oldest first. */
    public static List<CgSystemInput.Mouse.Event> drainMouse() {
        List<CgSystemInput.Mouse.Event> events = new ArrayList<>(mouse);
        mouse.clear();
        return events;
    }

    /** Hides and captures the cursor for a mouse-look camera, or gives it back. */
    public static void setGrabbed(boolean grab) {
        grabbed = grab;
        if (window != 0L) {
            GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR,
                    grab ? GLFW.GLFW_CURSOR_DISABLED : GLFW.GLFW_CURSOR_NORMAL);
        }
        takeMouseDelta();
    }

    public static boolean isGrabbed() {
        return grabbed;
    }

    /**
     * The pointer's travel since the last call, in framebuffer pixels, y down; and resets it. A camera
     * calls this once a frame, and once when it grabs, so the jump to the grab point is not a turn.
     */
    public static int[] takeMouseDelta() {
        int[] delta = {(int) Math.round(travelX), (int) Math.round(travelY)};
        travelX = 0;
        travelY = 0;
        return delta;
    }

    /** The wheel's notches since the last call, positive away from the user; and resets them. */
    public static double takeWheel() {
        double notches = wheel;
        wheel = 0;
        return notches;
    }

    public static void destroy() {
        if (window == 0L) return;
        Callbacks.glfwFreeCallbacks(window);
        GLFW.glfwDestroyWindow(window);
        window = 0L;
        GLFW.glfwTerminate();
        GLFWErrorCallback previous = GLFW.glfwSetErrorCallback(null);
        if (previous != null) previous.free();
    }

    private static double framebufferScale() {
        int[] w = new int[1];
        int[] h = new int[1];
        GLFW.glfwGetWindowSize(window, w, h);
        return w[0] == 0 ? 1.0 : (double) framebufferWidth / w[0];
    }
}
