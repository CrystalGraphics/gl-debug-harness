package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.api.material.CgMaterialUsage;
import com.crystalgraphics.easing.CgKeyframes;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.tool.GlErrorChecker;
import com.crystalgraphics.harness.util.HarnessFboHelper;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.device.CgDeviceInfo;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.vfx.scalability.CgVfxScalability;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.vfx.look.CgVfxKit;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.CgVfxModule;
import com.crystalgraphics.vfx.particle.gpu.CgVfxEvent;
import com.crystalgraphics.vfx.particle.gpu.CgVfxEventListener;
import com.crystalgraphics.vfx.particle.gpu.CgVfxEventRows;
import com.crystalgraphics.vfx.particle.gpu.CgVfxGpuEmitter;
import com.crystalgraphics.vfx.particle.spawn.CgVfxEmitterState;
import com.crystalgraphics.vfx.prefab.CgVfxPrefab;
import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgSequenceKinds;
import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.object.value.CgUnits;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;
import com.crystalgraphics.vfx.sequence.CgVfxClipBake;
import com.crystalgraphics.vfx.sequence.CgVfxNodeClip;
import org.joml.Matrix4f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.crystalgraphics.vfx.system.CgVfxSystemDefinition;
import com.crystalgraphics.vfx.system.CgVfxSystemInstance;

/**
 * The gate of particle-played clips: one {@code SEQUENCE} particle plays a clip baked from a sequence file, whose hub
 * turns about z in steps (90 degrees from frame 15, 180 from frame 45) and whose tip, two of the particle's sizes out,
 * draws a part and fades out on frame 66. Three frames are photographed: the part is where its node is at each, and gone once faded. An event at
 * the tip reports where it fired, the node's +Y its normal. The clip's burst and stream spawn children at the tip, which
 * a force pushes along x; each child's death spawns two grandchildren. Every child's and grandchild's row is checked
 * against Java: its id (a child's key, a grandchild's), where it was born, how far the force carried it.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-sequence"
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-sequence" -Dcrystalgraphics.compute.tier=G33
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-sequence --device=vulkan"
 * }</pre>
 *
 * <p>Prints {@code [vfx-sequence] PASS} or {@code FAIL}; on Vulkan a validation error fails it.</p>
 */
public class CgVfxSequenceScene implements InteractiveSceneLifecycle {

    private static final float DT = 1f / 60f, SIZE = 40f, REACH = 2f, PART = 0.3f, FORCE = 4f;
    private static final int BURST = 3, STREAM = 8, LEAVES = 2, END = 220, DRAIN = 40;
    /** The frames photographed: the tip up (hub at 90 degrees), left (180), then faded. */
    private static final int[] SHOTS = {60, 105, 150};
    private static final String[] SHOT_NAMES = {"up", "left", "faded"};
    private static final float TOLERANCE = 2e-3f;

