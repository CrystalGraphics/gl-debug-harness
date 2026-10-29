package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.asset.kind.CgGameplayKinds;
import com.crystalgraphics.asset.load.CgLoadPriority;
import com.crystalgraphics.asset.load.CgVfxAssets;
import com.crystalgraphics.cue.CgCueManager;
import com.crystalgraphics.cue.CgCueParameters;
import com.crystalgraphics.cue.CgCuePlaying;
import com.crystalgraphics.sequence.CgSequencePlayer;
import com.crystalgraphics.sequence.CgSequencer;
import com.crystalgraphics.sequence.binding.CgSequenceRole;
import com.crystalgraphics.sequence.event.CgSequenceEventListener;
import com.crystalgraphics.tag.CgTagRegistry;
import com.crystalgraphics.world.CgAttachment;

import javax.annotation.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Kakarot's {@code ATProjectileBeam} as the showcase's gameplay stand-in: hears a sequence's
 * {@code AnimNotify_SpawnProjectileBeam} span and plays the projectile's cue as the beam actor. RPG-Core flies it in
 * the game; skinned-play here, for K's shot and X's cut.
 *
 * <pre>{@code
 * KakarotProjectileBeam beams = KakarotProjectileBeam.install(sequencer, cues, player -> casterOf(player));
 * beams.close();   // with the scene
 * }</pre>
 *
 * <ul>
 *   <li>Placed once at the span's start, at the caster's {@code SocketName} plus {@code vecOffset} in the caster's frame,
 *       facing the caster's way (no target): not attached, as the game's actor.</li>
 *   <li>Its head, the cue's {@code ParticleTop}, starts {@code InitTopOffset} out along that facing and flies at
 *       {@code InitialSpeed}; nothing blocks it here.</li>
 *   <li>With {@code bRequestEnd} the span's end removes the cue, which plays its end phase; without, or for a beam
 *       spawned inside a cut-in ({@code ATAnimNotifyState_SkillCutin}, whose beams the request never reaches), the play
 *       stopping does, as the game ends it when its owner's action changes.</li>
 *   <li>The cue's loop waits {@code DurationST} and replaces the start phase on each socket, and its end phase replaces
 *       the loop: the cue's {@code delay} and {@code replace}, as the importer writes them.</li>
 *   <li>Each frame it writes {@code BeamLength} and {@code CylinderLength} as the cue's {@code writes} say.</li>
 * </ul>
 */
final class KakarotProjectileBeam implements CgSequenceEventListener, AutoCloseable {

    static final String EVENT = "kakarot:AnimNotify_SpawnProjectileBeam", CUTIN = "kakarot:ATAnimNotifyState_SkillCutin";
    private static final Logger LOGGER = LogManager.getLogger(KakarotProjectileBeam.class);
    /** The cue's ATProjectileBeam defaults, in metres: InitTopOffset 300, BeamLengthBase 100, InitialSpeed 16000. */
    private static final float TOP_OFFSET = 3f, LENGTH_BASE = 1f, SPEED = 160f;

    private final CgSequencer sequencer;
    private final CgCueManager cues;
    private final Function<CgSequencePlayer, CgSequenceRole> casters;
    private final Map<CgSequencePlayer, CgCuePlaying> playing = new IdentityHashMap<>();
    /** Plays inside an {@link #CUTIN} span, and plays whose beam ends with its span ({@code bRequestEnd}). */
    private final Set<CgSequencePlayer> cutIns = Collections.newSetFromMap(new IdentityHashMap<>()),
            requestsEnd = Collections.newSetFromMap(new IdentityHashMap<>());
    private final CgCueParameters params = new CgCueParameters();

    private KakarotProjectileBeam(CgSequencer sequencer, CgCueManager cues, Function<CgSequencePlayer, CgSequenceRole> casters) {
        this.sequencer = sequencer;
        this.cues = cues;
        this.casters = casters;
    }

    /** Listens on {@code sequencer}'s {@code rpg} side; {@code casters} names the caster of a play, or null. */
    static KakarotProjectileBeam install(CgSequencer sequencer, CgCueManager cues,
                                         Function<CgSequencePlayer, CgSequenceRole> casters) {
        KakarotProjectileBeam beams = new KakarotProjectileBeam(sequencer, cues, casters);
        sequencer.listen(Side.RPG, beams);
        return beams;
    }

    /**
     * Loads {@code projectile}'s cue ahead of its first beam: an event's args name it, so the moves' own loads never
     * reach it, and an add of a cue not yet ready waits for its load.
     */
    KakarotProjectileBeam prepare(String projectile) {
        CgVfxAssets.instance().declare(CgGameplayKinds.CUE, projectile).request(CgLoadPriority.MOVE);
        return this;
    }

