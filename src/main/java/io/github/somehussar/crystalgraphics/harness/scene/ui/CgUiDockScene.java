package io.github.somehussar.crystalgraphics.harness.scene.ui;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.style.sheet.StyleSheetRegistry;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.Ui;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.elements.dock.DockArea;
import com.crystalgui.ui.elements.dock.DockBranch;
import com.crystalgui.ui.elements.dock.DockCommands;
import com.crystalgui.ui.elements.dock.DockDropZone;
import com.crystalgui.ui.elements.dock.DockLayout;
import com.crystalgui.ui.elements.dock.DockLayoutCodec;
import com.crystalgui.ui.elements.dock.DockLeaf;
import com.crystalgui.ui.elements.dock.DockPanelDescriptor;
import com.crystalgui.ui.elements.dock.DockPanelRef;
import com.crystalgui.ui.elements.dock.DockPanelRegistry;
import com.crystalgui.ui.input.FocusPolicy;
import dev.vfyjxf.taffy.style.FlexDirection;
import io.github.somehussar.crystalgraphics.harness.FrameInfo;
import io.github.somehussar.crystalgraphics.harness.InteractiveSceneLifecycle;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;

/**
 * The docking workspace — drag tabs between panes, split, reorder, save and restore.
 *
 * <h3>What to do in here</h3>
 * <ul>
 *   <li><b>Drag a tab onto the middle of another pane</b> — it joins that pane's strip. This is the
 *       most-used drop in the whole system and the one an edge-zones-only implementation forgets.</li>
 *   <li><b>Drag a tab near a pane's edge</b> — it splits. The preview covers the half it would take.</li>
 *   <li><b>Drag a tab within its own strip</b> — a caret shows where it lands; it reorders, never splits.</li>
 *   <li><b>Drag to the very edge of the whole area</b> — a full-height column beside everything, which is
 *       the one thing VS Code's per-group targets cannot express.</li>
 *   <li><b>Ctrl+S / Ctrl+O</b> — serialise the arrangement and restore it, through the real codec.</li>
 *   <li><b>Ctrl+\ , Ctrl+W, Ctrl+M, Ctrl+K</b> — split, close, maximize, cycle groups, via commands.</li>
 * </ul>
 *
 * <p>Panels are deliberately trivial coloured boxes: what is being exercised is the layout tree and the
 * drop geometry, and a scene full of real editors would make a broken split look like a broken editor.</p>
 */
public class CgUiDockScene implements InteractiveSceneLifecycle, CgSystemInput.Keyboard, CgSystemInput.Mouse {

    private static final String STYLES = """
            /* Room for the harness's own status line, which is painted at y=0 over everything. */
            .demo-root { width: 100%; height: 100%; padding-all: 8px; padding-top: 22px; }
            .panel-body { flex-grow: 1; padding-all: 6px; }
            .p-graph  { background-color: #2F4858; }
            .p-code   { background-color: #33475B; }
            .p-nodes  { background-color: #3F3552; }
            .p-props  { background-color: #4A3B2E; }
            .p-console{ background-color: #2E3F2E; }
            """;

    private UIWindow uiWindow;
    private DockArea area;
    private DockPanelRegistry<UIElement> registry;

    /** The last saved arrangement, as the codec produced it. Null until Ctrl+S. */
    private Object saved;
    private String note = "drag a tab to begin";

    @Override
    public void init(HarnessContext ctx) {
        org.lwjgl.input.Keyboard.enableRepeatEvents(true);

        registry = new DockPanelRegistry<>();
        registry.register(DockPanelDescriptor.document("graph", "Shader Graph"), ref -> body("p-graph"));
        registry.register(DockPanelDescriptor.document("code", "main.glsl"), ref -> body("p-code"));
        registry.register(DockPanelDescriptor.singleton("nodes", "Node Library"), ref -> body("p-nodes"));
        registry.register(DockPanelDescriptor.singleton("props", "Inspector"), ref -> body("p-props"));
        registry.register(DockPanelDescriptor.singleton("console", "Console"), ref -> body("p-console"));

        area = new DockArea(registry, defaultLayout());

        UIElement root = new UIElement()
                .layout(l -> l.flexDirection(FlexDirection.COLUMN))
                .setFocusPolicy(FocusPolicy.NONE);
        root.addClass("demo-root");
        root.addChild(area);

        uiWindow = new UIWindow(Ui.of(root));
        uiWindow.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        uiWindow.getStyleEngine().addStylesheet(StyleSheetRegistry.of("crystalgui:ore"));
        uiWindow.getStyleEngine().addStylesheet(StyleSheet.parse(STYLES));
        DockCommands.install(uiWindow);
    }

