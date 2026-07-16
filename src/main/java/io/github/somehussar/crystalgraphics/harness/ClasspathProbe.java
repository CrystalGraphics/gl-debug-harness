package io.github.somehussar.crystalgraphics.harness;

import com.crystalgraphics.gl.texture.CgTextureManager;
import com.crystalgraphics.api.material.CgMaterialRegistry;

public class ClasspathProbe {
    public static void probe() {
        CgTextureManager.get();
        CgMaterialRegistry.get();
    }
}