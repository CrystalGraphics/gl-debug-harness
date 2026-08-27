package io.github.somehussar.crystalgraphics.harness.scene.ui;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.Ui;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.ScrollerView;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.elements.slot.FluidSlot;
import com.crystalgui.ui.elements.slot.ItemSlot;
import com.crystalgui.ui.elements.slot.NativeContent;
import com.crystalgui.ui.elements.slot.NativeContentService;
import com.crystalgui.ui.elements.slot.NativeDescriptors;
import com.crystalgui.ui.elements.slot.NativeProfile;
import com.crystalgui.ui.elements.slot.NativeSurface;
import com.crystalgui.ui.input.FocusPolicy;
import dev.vfyjxf.taffy.style.FlexDirection;
import io.github.somehussar.crystalgraphics.harness.FrameInfo;
import io.github.somehussar.crystalgraphics.harness.InteractiveSceneLifecycle;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;
import org.lwjgl.opengl.GL11;

/**
 * {@code ItemSlot} / {@code FluidSlot} — the native-content seam, with a stand-in renderer.
 *
 * <p>The harness has no Minecraft, so it cannot draw a real item. What it <em>can</em> do is stand in for
 * one, and that turns out to cover almost everything worth covering: the scratch target, its viewport and
 * pixel sizing, the composite back through the pose, clipping, the layer-FBO path, depth, and the fill
 * geometry are all engine-side and none of them care whose renderer filled the box.</p>
 *
 * <h3>Raw GL here is the point, not a violation</h3>
 *
 * <p>Harness scenes are told never to call raw GL. This one does, in exactly one place —
 * {@link StandInService} — because it is impersonating the foreign fixed-function renderer the whole seam
 * exists to host. A stand-in drawn through {@code CgUiPaintContext} would exercise the engine's own path
 * and prove nothing about handing GL away.</p>
 *
 * <h3>Manual verification checklist</h3>
 * <ul>
 *   <li><b>Orientation.</b> Every filled slot draws a magenta square in its <b>top-left</b> quarter. Bottom-left
 *       means the composite's V flip is wrong — the one bug this pattern exists to make unmissable.</li>
 *   <li><b>Resolution.</b> The three sizes in row 1 (16/18/32px) are all crisp. Blurring on the large one
 *       means the scratch target was sized in logical units rather than device pixels.</li>
 *   <li><b>Depth.</b> Row 1's slots draw a NEAR teal quad first and a FAR orange quad second. Teal must
 *       stay on top. Orange winning means the {@code MODEL} target lost its depth attachment, which is
 *       what makes a real block item render inside-out.</li>
 *   <li><b>Fill.</b> Row 2's four tanks fill up / down / right / left at 25%, 50%, 75%, 100%.</li>
 *   <li><b>Clipping.</b> Row 3 scrolls; slots must be cut off at the scroller's edge, not drawn over it.</li>
 *   <li><b>Layers.</b> Row 4 sits in an {@code opacity: 0.45} parent — the slots must fade with it rather
 *       than punching through at full strength.</li>
 *   <li><b>The empty well</b> at the end of row 1 draws its background and no content.</li>
 *   <li><b>Press U</b> to swap the platform between the stand-in and {@code UNSUPPORTED}. Every slot should
 *       switch to its {@code __unsupported__} face and back, with nothing throwing.</li>
 * </ul>
 */
public class CgUiSlotScene implements InteractiveSceneLifecycle, CgSystemInput.Keyboard, CgSystemInput.Mouse {

    private UIWindow uiWindow;
    private boolean standInInstalled = true;

    private static final String STYLE_SHEET = """
            .demo-root {
                padding-all: 16px;
                flex-direction: column;
                gap-all: 14px;
                background-color: #FF1B1B22;
            }
            .row {
                flex-direction: row;
                gap-all: 8px;
                align-items: center;
            }
            .caption {
                color: #FFC8C8D0;
                font-size: 12;
            }
            /* The well. Geometry comes from ua/widgets.css; this is only the face, which is what a theme
               owns -- see ore.css for the shipped one. */
            itemslot, fluidslot {
                background-color: #FF2A2A34;
                border-width: 1px;
                border-color: #FF55555F;
            }
            itemslot.__unsupported__, fluidslot.__unsupported__ {
                background-color: #FF3A2020;
                border-color: #FF7A3030;
            }
            .big { width: 32px; height: 32px; }
            .small { width: 16px; height: 16px; }
            .tall { width: 18px; height: 48px; }
            .scroll-box {
                width: 150px;
                height: 40px;
                overflow: scroll;
                background-color: #FF15151B;
            }
            .faded { opacity: 0.45; }
            """;

    @Override
    public void init(HarnessContext ctx) {
        org.lwjgl.input.Keyboard.enableRepeatEvents(false);
        installStandIn();

        UIElement root = build();
        this.uiWindow = new UIWindow(Ui.of(root));
        this.uiWindow.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        this.uiWindow.getStyleEngine().addStylesheet(StyleSheet.parse(STYLE_SHEET));
    }