    @Override
    public void heard(CgSequencePlayer player, Phase phase, String event, @Nullable Object args) {
        if (event.equals(CUTIN)) {
            if (phase == Phase.BEGIN) cutIns.add(player);
            else if (phase == Phase.END) cutIns.remove(player);
            return;
        }
        if (!event.equals(EVENT) || !(args instanceof Map<?, ?> a)) return;
        if (phase == Phase.END) {
            // bRequestEnd ends it with the span; without, as the owner's action changes (end(), below)
            if (requestsEnd.remove(player)) end(player);
            return;
        }
        CgSequenceRole caster = casters.apply(player);
        if (caster == null || !(a.get("Projectile") instanceof String projectile)) {
            LOGGER.warn("[skinned-play] {}'s beam has no caster or Projectile: {}", player.definition().id(), args);
            return;
        }
        String socket = a.get("SocketName") instanceof String s ? s : null;
        Beam beam = Beam.at(caster.attachment(socket), caster.attachment(null), a.get("vecOffset"));
        if (beam == null) {
            LOGGER.warn("[skinned-play] the beam {}: its caster's {} is not posed", projectile, socket);
            return;
        }
        LOGGER.info("[skinned-play] the beam {}", projectile);
        // The importer tags each projectile's cue by its asset's name: kakarot_kit:cue.BP_Projectile_...
        String tag = projectile.substring(0, projectile.indexOf(':') + 1) + "cue." + projectile.substring(projectile.lastIndexOf('/') + 1);
        end(player);
        CgCuePlaying p = cues.add(CgTagRegistry.global().require(tag), params.reset().target(beam));
        p.writes("BeamLength", (time, out) -> {
            // The owner's action changing: its play stopped (a new move, a release)
            if (player.stopped()) end(player);
            out[0] = beam.length(time) / LENGTH_BASE;
            out[1] = out[2] = 1f;
        }).writes("CylinderLength", (time, out) -> {
            out[0] = 200f * beam.length(time) / LENGTH_BASE;
            out[1] = out[2] = 0f;
        });
        playing.put(player, p);
        // The request ends only the action's listed beams, which a cut-in's never is: it runs to the action's end
        if (Boolean.TRUE.equals(a.get("bRequestEnd")) && !cutIns.contains(player)) requestsEnd.add(player);
    }

    /** {@code player}'s beam into its end phase, if it has one playing. */
    private void end(CgSequencePlayer player) {
        requestsEnd.remove(player);
        CgCuePlaying p = playing.remove(player);
        if (p != null && p.active()) p.remove();
    }

    @Override
    public void close() {
        sequencer.unlisten(Side.RPG, this);
        for (CgCuePlaying p : playing.values()) if (p.active()) p.remove();
        playing.clear();
        cutIns.clear();
        requestsEnd.clear();
    }

    /** The actor: its root where it was placed, its head flying out along its facing from the first frame it is asked. */
    private static final class Beam implements CgAttachment {
        private final double[] root = new double[3];
        private final Quaternionf turn = new Quaternionf();
        private final Vector3f forward = new Vector3f();
        private double born = Double.NaN, now;

        @Nullable
        static Beam at(CgAttachment socket, CgAttachment actor, @Nullable Object offset) {
            Beam b = new Beam();
            // The socket's place, the actor's facing (no target)
            if (!actor.place(b.root, b.turn) || !socket.place(b.root, null)) return null;
            // vecOffset is Unreal's (x forward, y right, z up) in cm: the host's (x, z, y) in metres, in the actor's frame
            Vector3f o = new Vector3f();
            if (offset instanceof Map<?, ?> m) {
                o.set(number(m.get("X")), number(m.get("Z")), number(m.get("Y"))).mul(0.01f);
            }
            b.turn.transform(o);
            b.root[0] += o.x;
            b.root[1] += o.y;
            b.root[2] += o.z;
            b.turn.transform(b.forward.set(1f, 0f, 0f));
            return b;
        }

        private static float number(@Nullable Object v) {
            return v instanceof Number n ? n.floatValue() : 0f;
        }

        /** Root to head at the frame's {@code time}: its first frame's InitTopOffset, then on at its speed. */
        float length(double time) {
            if (Double.isNaN(born)) born = time;
            now = time;
            return TOP_OFFSET + SPEED * (float) (time - born);
        }

        @Override
        public boolean place(double[] point, @Nullable Quaternionf turn) {
            System.arraycopy(root, 0, point, 0, 3);
            if (turn != null) turn.set(this.turn);
            return true;
        }

        @Override
        public boolean point(String name, double[] out) {
            System.arraycopy(root, 0, out, 0, 3);
            if (name.equals("ParticleTop")) {
                float d = Double.isNaN(born) ? TOP_OFFSET : TOP_OFFSET + SPEED * (float) (now - born);
                out[0] += forward.x * d;
                out[1] += forward.y * d;
                out[2] += forward.z * d;
                return true;
            }
            return name.equals("ParticleRoot");
        }
    }
}