    private final CgMaterialDefinition white = CgMaterialDefinition.of("harness:materials/sequence-part",
            "crystalgraphics:shaders/vfx/sequence/part.shader").usage(CgMaterialUsage.MESH_PARTICLES, CgMaterialUsage.STATIC_MESH).build();
    private final CgVfxPrefab prefab = CgVfxPrefab.builder("harness:sequence_gate").node("hub")
            .node("tip").parent("hub").at(REACH, 0f, 0f).scale(PART).part(white).build();
    private final int tip = prefab.node("tip");
    private final CgVfxEmitter leaf = CgVfxEmitter.builder("sequence-leaf").capacity(128).life(0.25f, 0.25f)
            .speed(0f, 0f).event(CgVfxEvent.onDeath().readback(128)).build();
    private final CgVfxEmitter middle = CgVfxEmitter.builder("sequence-middle").capacity(64).life(0.5f, 0.5f)
            .speed(0f, 0f).module(new CgVfxModule.Force(FORCE, 0f, 0f))
            .event(CgVfxEvent.onDeath().spawn(leaf, LEAVES).maxPerFrame(64).readback(64)).build();
    /** The clip's channels, from a sequence file: the hub's steps and the tip's fade. */
    private static final String SEQUENCE = "_v: 1\ntick_resolution: 30\ndisplay_rate: 30\nrange: [0, 90]\n"
            + "spawnables:\n  - { id: s1, name: gate, prefab: harness:sequence_gate }\n"
            + "tracks:\n"
            + "  - { id: t1, kind: transform, binding: { spawnable: s1, node: hub }, sections: [ { id: a, range: [0, 90],"
            + " rotation: { z: { keys: [[0, 0.0, constant], [15, 1.5707964, constant], [45, 3.1415927, constant]] } } } ] }\n"
            + "  - { id: t2, kind: property, binding: { spawnable: s1, node: tip }, property: opacity, sections: [ { id: b,"
            + " range: [0, 90], value: { keys: [[0, 1.0, constant], [66, 0.0, constant]] } } ] }\n";
    /** Rotations as radians, intrinsic x, y, z, in the host's axes. */
    private static final CgUnits ANGLES = CgUnits.of(Map.of("angles", "radians", "rotation", Map.of("order", "xyz", "intrinsic", true)), CgUnits.HOST);
    private final CgVfxNodeClip clip = CgVfxClipBake.builder("sequence-root", sequence())
            .burst("tip", middle, 30, BURST)
            .stream("tip", middle, 50, 69, 14f)
            .build();
    /** Its events: the tip's report, then the clip's burst and stream. */
    private final CgVfxEmitter root = CgVfxEmitter.builder("sequence-root").sequence(clip).state(CgVfxEmitterState.ONCE)
            .capacity(1).burst(0f, 1).life(3f, 3f).size(SIZE, SIZE).speed(0f, 0f).opacity(CgKeyframes.constant(1f))
            .event(CgVfxEvent.onAge(1f).at(tip).readback(4)).build();

    private CgSequenceDefinition sequence() {
        CgAssetCompiler.Dependencies deps = new CgAssetCompiler.Dependencies() {
            @Override
            public Object definition(CgType kind, String id) {
                return id.equals(prefab.id()) ? prefab : null;
            }

            @Override
            public CgUnits units() {
                return ANGLES;
            }
        };
        return CgSequenceDefinition.COMPILER.compile(CgAssetData.of("harness:sequence_gate", CgSequenceKinds.SEQUENCE,
                (Map<?, ?>) CgAssetYaml.read(SEQUENCE), null), deps);
    }

    private final CgVfxSystem vfx = new CgVfxSystem();
    private final CgVfxEventListener listener = this::heard;
    private final List<double[]> tipRows = new ArrayList<>(), middleRows = new ArrayList<>();
    private final Map<Integer, double[]> leafRows = new HashMap<>();
    private final int[][] shots = new int[SHOTS.length][3];
    private HarnessFboHelper target;
    private double cx, cy;
    private int frame, repeated, dropped;
    private String failure;
    private boolean running = true;

    @Override
    public void init(HarnessContext ctx) {
        vfx.onEvents(listener);
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo info) {
        frame++;
        int w = ctx.getScreenWidth(), h = ctx.getScreenHeight();
        if (frame == 1) {
            cx = Math.round(w * 0.5);
            cy = Math.round(h * 0.5);
            target = HarnessFboHelper.create(w, h, true);   // with depth: the GPU cull of its parts reads it
            CgVfxLook look = CgVfxLook.builder(CgVfxSystemDefinition.SCHEMA)
                    .with(CgVfxKit.of("harness:sequence").emitter(root).build(), prefab.kit()).build();
            vfx.play(new CgVfxSystemInstance<>(look, cx, cy, 0.0)).scalability(CgVfxScalability.HERO);   // far from the camera, in pixels
        }
        if (frame <= END) {
            target.bind();
            try {
                target.clear(0f, 0f, 0f, 1f);
                vfx.update(frame * (double) DT);
                vfx.submit(CgWorldRenderer.get());
                Matrix4f view = new Matrix4f(), projection = new Matrix4f().setOrtho(0, w, 0, h, -100, 100);
                for (CgRenderStage stage : List.of(CgRenderStage.WORLD_OPAQUE, CgRenderStage.WORLD_TRANSPARENT)) {
                    stage.host().set(0f, w, h, target.getFboId()).view().set(0, 0, 0, view, projection);
                    stage.fire();
                }
                for (int s = 0; s < SHOTS.length; s++) {
                    if (frame == SHOTS[s]) target.captureToFile(ctx.getOutputDir(), "vfx-sequence-" + SHOT_NAMES[s] + ".png");
                }
            } finally {
                target.unbind();
            }
            return;
        }
        if (frame > END + DRAIN) {
            report(ctx);
            running = false;
        }
    }

