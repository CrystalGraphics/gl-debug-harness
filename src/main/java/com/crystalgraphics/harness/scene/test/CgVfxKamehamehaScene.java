package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontStyle;
import com.crystalgraphics.demo.CgVfxDemoControls;
import com.crystalgraphics.demo.CgVfxShowcase;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.camera.Camera3D;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.util.HarnessFontUtil;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.vfx.system.CgVfxSystemInstance;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Kakarot's Kamehameha played from its kit: the battle path's eight systems, each in its own lane side by side so a beam
 * runs past no other station, each played again as it ends, on the showcase's floor under its sky. N flies to the next
 * station, Shift+N the one before, M back to every lane; R plays every system again at once. The HUD names the station, its system and its beat in the game, and what
 * it is showing. Scene id {@code vfx-kamehameha}.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-kamehameha --device=vulkan"
 * }</pre>
 *
 * <ul>
 *   <li>The kit is the plan repository's built {@code kakarot_kit}, found from the harness's working directory; its
 *       textures resolve through {@code local/kitroot}, which the harness's build adds as a resource root.</li>
 *   <li>Each system plays at its own station, not at the caster's sockets, and the beam's user parameters stay at their
 *       defaults: the game's projectile sets them each frame.</li>
 * </ul>
 */
public final class CgVfxKamehamehaScene implements InteractiveSceneLifecycle, CgSystemInput.Keyboard {

    /** From the harness's working directory, {@code gl-debug-harness/}. */
    private static final Path KIT = Paths.get("../plan/crystalgraphics/vfx-aaa/local/examples/kakarot_kit");
    private static final String PARTICLES = "Art/EFF/e/skill/a/Cpl001/Particles/";
    /** Blocks between lanes along z; each system faces +x, down its lane. */
    private static final float SPACING = 8f;
    /** The cameras' yaw, looking down the lanes along +x: the first lane on the left. */
    private static final float DOWN_THE_LANES = -90f;
    /** Where a station's system plays above the floor, and its label above that. */
    private static final float HEIGHT = 1.5f, LABEL = 4.5f;

    /** One system of the battle path: what it is, and where and when the game plays it. */
    private record Station(String title, String beat, String id) {
    }

    private static final List<Station> STATIONS = List.of(
            new Station("start flash", "charge montage, frame 0, at the pelvis pitched -90",
                    "Art/EFF/e/skill/a/cmn/Particles/PS_skill_cmn_start_01_00"),
            new Station("charge", "charge montage, frame 8, at hand_r (10, 0, -10)",
                    PARTICLES + "PS_skill_Cpl001_kameha_s_charge_01_00"),
            new Station("charge loop", "from frame 17, 30 frames a pass, at hand_r",
                    PARTICLES + "PS_skill_Cpl001_kameha_s_charge_01_00_lp"),
            new Station("muzzle burst", "shot montage, frame 6.7, at EFF_root (0, 60, 138) yaw 90",
                    PARTICLES + "PS_skill_cpl001_kameha_shot_01_00"),
            new Station("beam start", "the projectile's root, at the hand: then the loop",
                    PARTICLES + "PS_skill_cpl001_kameha_bullet_01_00_ST"),
            new Station("beam loop", "held 1.7 s; its length the projectile's, at defaults here",
                    PARTICLES + "PS_skill_cpl001_kameha_bullet_01_00_LP"),
            new Station("beam end", "after the loop's 1.7 s",
                    PARTICLES + "PS_skill_cpl001_kameha_bullet_01_00_ED"),
            new Station("hit", "where the beam strikes, every 0.2 s",
                    "Art/EFF/e/cmn/Particles/PS_cmn_hit_beem_s_01_00"));

    /** The battle path's systems, by id under the kit's {@code vfx/systems}: what {@link CgVfxKitPlayScene} gates. */
    static List<String> systems() {
        List<String> ids = new ArrayList<>();
        for (Station s : STATIONS) ids.add(s.id());
        return ids;
    }

    private final CgVfxSystem vfx = new CgVfxSystem();
    private final CgVfxShowcase stage = new CgVfxShowcase();
    private final HarnessVfxControls controls = new HarnessVfxControls();
    private final CgVfxSystemInstance<?>[] playing = new CgVfxSystemInstance<?>[STATIONS.size()];
    private final int[] plays = new int[STATIONS.size()];
    private HarnessKitSystems kit;
    private CgFont font;
    private Camera3D camera;
    private boolean shift, started;
    /** The station framed, -1 for every lane. */
    private int station = -1;
    private String failure;
    /** {@code crystalgraphics.harness.probe}'s, else null. */
    private HarnessParticleProbe probe;

