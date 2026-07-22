package io.github.somehussar.crystalgraphics.platform.service;

import com.crystalgraphics.platform.service.CgResourceService;

import java.io.InputStream;


public final class ResourceServiceHarness implements CgResourceService {

    @Override
    public InputStream openStream(String domain, String path) {
        try {
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