    private void heard(CgVfxGpuEmitter definition, int event, CgVfxEventRows rows) {
        dropped += rows.dropped();
        for (int i = 0; i < rows.count(); i++) {
            double[] row = {rows.x(i), rows.y(i), rows.z(i), rows.vx(i), rows.vy(i), rows.vz(i), rows.nx(i), rows.ny(i),
                    rows.nz(i), rows.parentId(i)};
            if (definition.name().equals(root.name())) tipRows.add(row);
            else if (definition.name().equals(middle.name())) middleRows.add(row);
            else if (leafRows.put(rows.parentId(i), row) != null) repeated++;
        }
    }

    /** Where the tip's part is drawn, from the effect, with the hub turned {@code degrees} about z. */
    private double[] tipAt(double degrees) {
        double a = Math.toRadians(degrees);
        return new double[]{cx + Math.cos(a) * REACH * SIZE, cy + Math.sin(a) * REACH * SIZE, 0.0};
    }

    private void compare() {
        // The tip's report, on frame 30 of the clip: the hub at 90, so the tip is up and its +Y points along -x.
        if (tipRows.size() != 1) {
            fail("the tip's event fired " + tipRows.size() + " times");
            return;
        }
        double[] at = tipAt(90), row = tipRows.get(0);
        if (!near(row, at, SIZE) || Math.abs(row[6] + 1.0) > TOLERANCE || Math.abs(row[7]) > TOLERANCE) {
            fail("the tip's event fired at (" + row[0] + ", " + row[1] + ") facing (" + row[6] + ", " + row[7]
                    + "), not at (" + at[0] + ", " + at[1] + ") facing -x");
            return;
        }
        // The children: the burst's at the tip up, the stream's at the tip left; each its key; carried by the force.
        Set<Integer> due = new HashSet<>();
        for (int i = 0; i < BURST; i++) due.add(CgVfxEvent.childKey(0, 1, i));
        for (int f = 1; f <= STREAM; f++) due.add(CgVfxEvent.childKey(0, 2, 0, f));
        Map<Integer, double[]> leavesDue = new HashMap<>();
        Set<Integer> seen = new HashSet<>();
        for (double[] m : middleRows) {
            int id = (int) m[9];
            if (!due.contains(id) || !seen.add(id)) {
                fail("a child of id " + id + " that no burst or stream of the clip spawned");
                return;
            }
            boolean burst = id == CgVfxEvent.childKey(0, 1, 0) || id == CgVfxEvent.childKey(0, 1, 1)
                    || id == CgVfxEvent.childKey(0, 1, 2);
            double[] born = tipAt(burst ? 90 : 180);
            // Semi-implicit Euler from rest: after k steps v = a k dt and x = a dt^2 k (k + 1) / 2.
            long k = Math.round(m[3] / (FORCE * DT));
            double x = born[0] + FORCE * DT * DT * k * (k + 1) / 2.0;
            if (Math.abs(m[3] - FORCE * DT * k) > TOLERANCE || k < 29 || k > 32
                    || !near(m, new double[]{x, born[1], 0.0}, SIZE)) {
                fail("child " + id + " died at (" + m[0] + ", " + m[1] + ") moving " + m[3] + ", not at (" + x + ", "
                        + born[1] + ") after " + k + " steps");
                return;
            }
            for (int i = 0; i < LEAVES; i++) leavesDue.put(CgVfxEvent.grandchildKey(id, 0, i, 0), m);
        }
        if (seen.size() != due.size()) {
            fail(seen.size() + " children died, " + due.size() + " due");
            return;
        }
        // The grandchildren: still where their parent died, each keyed by it.
        if (!leafRows.keySet().equals(leavesDue.keySet()) || repeated > 0 || dropped > 0) {
            fail(leafRows.size() + " grandchildren died (" + repeated + " twice, " + dropped + " rows dropped), "
                    + leavesDue.size() + " due, or their keys are not their parents'");
            return;
        }
        for (Map.Entry<Integer, double[]> l : leafRows.entrySet()) {
            if (!near(l.getValue(), leavesDue.get(l.getKey()), SIZE)) {
                fail("grandchild " + l.getKey() + " died away from where its parent did");
                return;
            }
        }
    }

