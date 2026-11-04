package com.crystalgraphics.harness;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * What a project built on CrystalGraphics adds to the harness: its scenes, a step for Ctrl+R, and the shader
 * namespaces it ships. The harness itself names no such project, so this is how one's scenes get in.
 *
 * <pre>{@code
 * public final class MyHarness implements HarnessExtension {
 *     @Override public void registerScenes(SceneRegistry registry) {
 *         registry.register(
 *                 SceneDescriptor.builder("my-scene")
 *                         .description("What it shows")
 *                         .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
 *                         .category(SceneDescriptor.Category.SCENE)
 *                         .build(),
 *                 MyScene::new);
 *     }
 *
 *     @Override public List<String> shaderNamespaces() { return List.of("mymod"); }
 * }
 * }</pre>
 *
 * <p>Listed in {@code META-INF/services/com.crystalgraphics.harness.HarnessExtension}, and on the
 * {@code runHarness} classpath: the host build adds its module there, since the harness cannot depend on it.</p>
 *
 * <ul>
 *   <li>A scene id must be unique across the harness and every extension; a duplicate throws at startup.</li>
 *   <li>{@link #beforeReload} runs before CrystalGraphics' own asset reload, which then calls every
 *       {@code CgReloadListener}. Anything a listener already re-reads does not belong here.</li>
 * </ul>
 */
public interface HarnessExtension {

    /** Adds this project's scenes. Called once, after the harness's own. */
    void registerScenes(SceneRegistry registry);

    /** Ctrl+R: re-read what must be current before the asset reload runs. */
    default void beforeReload() {
    }

    /** Namespaces whose {@code assets/<ns>/shaders/*.shader} the {@code shader-compile-audit} mode checks. */
    default List<String> shaderNamespaces() {
        return List.of();
    }

    /**
     * Work this project starts at launch on threads of its own, GL-free (a UI library's registries, its parsed sheets):
     * called at pre-init, and the scene's {@code init} waits behind the loading screen until it ends, so the scene
     * never pays it on the render thread.
     *
     * <pre>{@code
     * @Override public CompletableFuture<?> start() { return MyUi.startAhead(); }
     * }</pre>
     */
    default CompletableFuture<?> start() {
        return CompletableFuture.completedFuture(null);
    }
}
