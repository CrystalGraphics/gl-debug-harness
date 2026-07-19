package io.github.somehussar.crystalgraphics.platform.input;

import com.crystalgui.core.input.CgUiInputAdapter;
import com.crystalgui.core.input.Modifiers;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

public class InputAdapter implements CgUiInputAdapter {
    @Override
    public int getCurrentModifiers() {
        int mods = 0;
        if (Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT))
            mods |= Modifiers.SHIFT;
        if (Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL))
            mods |= Modifiers.CTRL;
        if (Keyboard.isKeyDown(Keyboard.KEY_LMENU) || Keyboard.isKeyDown(Keyboard.KEY_RMENU))
            mods |= Modifiers.ALT;
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

    @Override
    public boolean isMouseDown(int localMouseCode) {
        return Mouse.isButtonDown(localMouseCode);
    }
}
