package io.github.somehussar.crystalgraphics.harness.scene.ui;

import java.nio.charset.StandardCharsets;

import org.lwjgl.input.Keyboard;

import dev.vfyjxf.taffy.style.FlexDirection;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.app.uibuilder.BuilderCommands;
import com.crystalgui.app.uibuilder.canvas.UIBuilderView;
import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;
import com.crystalgui.widget.surface.insert.InsertMenu;
import com.crystalgui.widget.text.UIText;
import io.github.somehussar.crystalgraphics.harness.FrameInfo;
import io.github.somehussar.crystalgraphics.harness.InteractiveSceneLifecycle;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;
import io.github.somehussar.crystalgraphics.harness.util.HarnessThemes;

/**
 * The UI builder's Insert menu over a small page: opened under a selected row, then searched, then Tab-cycled.
 *
 * <p>For judging the menu — the placement band, the kind marks in their role colours, the search field, the footer
 * describing the highlighted offer — and the drop indicator on the page showing where a pick lands. Interactive
 * afterwards: Shift+Space on the canvas opens it again, right-click on blank page opens it at the pointer.</p>
 *
 * <p>Writes {@code browse}, {@code search}, {@code cycle} and {@code pointer} captures.</p>
 */
public class CgUiInsertMenuScene implements InteractiveSceneLifecycle, CgSystemInput.Keyboard, CgSystemInput.Mouse {

    private static final float SCALE = 2f;

    private UIDocument document;
    private UIBuilderView editor;
    private UIElement row;
    private Disposable commands;

    @Override
    public void init(HarnessContext ctx) {
        Keyboard.enableRepeatEvents(true);
        UIElementRegistry.bootstrap();
        commands = BuilderCommands.register();
        document = new UIDocument().markFrameThread();
        document.boxes().setUiScale(SCALE);
        HarnessThemes.install(document.styles(), "crystalgui:crystal-dark");

        UiBuilderDocument model = new UiBuilderDocument(
                UiBuilderDocument.EMPTY.getBytes(StandardCharsets.UTF_8), "harness:insert.cgui");
        UIElement column = new UIElement().layout(l -> l.width(220).paddingAll(8).gapAll(6));
        column.setId("profile");
        column.append(new UIText("Profile"));
        row = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(4).height(24));
        row.setId("actions");
        column.append(row);
        model.root().append(column);

        editor = new UIBuilderView(model);
        UIElement root = new UIElement();
        StyleGroup.defaultPipeline(root.getStyle().getLayoutGroup(), l -> l.widthPercent(100f).heightPercent(100f));
        root.append(editor.view());
        document.append(root);
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        int w = ctx.getScreenWidth();
        int h = ctx.getScreenHeight();
        long n = frame.getFrameNumber();
        if (n == 4) {
            editor.selection().selectOnly(row);
            editor.insert().openForSelection();
        }
        if (n == 14) ctx.getArtifactService().requestCapture("browse");
        if (n == 18) menu().searchBox().setText("but");
        if (n == 28) ctx.getArtifactService().requestCapture("search");
        if (n == 32) {
            menu().searchBox().setText("");
            editor.insert().cycle(1);
        }
        if (n == 42) ctx.getArtifactService().requestCapture("cycle");
        // A RIGHT-CLICK ON BLANK PAGE, by its route: the pointer's place, beside the column.
        if (n == 46) menu().hide();
        if (n == 48) editor.insert().openAtPointer(w * 0.45f, h * 0.35f);
        if (n == 56) ctx.getArtifactService().requestCapture("pointer");

        document.frame(frame.getDeltaTime(), w / SCALE, h / SCALE);
        CgUiPaintContext paint = CgUiPaintContext.getInstance();
        paint.beginFrame(w, h);
        document.paint(paint);
        paint.endFrame();
    }

    private InsertMenu menu() {
        return editor.surface().insertMenu();
    }

    @Override
    public void dispose() {
        if (commands != null) commands.dispose();
        document = null;
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
        return document.input().consumeKeyboardEvent(event);
    }

    @Override
    public boolean consumeMouseEvent(CgSystemInput.Mouse.Event event) {
        return document.input().consumeMouseEvent(event);
    }
}
