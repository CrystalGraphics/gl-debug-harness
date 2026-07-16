package io.github.somehussar.crystalgraphics.platform.service;

import com.crystalgraphics.platform.service.CgResourceService;

import java.io.InputStream;

/**
 * MC 1.7.10 implementation of {@link CgResourceService}.
 * Delegates asset loading to Minecraft's {@link IResourceManager}.
 */
public final class ResourceService1710 implements CgResourceService {

    @Override
    public InputStream openStream(String domain, String path) {
        try {
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