    @Override
    public void init(HarnessContext ctx) {
        camera = ctx.getCamera3D();
        camera.setMoveSpeed(10f);
        font = CgFont.load(HarnessFontUtil.LATIN_FONT, CgFontStyle.REGULAR, 48);
        kit = HarnessKitSystems.load(vfx, "kakarot_kit", KIT, systems());
        probe = HarnessParticleProbe.fromProperties(vfx);
        overview();
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        if (!started && failure == null) {
            if (kit.failed()) {
                failure = kit.failures();
                System.out.println("[vfx-kamehameha] the kit's systems failed to load:\n  " + failure);
            } else if (kit.ready()) {
                for (int i = 0; i < playing.length; i++) play(i);
                started = true;
            }
        }
        if (started) {
            for (int i = 0; i < playing.length; i++) {
                if (playing[i].state() == CgVfxSystemInstance.State.DEAD) play(i);
            }
        }
        CgWorldRenderer world = CgWorldRenderer.get();
        vfx.update(CgVfxDemoControls.get().time(frame.getElapsedTime()));
        vfx.submit(world);
        for (int i = 0; i < STATIONS.size(); i++) {
            world.text(STATIONS.get(i).title()).at(0.0, LABEL, z(i)).height(0.4f).font(font).stroke(0.06f, 0xFF000000).submit();
        }
        stage.submitStage(world, 0.0, 0.0, 0.0, camera.getPosX(), camera.getPosY(), camera.getPosZ());
        HarnessWorld.fire(ctx, camera.getViewMatrix(), ctx.getProjection());
    }

    private void play(int i) {
        playing[i] = vfx.spawn(kit.ref(i), 0.0, HEIGHT, z(i)).ground(0f);
        plays[i]++;
        if (probe != null) probe.watch(STATIONS.get(i).title(), playing[i]);
    }

    private static double z(int i) {
        return i * SPACING;
    }

    /** Every lane from behind their start, looking down them. */
    private void overview() {
        station = -1;
        camera.moveCamera(-30f, 14f, (float) z(STATIONS.size() - 1) / 2f);
        camera.setYaw(DOWN_THE_LANES);
        camera.setPitch(-20f);
    }

    private void frame(int i) {
        station = Math.floorMod(i, STATIONS.size());
        camera.moveCamera(-9f, 3f, (float) z(station));
        camera.setYaw(DOWN_THE_LANES);
        camera.setPitch(-9f);
    }

    @Override
    public boolean consumeKeyboardEvent(CgSystemInput.Keyboard.Event event) {
        int key = event.key();
        if (key == CgKeyCodes.KEY_LSHIFT || key == CgKeyCodes.KEY_RSHIFT) shift = event.pressed();
        if (event.pressed() && !event.repeat()) {
            if (key == CgKeyCodes.KEY_N) frame(station < 0 ? (shift ? -1 : 0) : station + (shift ? -1 : 1));
            else if (key == CgKeyCodes.KEY_M) overview();
            else if (key == CgKeyCodes.KEY_R && started) {
                for (int i = 0; i < playing.length; i++) {
                    playing[i].kill();
                    play(i);
                }
            }
        }
        return controls.consume(event);
    }

    @Override
    public String hudLine() {
        StringBuilder hud = new StringBuilder(CgVfxDemoControls.get().hudLines(false));
        hud.append("\nN / Shift+N: station   M: every lane   R: play all again");
        if (failure != null) return hud.append("\nthe kit failed to load: see the log").toString();
        if (!started) return hud.append("\nloading the kit's systems...").toString();
        if (station < 0) return hud.append("\nevery lane: Kakarot's Kamehameha, the battle path's ").append(STATIONS.size())
                .append(" systems, a lane each, left to right").toString();
        Station s = STATIONS.get(station);
        CgVfxSystemInstance<?> p = playing[station];
        boolean particles = false;
        for (int e = 0; e < p.emitters(); e++) particles |= p.emitter(e).mayHaveParticles();
        return hud.append("\n").append(station + 1).append("/").append(STATIONS.size()).append("  ").append(s.title())
                .append(": ").append(s.beat())
                .append("\n").append(s.id())
                .append(String.format("\nage %.2f s, play %d, %s", p.age(), plays[station], particles ? "particles alive" : "no particles"))
                .toString();
    }

    @Override
    public void dispose() {
        if (probe != null) probe.close();
        vfx.delete();
        stage.delete();
        if (font != null) font.dispose();
    }

    @Override public boolean isRunning() { return true; }
    @Override public boolean uses3DCamera() { return true; }
    @Override public boolean shouldShutdownOnComplete() { return false; }
}
