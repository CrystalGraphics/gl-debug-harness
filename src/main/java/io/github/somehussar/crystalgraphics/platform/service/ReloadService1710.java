package io.github.somehussar.crystalgraphics.platform.service;

//import com.crystalgraphics.mc.CgAssetReloader;
import com.crystalgraphics.api.material.CgMaterialRegistry;
import com.crystalgraphics.gl.material.CgMaterialShaderRegistry;
import com.crystalgraphics.gl.texture.CgTextureManager;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgReloadService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class ReloadService1710 implements CgReloadService {

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");

    @Override
    public void onReload() {
        reload();
    }

    public static void reload() {
        reloadTextures();
        reloadShaders();
        reloadMaterials();
    }

    private static void reloadTextures() {
        try {
            CgTextureManager.get().reloadAll();
        } catch (Exception e) {
            LOGGER.error("Failed to reload textures", e);
        }
    }

    private static void reloadShaders() {
    }

    private static void reloadMaterials() {
        try {
            CgMaterialRegistry.get().reloadAll();
            CgMaterialShaderRegistry.get().reloadAll();
        } catch (Exception e) {
            LOGGER.error("Failed to reload materials", e);
        }
    }
    /**
     * Attach a reload listener to the current Minecraft resource manager so that
     * F3+T / resource pack changes trigger {@link CgPlatform#reload()}.
     * Must be called on the client thread after Minecraft has initialised its resource manager.
     */
    public static void attachToResourceManager() {
//        IResourceManager rm = Minecraft.getMinecraft().getResourceManager();
//        if (rm instanceof IReloadableResourceManager) {
//            ((IReloadableResourceManager) rm).registerReloadListener(
//                resourceManager -> CgPlatform.reload().onReload());
//        } else {
//            LOGGER.warn("[CrystalGraphics] Resource manager is not reloadable — hot-reload listener not registered");
//        }
    }
}
