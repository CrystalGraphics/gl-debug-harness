package io.github.somehussar.crystalgraphics.platform.input;

import com.crystalgraphics.platform.service.CgInputService;
import com.crystalgraphics.platform.input.CgModifiers;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;

/**
 * The harness's LWJGL2 input service. Key and mouse codes are already LWJGL2-shaped, so every
 * translation here is the identity.
 *
 * <p>The clipboard half goes through <b>AWT rather than LWJGL2</b>: {@code Sys.getClipboard()} can only
 * read, and Ctrl+X / Ctrl+C need to write. Every clipboard call is wrapped, because access can fail for
 * reasons entirely outside this process — another application owning it, a locked session — and an empty
 * read or a dropped write is the right degradation for a debug harness.</p>
 */
public class InputAdapter implements CgInputService {
    @Override
    public int getCurrentModifiers() {
        int mods = 0;
        if (Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT))
            mods |= CgModifiers.SHIFT;
        if (Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL))
            mods |= CgModifiers.CTRL;
        if (Keyboard.isKeyDown(Keyboard.KEY_LMENU) || Keyboard.isKeyDown(Keyboard.KEY_RMENU))
            mods |= CgModifiers.ALT;
        return mods;
    }

    @Override
    public int translateKeyboardCodes(int platformCode) {
        return platformCode;
    }

    @Override
    public boolean isKeyDown(int localKeyCode) {
        return Keyboard.isKeyDown(localKeyCode);
    }

    /** LWJGL2 button ids are already {@code CgMouseCodes} values. */
    @Override
    public int translateMouseCodes(int platformCode) {
        return platformCode;
    }

    @Override
    public boolean isMouseDown(int localMouseCode) {
        return Mouse.isButtonDown(localMouseCode);
    }

    @Override
    public int howManyMouseButtons() {
        return Mouse.getButtonCount();
    }

    @Override
    public String getClipboard() {
        try {
            var contents = Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null);
            if (contents != null && contents.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                return (String) contents.getTransferData(DataFlavor.stringFlavor);
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    @Override
    public void setClipboard(String text) {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(text), null);
        } catch (Exception ignored) {
        }
    }
}