    private UIElement build() {
        UIElement root = new UIElement()
                .layout(l -> l.flexDirection(FlexDirection.COLUMN))
                .setFocusPolicy(FocusPolicy.NONE);
        root.addClass("demo-root");

        root.addChild(caption("Row 1 — sizes, depth, and an empty well (magenta marker must be TOP-LEFT)"));
        UIElement sizes = row();
        sizes.addChild(new ItemSlot().bind(item("small")).addClass("small"));
        sizes.addChild(new ItemSlot().bind(item("default")));
        sizes.addChild(new ItemSlot().bind(item("big")).addClass("big"));
        // Bound to nothing: the well draws, the content does not. Distinct from `__unsupported__`, which
        // is the platform declining rather than the slot being empty.
        sizes.addChild(new ItemSlot());
        root.addChild(sizes);

        root.addChild(caption("Row 2 — fluid fill: up 25%, down 50%, right 75%, left 100%"));
        UIElement tanks = row();
        tanks.addChild(tank(FluidSlot.FillDirection.BOTTOM_UP, 0.25f));
        tanks.addChild(tank(FluidSlot.FillDirection.TOP_DOWN, 0.5f));
        tanks.addChild(tank(FluidSlot.FillDirection.LEFT_RIGHT, 0.75f));
        tanks.addChild(tank(FluidSlot.FillDirection.RIGHT_LEFT, 1f));
        root.addChild(tanks);

        root.addChild(caption("Row 3 — inside a scroller: content must be CLIPPED at the edge"));
        ScrollerView scroller = new ScrollerView();
        scroller.addClass("scroll-box");
        UIElement strip = row();
        for (int i = 0; i < 12; i++) strip.addChild(new ItemSlot().bind(item("s" + i)));
        scroller.addChild(strip);
        root.addChild(scroller);

        root.addChild(caption("Row 4 — inside opacity: 0.45, which routes the subtree through a layer FBO"));
        UIElement faded = row();
        faded.addClass("faded");
        for (int i = 0; i < 4; i++) faded.addChild(new ItemSlot().bind(item("f" + i)));
        root.addChild(faded);

        return root;
    }

