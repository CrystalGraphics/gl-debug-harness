package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialInstance;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshShapes;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.tool.DeviceErrors;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.render.CgRenderer;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.graph.CgDepthAccess;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.graph.CgRequest;
import com.crystalgraphics.render.meshpass.CgCustomDepth;
import com.crystalgraphics.render.meshpass.CgMeshPassType;
import com.crystalgraphics.render.meshpass.CgPassLayout;
import com.crystalgraphics.render.meshpass.CgPrimitiveFlag;
import com.crystalgraphics.render.meshpass.engine.CgEnginePasses;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.scene.component.CgMeshComponent;
import com.crystalgraphics.render.scene.pass.CgViewPasses;
import com.crystalgraphics.render.view.CgView;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * R11's proof: the client scene drawn by the engine's mesh passes into targets of the scene's own, no chunk and no world
 * renderer. Six cubes of one mesh and material in a row (merged into one instanced draw), one of them marked into custom
 * depth and one flagged a heat source; a cube moving across the view; a floor with a decal on it; a translucent pane.
 * Each frame runs depth, base, custom depth, velocity, decal and translucent passes from the view's commands, and a
 * consumer's pass type ({@link #THERMAL}) drawing what is flagged. Its last frame reads pixels back: the cubes' and the
 * floor's colours, the decal's red on the floor and not off it, the marked cube's stencil and no other's, velocity at
 * the moving cube and nowhere still, the consumer's mark on the flagged cube alone. Every frame after the first must take
 * the base pass's commands from what was kept. Prints {@code [mesh-passes] PASS} or {@code FAIL}.
 */
public class CgMeshPassesTestScene implements InteractiveSceneLifecycle {

    private static final int W = 480, H = 270, FRAMES = 6;
    static final CgPrimitiveFlag HEAT = CgPrimitiveFlag.register("harness:heat_source");
    private static final CgCustomDepth MARK = CgCustomDepth.stencil("harness:mesh_passes_mark");
    private static CgMaterial heat;
    /** A consumer's pass: what is flagged a heat source, drawn with a material of the pass's own. */
    static final CgMeshPassType THERMAL = CgMeshPassType.register("harness:thermal",
            CgPassLayout.color(CgFormat.R16_FLOAT).depth(CgFormat.DEPTH32_FLOAT), (batch, primitive, out) -> {
                if (primitive.has(HEAT)) out.draw(batch, heat);
            });

    private final Matrix4f view = new Matrix4f();
    private final Matrix4f projection = CgCamera.perspectiveMatrix(new Matrix4f(), 60f, (float) W / H, 0.1f, 200f);
    private final List<String> failures = new ArrayList<>();
    private final StencilProbe stencilProbe = new StencilProbe();

    @Override
    public void init(HarnessContext ctx) {
        heat = CgMaterial.load("assets/harness/shader/mesh_passes_heat.shader");
    }

    /** Harness frames waited for the readbacks before giving up. */
    private static final int GIVE_UP = 120;
    private int step;
    private boolean reported;
    private CgView v;
    private CgCamera camera;
    private CgMeshComponent mover;
    /** Rows a dynamic primitive gathers each frame: built every frame, never kept. */
    private static final int DYNAMIC_ROWS = 1;
    private int keptBase = -1, builtBase = -1;
    private Probes probes;