    private static boolean near(double[] got, double[] want, float scale) {
        for (int a = 0; a < 3; a++) if (Math.abs(got[a] - want[a]) > TOLERANCE * (scale + Math.abs(want[a]))) return false;
        return true;
    }

    private void comparePixels(HarnessContext ctx) {
        double[][] lit = {tipAt(90), tipAt(180), null}, dark = {tipAt(180), tipAt(90), tipAt(180)};
        for (int s = 0; s < SHOTS.length; s++) {
            BufferedImage picture;
            try {
                picture = ImageIO.read(new File(ctx.getOutputDir(), "vfx-sequence-" + SHOT_NAMES[s] + ".png"));
            } catch (IOException e) {
                fail("the " + SHOT_NAMES[s] + " capture could not be read: " + e);
                return;
            }
            int on = lit[s] == null ? -1 : red(picture, lit[s]), off = red(picture, dark[s]);
            if (lit[s] != null && on < 200 || off > 20) {
                fail("the " + SHOT_NAMES[s] + " frame reads " + on + " where the part is and " + off + " where it is not");
                return;
            }
        }
    }

    private static int red(BufferedImage picture, double[] at) {
        return picture.getRGB((int) Math.round(at[0]), picture.getHeight() - 1 - (int) Math.round(at[1])) >> 16 & 0xFF;
    }

    private void fail(String what) {
        if (failure == null) failure = what;
    }

    private void report(HarnessContext ctx) {
        CgDeviceInfo device = PlatformServiceHarness.deviceInfo();
        String on = (device == null ? "gl" : device.name()) + " at " + CgCapabilities.detect().computeTier();
        int validation = PlatformServiceHarness.validationErrors();
        if (GlErrorChecker.checkAndLog("vfx-sequence")) {
            System.out.println("[vfx-sequence] FAIL on " + on + ": GL errors, logged above");
            return;
        }
        if (validation > 0) {
            System.out.println("[vfx-sequence] FAIL on " + on + ": " + validation + " Vulkan validation errors, logged above");
            return;
        }
        if (on.startsWith("recording")) {
            System.out.println("[vfx-sequence] PASS on recording: every command validated; a recording device draws nothing");
            return;
        }
        compare();
        comparePixels(ctx);
        if (failure != null) {
            System.out.println("[vfx-sequence] FAIL on " + on + ": " + failure);
        } else {
            System.out.println("[vfx-sequence] PASS on " + on + ": the part drawn at its node up and left and gone once "
                    + "faded; the tip's event fired there facing its +Y; " + middleRows.size() + " children born at the tip "
                    + "and carried by the force, " + leafRows.size() + " grandchildren keyed by them");
        }
    }

    @Override
    public void dispose() {
        if (target != null) target.delete();
        vfx.delete();
    }

    @Override public boolean isRunning() { return running; }

    @Override public boolean uses3DCamera() { return false; }

    @Override public boolean shouldShutdownOnComplete() { return true; }
}