    private static UIElement row() {
        UIElement row = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW));
        row.addClass("row");
        return row;
    }

    private static UIElement caption(String text) {
        UIText label = new UIText(text);
        label.addClass("caption");
        return label;
    }

    private static FluidSlot tank(FluidSlot.FillDirection direction, float fill) {
        FluidSlot slot = new FluidSlot();
        slot.addClass("tall");
        slot.setFillDirection(direction);
        slot.bind(fluid(direction.name(), fill));
        return slot;
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        uiWindow.init(ctx.getScreenWidth(), ctx.getScreenHeight());
        uiWindow.paintFrame();

        CgUiPaintContext context = CgUiPaintContext.getInstance();
        String status = "platform: " + (standInInstalled ? "stand-in renderer" : "UNSUPPORTED")
                + "   (press U to swap)";
        context.text().draw().at(8, 4).text(status).font(context.getFont().atSize(14)).submit();

        if (frame.getFrameNumber() == 5) {
            ctx.getArtifactService().requestCapture("startup");
        }
    }

    @Override
    public void dispose() {
        // PUT IT BACK. The slot is process-wide static and the harness declared UNSUPPORTED for a reason;
        // leaving a stand-in installed would hand every later scene an item renderer that is not one.
        CgPlatform.provide(NativeContentService.SERVICE, NativeContentService.UNSUPPORTED);
        uiWindow = null;
    }

    private void installStandIn() {
        CgPlatform.provide(NativeContentService.SERVICE, new StandInService());
        standInInstalled = true;
    }

    @Override public boolean isRunning() { return true; }
    @Override public boolean uses3DCamera() { return false; }
    @Override public boolean shouldShutdownOnComplete() { return false; }

    @Override
    public boolean consumeKeyboardEvent(CgSystemInput.Keyboard.Event event) {
        if (event.key() == CgKeyCodes.KEY_U && event.pressed() && !event.repeat()) {
            if (standInInstalled) {
                CgPlatform.provide(NativeContentService.SERVICE, NativeContentService.UNSUPPORTED);
                standInInstalled = false;
            } else {
                installStandIn();
            }
            return true;
        }
        return uiWindow.getInputHandler().consumeKeyboardEvent(event);
    }

    @Override
    public boolean consumeMouseEvent(CgSystemInput.Mouse.Event event) {
        return uiWindow.getInputHandler().consumeMouseEvent(event);
    }

    // ── The stand-in ────────────────────────────────────────────────────────

    /**
     * Fixtures speak the real grammar. The ids are namespaced (`harness:small`) and the strings are
     * formatted by {@link NativeDescriptors}, because the whole point of the stand-in is to be a second
     * independent implementation of the cross-version contract -- a fixture the real grammar refuses
     * would be exercising a dialect no loader speaks.
     */
    private static NativeContent item(String path) {
        return content(NativeDescriptors.item("harness:" + path, 0, 1), NativeProfile.MODEL, 1f);
    }

    private static NativeContent fluid(String path, float fill) {
        // The descriptor's amount/capacity and the handle's fillFraction are the same fact spelled
        // twice; deriving one from the other keeps them from disagreeing.
        int amount = Math.round(Math.max(0f, Math.min(1f, fill)) * 1000f);
        return content(NativeDescriptors.fluid("harness:" + path, amount, 1000), NativeProfile.FLAT, fill);
    }

    private static NativeContent content(String descriptor, NativeProfile profile, float fill) {
        return new NativeContent() {
            @Override public String descriptor() { return descriptor; }
            @Override public NativeProfile profile() { return profile; }
            @Override public boolean isEmpty() { return false; }
            @Override public float fillFraction() { return fill; }
        };
    }

    /**
     * Impersonates a fixed-function host renderer.
     *
     * <p>Draws through {@code GL11} on purpose — see the class javadoc. It sets up its own projection for
     * the surface it was handed, exactly as {@code Mc1710NativeContentService} does, because that is the
     * contract being tested: the host is given a box and its size and nothing about CrystalGUI's
     * coordinate space.</p>
     */
    private static final class StandInService implements NativeContentService {

        @Override public boolean isAvailable() { return true; }

        @Override
        public NativeContent resolve(String descriptor) {
            // Parsed through the core grammar, exactly as Mc1710NativeContentService does -- this is
            // the drift detector: a loader spelling a descriptor its own way stops resolving HERE, in
            // a scene anyone can run in seconds, rather than on some other version's client.
            if (NativeDescriptors.parseSlot(descriptor) != null
                    || NativeDescriptors.parseItem(descriptor) != null) {
                return content(descriptor, NativeProfile.MODEL, 1f);
            }
            NativeDescriptors.FluidRef fluid = NativeDescriptors.parseFluid(descriptor);
            if (fluid != null) {
                return content(descriptor, NativeProfile.FLAT,
                        Math.min(1f, fluid.amount() / (float) fluid.capacity()));
            }
            return NativeContent.EMPTY;
        }

        @Override
        public void draw(NativeSurface surface, NativeContent content) {
            boolean model = surface.profile() == NativeProfile.MODEL;

            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPushMatrix();
            GL11.glLoadIdentity();
            GL11.glOrtho(0d, 16d, 16d, 0d, 1000d, 3000d);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPushMatrix();
            GL11.glLoadIdentity();
            GL11.glTranslatef(0f, 0f, -2000f);
            try {
                GL11.glDisable(GL11.GL_TEXTURE_2D);
                GL11.glDisable(GL11.GL_LIGHTING);
                GL11.glEnable(GL11.GL_BLEND);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

                if (model) {
                    GL11.glEnable(GL11.GL_DEPTH_TEST);
                    GL11.glDepthMask(true);
                    // NEAR first, then FAR -- and in this GUI ortho HIGHER z is NEARER, which is the same
                    // convention vanilla drives through RenderItem.zLevel. With a depth buffer the far
                    // quad loses and teal survives; without one it simply overwrites, which is exactly
                    // how a real block item comes out inside-out. Drawn in this order on purpose:
                    // near-then-far is the only sequence painter's order and depth disagree about.
                    quad(0f, 0f, 16f, 16f, 0.15f, 0.55f, 0.55f, 1f, 400f);
                    quad(0f, 0f, 16f, 16f, 0.85f, 0.45f, 0.10f, 1f, 0f);
                } else {
                    GL11.glDisable(GL11.GL_DEPTH_TEST);
                    GL11.glDepthMask(false);
                    quad(0f, 0f, 16f, 16f, 0.20f, 0.45f, 0.80f, 1f, 0f);
                }

                // ORIENTATION MARKER, top-left quarter. A V-flip in the composite moves it to the bottom,
                // which is the one failure that otherwise looks like a plausible picture.
                GL11.glDisable(GL11.GL_DEPTH_TEST);
                quad(1f, 1f, 6f, 6f, 1f, 0.15f, 0.9f, 1f, 0f);
            } finally {
                GL11.glPopMatrix();
                GL11.glMatrixMode(GL11.GL_PROJECTION);
                GL11.glPopMatrix();
                // MODELVIEW left active: PoseStack writes through glLoadMatrix and assumes it.
                GL11.glMatrixMode(GL11.GL_MODELVIEW);
            }
        }

        @Override
        public void drawTooltip(NativeContent content, float x, float y, int screenWidth, int screenHeight) {
            // Nothing: a tooltip is the one native draw with no offscreen target to isolate it, so a
            // stand-in for it would be drawing into the live frame to no purpose.
        }

        private static void quad(float x, float y, float w, float h,
                                 float r, float g, float b, float a, float depth) {
            GL11.glColor4f(r, g, b, a);
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glVertex3f(x, y + h, depth);
            GL11.glVertex3f(x + w, y + h, depth);
            GL11.glVertex3f(x + w, y, depth);
            GL11.glVertex3f(x, y, depth);
            GL11.glEnd();
            GL11.glColor4f(1f, 1f, 1f, 1f);
        }
    }
}
