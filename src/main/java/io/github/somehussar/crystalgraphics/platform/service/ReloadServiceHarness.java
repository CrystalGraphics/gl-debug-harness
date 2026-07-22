package io.github.somehussar.crystalgraphics.platform.service;

import com.crystalgraphics.mc.CgAssetReloader;
import com.crystalgraphics.platform.service.CgReloadService;

public final class ReloadServiceHarness implements CgReloadService {
    
    @Override
    public void onReload() {
        CgAssetReloader.reload();
    }
}