    /**
     * The arrangement an IDE opens on: a library down the left, the work area in the middle with two
     * documents sharing a strip, an inspector on the right, and a console beneath.
     *
     * <p>The work area is the <b>central</b> leaf — it cannot be closed or absorbed, which is what stops
     * the layout being reducible to nothing but tool panels.</p>
     */
    private DockLayout defaultLayout() {
        DockLeaf centre = new DockLeaf(new DockPanelRef("graph"), new DockPanelRef("code"));
        centre.setCentral(true);
        DockLayout layout = DockLayout.of(centre);

        layout.drop(centre, DockDropZone.SPLIT_LEFT, new DockLeaf(new DockPanelRef("nodes")));
        layout.drop(centre, DockDropZone.SPLIT_RIGHT, new DockLeaf(new DockPanelRef("props")));
        layout.drop(centre, DockDropZone.SPLIT_DOWN, new DockLeaf(new DockPanelRef("console")));

        // Weights, explicitly -- and worth understanding rather than copying.
        //
        // A split halves the TARGET's share and gives the other half to the newcomer, which is right (every
        // OTHER pane keeps the proportion the user gave it) and means three splits off the same centre pane
        // leave it at an eighth. Insertion order then decides the picture: the first thing split off is the
        // biggest, so building an IDE layout in the obvious order hands half the screen to the node library.
        //
        // The lesson is that a DEFAULT layout is authored, not accumulated. A user's layout comes out of
        // their drags and needs no help; a starting one has to state what it wants.
        layout.root().child(0).size(0.20f);   // node library
        layout.root().child(1).size(0.62f);   // the work column
        layout.root().child(2).size(0.18f);   // inspector

        DockBranch workColumn = (DockBranch) layout.root().child(1);
        workColumn.child(0).size(0.75f);      // documents
        workColumn.child(1).size(0.25f);      // console
        return layout;
    }

    private UIElement body(String cssClass) {
        UIElement element = new UIElement();
        element.addClass("panel-body");
        element.addClass(cssClass);
        return element;
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        uiWindow.init(ctx.getScreenWidth(), ctx.getScreenHeight());
        uiWindow.paintFrame();

        var context = CgUiPaintContext.getInstance();
        context.text().draw().at(0, 14)
                .text(diagnose())
                .font(context.getFont().atSize(12)).submit();
        context.text().draw().at(0, 0)
                .text(String.format("Dock — %d panes.  Ctrl+S save · Ctrl+O restore · Ctrl+\\ split · "
                                + "Ctrl+W close · Ctrl+M maximize · Ctrl+K next group   [%s]",
                        area.layout().leaves().size(), note))
                .font(context.getFont().atSize(14)).submit();

        if (frame.getFrameNumber() == 5) {
            ctx.getArtifactService().requestCapture("startup");
        }
    }

    /**
     * Reports the view against the model, every frame.
     *
     * <p>Here rather than in a test because the failure has not reproduced headlessly: a two-group drag
     * and a drag in this exact layout both come out clean, so whatever differs is something only the real
     * loop does. This says what is actually true on screen instead of what a screenshot suggests.</p>
     */
    private String diagnose() {
        int leaves = area.layout().leaves().size();
        int attached = countGroups(area);
        StringBuilder mismatches = new StringBuilder();
        for (var leaf : area.layout().leaves()) {
            var group = area.groupFor(leaf);
            if (group == null) {
                mismatches.append(" [no group]");
                continue;
            }
            int tabs = group.tabView().getTabCount();
            if (tabs != leaf.panelCount()) {
                mismatches.append(String.format(" [%s: %d tabs vs %d panels]",
                        leaf.panelCount() > 0 ? leaf.panel(0).typeId() : "empty", tabs, leaf.panelCount()));
            }
        }
        return String.format("groups attached=%d leaves=%d%s", attached, leaves,
                mismatches.length() == 0 ? "  strips OK" : "  MISMATCH" + mismatches);
    }

    private static int countGroups(UIElement element) {
        int count = element instanceof com.crystalgui.ui.elements.dock.DockGroup ? 1 : 0;
        for (UIElement child : element.getChildren()) count += countGroups(child);
        return count;
    }

    @Override
    public void dispose() {
        uiWindow = null;
    }

    @Override
    public boolean isRunning() {
        return true;
    }

    @Override
    public boolean uses3DCamera() {
        return false;
    }

    @Override
    public boolean shouldShutdownOnComplete() {
        return false;
    }

    @Override
    public boolean consumeKeyboardEvent(CgSystemInput.Keyboard.Event event) {
        if (event.pressed()) {
            boolean ctrl = org.lwjgl.input.Keyboard.isKeyDown(org.lwjgl.input.Keyboard.KEY_LCONTROL)
                    || org.lwjgl.input.Keyboard.isKeyDown(org.lwjgl.input.Keyboard.KEY_RCONTROL);
            if (ctrl && event.key() == org.lwjgl.input.Keyboard.KEY_S) {
                // Read the user's divider positions back out first, or the save records the weights the
                // layout was BUILT with rather than the ones on screen.
                area.pullWeightsIntoLayout();
                saved = DockLayoutCodec.encode(area.layout(), PlainOps.INSTANCE,
                        uiWindow.getScreenWidth(), uiWindow.getScreenHeight());
                note = "saved";
                return true;
            }
            if (ctrl && event.key() == org.lwjgl.input.Keyboard.KEY_O) {
                if (saved == null) {
                    note = "nothing saved yet";
                    return true;
                }
                DockLayout restored = DockLayoutCodec.decode(saved, PlainOps.INSTANCE, registry);
                if (restored == null) {
                    // The codec's honest answer, and a normal outcome rather than an error path.
                    area.setLayout(defaultLayout());
                    note = "blob refused — default layout";
                } else {
                    area.setLayout(restored);
                    note = "restored";
                }
                return true;
            }
        }
        return uiWindow.getInputHandler().consumeKeyboardEvent(event);
    }

    @Override
    public boolean consumeMouseEvent(CgSystemInput.Mouse.Event event) {
        return uiWindow.getInputHandler().consumeMouseEvent(event);
    }
}