    /** A frame of the scene a harness frame: the readbacks land as the harness's frames retire. */
    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        if (reported) return;
        if (step == 0) setUp();
        if (step < FRAMES) {
            int f = step++;
            mover.at(-1 + 0.25 * f, -0.8, -6);
            CgScene.client().update();
            CgRecording rec = new CgRecording();
            CgViewPasses passes = CgViewPasses.of(v, CgScene.client(), camera).skipUnready(false);
            // The camera stands still: last frame's view is this one's, and every frame after the first has it
            CgPassConstants constants = new CgPassConstants().seenThrough(camera, W, H)
                    .history(new Matrix4f(projection).mul(view), f, f > 0);
            Probes p = draw(rec, passes, constants, f == FRAMES - 1);
            if (f > 0) {
                builtBase = Math.max(builtBase, p.builtBase);
                keptBase = p.keptBase;
            }
            if (f == FRAMES - 1) probes = p;
            CgRenderer.executeNow(rec);
            return;
        }
        if (!probes.done() && step++ < FRAMES + GIVE_UP) return;
        CgScene.client().clear();
        reported = true;
        report(probes, builtBase, keptBase, DeviceErrors.checkAndLog("mesh-passes"));
    }

    private void setUp() {
        CgMaterialInstance flat = CgMaterial.load("assets/harness/shader/flat_colour.shader").instance();
        CgMaterialInstance glass = CgMaterial.load("assets/harness/shader/forward_transparent_test.shader").instance();
        CgMaterial decalMaterial = CgMaterial.load("assets/harness/shader/mesh_passes_decal.shader");
        CgMesh cube = CgMeshShapes.cube();
        CgMesh plane = CgMeshShapes.quad(CgVertexFormat.SPATIAL, 12f, 12f);
        // The client scene's: every pass binds its records until a view has a scene of its own (R12)
        CgScene scene = CgScene.client();
        scene.clear();
        for (int i = 0; i < 6; i++) {
            CgMeshComponent c = scene.mesh(cube, flat).at(-5 + 2 * i, 1, -10).customData(0, 0f, 1f, 0f, 1f);
            if (i == 1) c.customDepth(MARK);
            if (i == 4) c.flag(HEAT);
            c.spawn();
        }
        scene.mesh(plane, flat).at(0, -2, -10).rotation(new Quaternionf().rotateX((float) -Math.PI / 2))
                .customData(0, 0.3f, 0.3f, 0.3f, 1f).spawn();
        mover = scene.mesh(cube, flat).at(-1, -0.8, -6).customData(0, 1f, 1f, 1f, 1f).spawn();
        scene.mesh(CgMeshShapes.quad(CgVertexFormat.SPATIAL, 1f, 1f), glass).at(3, 1, -8).customData(0, 1f, 0f, 1f, 0.5f).spawn();
        // X down: projected onto the floor, a 3 x 3 square
        scene.decal(decalMaterial).at(-3, -2, -10).rotation(new Quaternionf().rotateZ((float) -Math.PI / 2))
                .size(0.5f, 1.5f, 1.5f).spawn();
        // A dynamic primitive's cube: a frame record of the recording, where every other draws a scene record
        scene.dynamic((view, out) -> out.mesh(cube, flat).at(-6, 3, -12).customData(0, 1f, 1f, 0f, 1f).add()).spawn();
        v = CgView.create("mesh-passes");
        camera = new CgCamera().set(0, 0, 0, view, projection).resolution(W, H);
    }

    /** What the last frame reads back, as it lands. */
    private final class Probes {
        final List<CgRequest> requests = new ArrayList<>();
        int builtBase, keptBase;
        String commands = "";
        float[] green = new float[4], floor = new float[4], decal = new float[4], pane = new float[4], dynamic = new float[4];
        float[] velocityMoving = new float[2], velocityStill = new float[2];
        float heatFlagged = -1, heatOther = -1;
        int stencilMarked = -1, stencilOther = -1;

        boolean done() {
            for (CgRequest r : requests) if (!r.done()) return false;
            return true;
        }
    }

    private Probes draw(CgRecording rec, CgViewPasses passes, CgPassConstants constants, boolean read) {
        Probes p = new Probes();
        CgGraphTexture colour = CgGraphTexture.transientTexture("meshPasses.colour", CgTextureDesc.of(W, H, CgFormat.RGBA16_FLOAT));
        CgGraphTexture depth = CgGraphTexture.transientTexture("meshPasses.depth", CgTextureDesc.of(W, H, CgFormat.DEPTH32_FLOAT));
        CgGraphTexture custom = CgGraphTexture.transientTexture("meshPasses.custom",
                CgTextureDesc.of(W, H, CgEnginePasses.CUSTOM_DEPTH.layout().depth()));
        CgGraphTexture velocity = CgGraphTexture.transientTexture("meshPasses.velocity",
                CgTextureDesc.of(W, H, CgEnginePasses.VELOCITY_FORMAT));
        CgGraphTexture thermal = CgGraphTexture.transientTexture("meshPasses.thermal", CgTextureDesc.of(W, H, CgFormat.R16_FLOAT));

        passes.cull(rec, null);
        rec.raster("meshPasses.depth").depth(depth, CgLoad.CLEAR_DEPTH, CgDepthAccess.WRITE).constants(constants)
                .add(passes.commands(CgEnginePasses.DEPTH, rec)).end();
        int built = passes.built(), kept = passes.kept();
        rec.raster("meshPasses.base").color(colour, CgLoad.clear(0f, 0f, 0f, 1f)).depth(depth, CgLoad.load(), CgDepthAccess.WRITE)
                .constants(constants).add(passes.commands(CgEnginePasses.BASE, rec)).end();
        p.builtBase = passes.built() - built;
        p.keptBase = passes.kept() - kept;
        rec.raster("meshPasses.custom").depth(custom, CgLoad.CLEAR_DEPTH.andStencil(0), CgDepthAccess.WRITE).constants(constants)
                .add(passes.commands(CgEnginePasses.CUSTOM_DEPTH, rec)).end();
        rec.raster("meshPasses.velocity").color(velocity, CgLoad.clear(0f, 0f, 0f, 0f)).depth(depth, CgLoad.load(), CgDepthAccess.READ)
                .constants(constants).add(passes.commands(CgEnginePasses.VELOCITY, rec)).end();
        rec.raster("meshPasses.decal").color(colour, CgLoad.load()).readsDepth(depth).constants(constants)
                .add(passes.commands(CgEnginePasses.DECAL, rec)).end();
        rec.raster("meshPasses.translucent").color(colour, CgLoad.load()).depth(depth, CgLoad.load(), CgDepthAccess.READ)
                .constants(constants).add(passes.commands(CgEnginePasses.TRANSLUCENT, rec)).end();
        rec.raster("meshPasses.thermal").color(thermal, CgLoad.clear(0f, 0f, 0f, 0f)).depth(depth, CgLoad.load(), CgDepthAccess.READ)
                .constants(constants).add(passes.commands(THERMAL, rec)).end();
        // The same lists again: built once a frame and recording
        p.commands = "depth " + passes.commands(CgEnginePasses.DEPTH, rec).size() + ", base " + passes.commands(CgEnginePasses.BASE, rec).size()
                + ", custom depth " + passes.commands(CgEnginePasses.CUSTOM_DEPTH, rec).size() + ", velocity "
                + passes.commands(CgEnginePasses.VELOCITY, rec).size() + ", decal " + passes.commands(CgEnginePasses.DECAL, rec).size()
                + ", translucent " + passes.commands(CgEnginePasses.TRANSLUCENT, rec).size() + ", thermal " + passes.commands(THERMAL, rec).size()
                + " of " + passes.visibility().batches().size() + " rows";
        if (!read) return p;

        int[] green = pixel(-5 + 2 * 3, 1, -10), marked = pixel(-3, 1, -10), flagged = pixel(3, 1, -10);
        int[] floor = pixel(4, -2, -9), decal = pixel(-3, -2, -10), pane = pixel(3, 1, -8);
        int[] moving = pixel(-1 + 0.25 * (FRAMES - 1), -0.8, -6);
        read(p, rec, colour, green, d -> halves(d, p.green));
        read(p, rec, colour, pixel(-6, 3, -12), d -> halves(d, p.dynamic));
        read(p, rec, colour, floor, d -> halves(d, p.floor));
        read(p, rec, colour, decal, d -> halves(d, p.decal));
        read(p, rec, colour, pane, d -> halves(d, p.pane));
        read(p, rec, velocity, moving, d -> unorms(d, p.velocityMoving));
        read(p, rec, velocity, green, d -> unorms(d, p.velocityStill));
        read(p, rec, thermal, flagged, d -> p.heatFlagged = Float.float16ToFloat(d.getShort(0)));
        read(p, rec, thermal, green, d -> p.heatOther = Float.float16ToFloat(d.getShort(0)));
        CgGraphTexture bytes = stencilProbe.record(rec, constants, custom);
        if (bytes != null) {
            read(p, rec, bytes, marked, d -> p.stencilMarked = d.get(0) & 0xFF);
            read(p, rec, bytes, green, d -> p.stencilOther = d.get(0) & 0xFF);
        } else {
            failures.add("the stencil probe's program did not build");
        }
        return p;
    }

    private interface Reading {
        void read(ByteBuffer data);
    }

    private static void read(Probes p, CgRecording rec, CgGraphTexture texture, int[] at, Reading reading) {
        p.requests.add(rec.readback(texture, 0, at[0], at[1], 1, 1, reading::read));
    }

    private static void halves(ByteBuffer data, float[] out) {
        for (int i = 0; i < out.length; i++) out[i] = Float.float16ToFloat(data.getShort(i * 2));
    }

    /** 16-bit unsigned normalized channels: the velocity target ({@code CgEnginePasses.VELOCITY_FORMAT}). */
    private static void unorms(ByteBuffer data, float[] out) {
        for (int i = 0; i < out.length; i++) out[i] = (data.getShort(i * 2) & 0xffff) / 65535f;
    }

    /** Where {@code (x, y, z)} lands on the target, from its bottom left. */
    private int[] pixel(double x, double y, double z) {
        Vector3f window = new Matrix4f(projection).mul(view).project((float) x, (float) y, (float) z, new int[] {0, 0, W, H},
                new Vector3f());
        return new int[] {Math.round(window.x), Math.round(window.y)};
    }

    private void report(Probes p, int builtBase, int keptBase, boolean glErrors) {
        CgRHI.Globals device = PlatformServiceHarness.deviceInfo();
        String on = device == null ? "gl" : device.name();
        int validation = PlatformServiceHarness.validationErrors();
        if (p == null || !p.done()) {
            System.out.println("[mesh-passes] FAIL on " + on + ": the readbacks never landed");
            return;
        }
        float vx = (p.velocityMoving[0] - 32767f / 65535f) / (0.499f * 0.5f);
        System.out.println("[mesh-passes] commands: " + p.commands + "; the dynamic primitive's cube " + fmt(p.dynamic));
        System.out.println("[mesh-passes] base kept " + keptBase + ", built " + builtBase + " after the first frame; cube "
                + fmt(p.green) + ", floor " + fmt(p.floor) + ", decal " + fmt(p.decal) + ", pane " + fmt(p.pane)
                + "; velocity at the mover " + fmt(p.velocityMoving) + " (x " + vx + "), still " + fmt(p.velocityStill)
                + "; stencil marked " + p.stencilMarked + " (" + MARK.value() + "), other " + p.stencilOther
                + "; thermal flagged " + p.heatFlagged + ", other " + p.heatOther);
        String fail = failures.isEmpty() ? null : failures.get(0);
        if (fail == null && glErrors) fail = "GL errors, logged above";
        else if (fail == null && validation > 0) fail = validation + " Vulkan validation errors, logged above";
        else if (fail == null && (builtBase != DYNAMIC_ROWS || keptBase < 8)) {
            fail = "the base pass built " + builtBase + " rows and kept " + keptBase + " after its first frame, not the dynamic primitive's "
                    + DYNAMIC_ROWS + " and every persistent row";
        } else if (fail == null && !(p.green[1] > 0.9f && p.green[0] < 0.1f)) fail = "the cube is " + fmt(p.green) + ", not green";
        else if (fail == null && !(p.dynamic[0] > 0.9f && p.dynamic[1] > 0.9f && p.dynamic[2] < 0.1f)) {
            fail = "the dynamic primitive's cube is " + fmt(p.dynamic) + ", not yellow";
        }
        else if (fail == null && !(Math.abs(p.floor[0] - 0.3f) < 0.05f && Math.abs(p.floor[2] - 0.3f) < 0.05f)) {
            fail = "the floor off the decal is " + fmt(p.floor) + ", not grey";
        } else if (fail == null && !(p.decal[0] > 0.9f && p.decal[1] < 0.1f)) fail = "the floor under the decal is " + fmt(p.decal) + ", not red";
        else if (fail == null && !(p.pane[0] > 0.1f && p.pane[2] > 0.1f)) fail = "the pane over the cube is " + fmt(p.pane) + ": nothing blended";
        else if (fail == null && !(p.velocityMoving[0] > 0f && vx > 0f)) fail = "the moving cube wrote no velocity, or not rightward";
        else if (fail == null && (p.velocityStill[0] != 0f || p.velocityStill[1] != 0f)) fail = "a still cube wrote velocity";
        else if (fail == null && p.stencilMarked != MARK.value()) fail = "the marked cube's stencil is " + p.stencilMarked;
        else if (fail == null && p.stencilOther != 0) fail = "an unmarked cube's stencil is " + p.stencilOther;
        else if (fail == null && !(p.heatFlagged > 0.9f && p.heatOther == 0f)) fail = "the consumer's pass drew what was not flagged, or missed what was";
        if (fail != null) {
            System.out.println("[mesh-passes] FAIL on " + on + ": " + fail);
            return;
        }
        System.out.println("[mesh-passes] PASS on " + on + ": the scene through the engine's mesh passes and a consumer's, "
                + "commands kept after the first frame, decal, custom depth stencil, velocity and the consumer's pass where each belongs");
    }

    private static String fmt(float[] v) {
        StringBuilder b = new StringBuilder("(");
        for (int i = 0; i < v.length; i++) b.append(i > 0 ? ", " : "").append(String.format("%.3f", v[i]));
        return b.append(')').toString();
    }

    @Override
    public void dispose() {
    }

    @Override
    public boolean isRunning() {
        return !reported;
    }

    @Override
    public boolean uses3DCamera() {
        return false;
    }

    @Override
    public boolean shouldShutdownOnComplete() {
        return true;
    }
}
