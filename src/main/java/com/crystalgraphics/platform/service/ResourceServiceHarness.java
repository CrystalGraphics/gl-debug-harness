package com.crystalgraphics.platform.service;

import com.crystalgraphics.platform.service.CgResourceService;

import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Set;


public final class ResourceServiceHarness implements CgResourceService {

    @Override
    public InputStream openStream(String domain, String path) {
        try {
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** The harness reads its assets from the classpath through CgIO: no packs to stack. */
    @Override
    public List<InputStream> openStack(String domain, String path) {
        return Collections.emptyList();
    }

    @Override
    public Set<String> namespaces() {
        return Collections.emptySet();
    }
}
