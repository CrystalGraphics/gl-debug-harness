package io.github.somehussar.crystalgraphics.harness.scene;

import com.crystalgraphics.api.mesh.CgMeshData;
import com.crystalgraphics.api.shader.CgShader;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.gl.mesh.*;
import io.github.somehussar.crystalgraphics.gl.mesh.*;
import com.crystalgraphics.gl.shader.CgShaderFactory;
import io.github.somehussar.crystalgraphics.harness.FrameInfo;
import io.github.somehussar.crystalgraphics.harness.InteractiveSceneLifecycle;
import io.github.somehussar.crystalgraphics.harness.camera.Camera3D;
import io.github.somehussar.crystalgraphics.harness.capture.ArtifactService;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;
import io.github.somehussar.crystalgraphics.harness.config.ViewportState;
import io.github.somehussar.crystalgraphics.harness.object.WorldAxisRenderer;
import io.github.somehussar.crystalgraphics.harness.scheduler.TaskScheduler;

import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Interactive 3D scene that exercises all {@code CgMeshBuilder} shapes plus
 * OBJ and glTF/GLB loading.
 *
 * <h3>Meshes displayed</h3>
 * <ol>
 *   <li>unitCube at (-6, 0, 0)</li>
 *   <li>quad2D at (-3, 0, 0)</li>
 *   <li>plane 4×4 subdivisions at (0, 0, 0)</li>
 *   <li>uvSphere 16 rings × 16 sectors at (3, 0, 0)</li>
 *   <li>icosahedron 1 subdivision at (6, 0, 0)</li>
 *   <li>OBJ from {@code /meshes/test_model.obj} at (-4.5, 2, 0)</li>
 *   <li>GLB from {@code /meshes/test_model.glb} at (4.5, 2, 0)</li>
 * </ol>
 *
 * <h3>Shader modes</h3>
 * <p>All meshes use the same shader program. Uniform {@code u_mode} selects:</p>
 * <ul>
 *   <li>0 — vertex color (from packed a_color)</li>
 *   <li>1 — UV checkerboard grid</li>
 *   <li>2 — solid white (useful for silhouette inspection)</li>
 * </ul>
 *
 * <h3>Capture sequence</h3>
 * <ol>
 *   <li>t=1.0s — {@code "overview"} — initial camera angle showing all meshes</li>
 *   <li>t=1.5s — {@code "closeup"} — camera at (0, 1.5, 5) for center meshes</li>
 *   <li>t=2.0s — scene exits</li>
 * </ol>
 */
public class MeshTestScene implements InteractiveSceneLifecycle {

    private static final Logger LOGGER = Logger.getLogger(MeshTestScene.class.getName());

    // ── Shader source — matches CgVertexFormat.POS3_UV2_COL4UB layout ──
    // location 0: vec3 a_pos
    // location 1: vec2 a_uv
    // location 2: vec4 a_color  (4 ubytes, normalized)

    private static final String VERT_SRC =
            "#version 330 core\n" +
                    "uniform mat4 u_model;\n" +
                    "uniform mat4 u_view;\n" +
                    "uniform mat4 u_projection;\n" +
                    "in vec3 a_pos;\n" +
                    "in vec2 a_uv;\n" +
                    "in vec4 a_color;\n" +
                    "out vec2 v_uv;\n" +
                    "out vec4 v_color;\n" +
                    "void main() {\n" +
                    "    gl_Position = u_projection * u_view * u_model * vec4(a_pos, 1.0);\n" +
                    "    v_uv = a_uv;\n" +
                    "    v_color = a_color;\n" +
                    "}\n";

    private static final String FRAG_SRC =
            "#version 330 core\n" +
                    "uniform int u_mode;\n" +
                    "in vec2 v_uv;\n" +
                    "in vec4 v_color;\n" +
                    "out vec4 fragColor;\n" +
                    "void main() {\n" +
                    "    if (u_mode == 1) {\n" +
                    "        // UV checkerboard grid (8x8 squares)\n" +
                    "        vec2 grid = floor(v_uv * 8.0);\n" +
                    "        float checker = mod(grid.x + grid.y, 2.0);\n" +
                    "        fragColor = mix(vec4(0.2, 0.2, 0.2, 1.0), vec4(0.9, 0.9, 0.9, 1.0), checker);\n" +
                    "    } else if (u_mode == 2) {\n" +
                    "        // Solid white\n" +
                    "        fragColor = vec4(1.0, 1.0, 1.0, 1.0);\n" +
                    "    } else {\n" +
                    "        // Vertex color (mode 0, default)\n" +
                    "        fragColor = v_color;\n" +
                    "    }\n" +
                    "}\n";

    private boolean running = true;
    private HarnessContext ctx;

    // ── GL handles ──
    private CgShader program = CgShaderFactory.fromSource(VERT_SRC, FRAG_SRC);

    // ── Mesh slots — null if build/load failed ──
    private CgMesh meshUnitCube;
    private CgMesh meshQuad2D;
    private CgMesh meshPlane;
    private CgMesh meshUvSphere;
    private CgMesh meshIcosahedron;
    private CgMesh meshIcosahedron1;
    private CgMesh meshIcosahedron2;
    private CgMesh meshObj;
    private CgMesh meshGltf;

    // ── Reused FloatBuffer for matrix uploads ──
    private final FloatBuffer matBuf = BufferUtils.createFloatBuffer(16);

    WorldAxisRenderer axis = new WorldAxisRenderer();

    @Override
    public void init(HarnessContext ctx) {
        this.ctx = ctx;
        axis.init(ctx);

        // ── Build procedural meshes ──
        meshUnitCube = buildMesh("unitCube", buildUnitCube());
        meshQuad2D = buildMesh("quad2D", buildQuad2D());
        meshPlane = buildMesh("plane", buildPlane());
        meshUvSphere = buildMesh("uvSphere", buildUvSphere());
        meshIcosahedron = buildMesh("icosahedron", buildIcosahedron(0));
        meshIcosahedron1 = buildMesh("icosahedron", buildIcosahedron(1));
        meshIcosahedron1 = buildMesh("icosahedron", buildIcosahedron(2));

        // ── Load OBJ ──
        meshObj = loadObj("/meshes/test_model.obj");
        meshGltf = loadGltf("/meshes/test_model.glb");


        // ── Log summary ──
        logSummary();

        // ── Schedule captures and shutdown ──
        scheduleCapturesAndShutdown();

        LOGGER.info("[MeshTestScene] Initialized. " +
                ctx.getTaskScheduler().pendingCount() + " tasks scheduled.");
    }

    // ── Render ───────────────────────────────────────────────────────────────

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        ViewportState vp = ctx.getViewport();
        Matrix4f view = ctx.getCamera3D().getViewMatrix();

        axis.render(ctx);
        program.bindings().mat4("u_view", view).mat4("u_projection", ctx.getProjection());

        CgMesh iso2 = CgMeshRegistry.get().getOrCreate("iso4", ()->
                CgMeshBuilder.icosahedron(CgVertexFormat.POS3_UV2_COL4UB, 4).upload());

        // Upload view + projection (same for all meshes)
        // Render each mesh at its world position
        // Mode 1 (UV checkerboard) for procedural shapes, mode 0 (vertex color) for loaded meshes
        renderMesh(meshUnitCube, -6.0f, 0.0f, 0.0f, 1);
        renderMesh(meshQuad2D, -3.0f, 0.0f, 0.0f, 1);
        renderMesh(meshPlane, 0.0f, -2.0f, 0.0f, 1);
        renderMesh(meshUvSphere, 0.0f, 2.0f, 0.0f, 1);
        GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK,GL11.GL_FILL);
        renderMesh(iso2, 0.0f, 0.0f, -4.0f, 1);
        renderMesh(meshObj, -1.5f, 0.0f, 0.0f, 1);
        renderMesh(meshGltf, 4.5f, 2.0f, 0.0f, 1);

        GL20.glUseProgram(0);
    }

    // ── Procedural mesh builders ─────────────────────────────────────────────

    private static CgMeshData buildUnitCube() {
        return CgMeshBuilder.unitCube(CgVertexFormat.POS3_UV2_COL4UB);
    }

    private static CgMeshData buildQuad2D() {
        return CgMeshBuilder.quad2D(CgVertexFormat.POS3_UV2_COL4UB,
                -0.5f, -0.5f, 0.5f, 0.5f);
    }

    private static CgMeshData buildPlane() {
        return CgMeshBuilder.plane(CgVertexFormat.POS3_UV2_COL4UB,
                4, 4, 2.0f, 2.0f);
    }

    private static CgMeshData buildUvSphere() {
        return CgMeshBuilder.uvSphere(CgVertexFormat.POS3_UV2_COL4UB,
                16, 16, 0.8f);
    }

    private static CgMeshData buildIcosahedron(int sub) {
        return CgMeshBuilder.icosahedron(CgVertexFormat.POS3_UV2_COL4UB, sub);
    }

    // ── Mesh upload helpers ──────────────────────────────────────────────────

    /**
     * Uploads a {@link CgMeshData} to a {@link CgMesh}, logging success or failure.
     *
     * @param name label for logging
     * @param data CPU mesh data (must be non-null)
     * @return the uploaded GPU mesh, or {@code null} on error
     */
    private static CgMesh buildMesh(String name, CgMeshData data) {
        try {
            CgMesh mesh = CgMesh.upload(data);
            LOGGER.info(String.format("[MeshTestScene]   %-14s OK (%dv %di)",
                    name, data.getVertexCount(), data.indexCount()));
            return mesh;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE,
                    "[MeshTestScene]   " + name + " FAILED to upload: " + e.getMessage(), e);
            return null;
        }
    }

    // ── OBJ / GLTF loaders ──────────────────────────────────────────────────

    private static CgMesh loadObj(String resourcePath) {
        InputStream stream = MeshTestScene.class.getResourceAsStream(resourcePath);
        if (stream == null) {
            LOGGER.warning("[MeshTestScene]   OBJ  FAILED: resource not found: " + resourcePath);
            return null;
        }
        try {
            CgMeshData data = CgObjLoader.load(stream, CgVertexFormat.POS3_UV2_COL4UB);
            CgMesh mesh = CgMesh.upload(data);
            LOGGER.info(String.format("[MeshTestScene]   OBJ          OK (%dv %di)",
                    data.getVertexCount(), data.indexCount()));
            return mesh;
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE,
                    "[MeshTestScene]   OBJ  FAILED: " + e.getMessage(), e);
            return null;
        } finally {
            try {stream.close();} catch (IOException ignored) {}
        }
    }

    private static CgMesh loadGltf(String resourcePath) {
        InputStream stream = MeshTestScene.class.getResourceAsStream(resourcePath);
        if (stream == null) {
            LOGGER.warning("[MeshTestScene]   GLB  FAILED: resource not found: " + resourcePath);
            return null;
        }
        try {
            CgMeshData data = CgGltfLoader.loadFirstPrimitive(stream, CgVertexFormat.POS3_UV2_COL4UB);
            CgMesh mesh = CgMesh.upload(data);
            LOGGER.info(String.format("[MeshTestScene]   GLB          OK (%dv %di)",
                    data.getVertexCount(), data.indexCount()));
            return mesh;
        } catch (UnsupportedOperationException e) {
            LOGGER.log(Level.WARNING,
                    "[MeshTestScene]   GLB  FAILED (skinned mesh): " + e.getMessage(), e);
            return null;
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE,
                    "[MeshTestScene]   GLB  FAILED: " + e.getMessage(), e);
            return null;
        } finally {
            try {stream.close();} catch (IOException ignored) {}
        }
    }

    // ── Summary logging ──────────────────────────────────────────────────────

    private void logSummary() {
        LOGGER.info("[MeshTestScene] Initialized. Meshes loaded:");
        logMeshStatus("unitCube", meshUnitCube);
        logMeshStatus("quad2D", meshQuad2D);
        logMeshStatus("plane", meshPlane);
        logMeshStatus("uvSphere", meshUvSphere);
        logMeshStatus("icosahedron", meshIcosahedron);
        logMeshStatus("OBJ", meshObj);
        logMeshStatus("GLB", meshGltf);
    }

    private static void logMeshStatus(String name, CgMesh mesh) {
        if (mesh != null) {
            LOGGER.info(String.format("[MeshTestScene]   %-14s OK (%dv %di)",
                    name, mesh.getVertexCount(), mesh.getIndexCount()));
        } else {
            LOGGER.info("[MeshTestScene]   " + name + " FAILED or not loaded");
        }
    }

    // ── Capture scheduling ───────────────────────────────────────────────────

    private void scheduleCapturesAndShutdown() {
        final TaskScheduler scheduler = ctx.getTaskScheduler();
        final ArtifactService artifacts = ctx.getArtifactService();
        final Camera3D camera = ctx.getCamera3D();

        // t=1.0s — overview from initial camera position
        scheduler.schedule(1.0, "capture-overview", new Runnable() {
            @Override
            public void run() {
                artifacts.requestCapture("overview");
            }
        });

        // t=1.5s — move camera closer for center meshes, capture
        scheduler.schedule(1.5, "capture-closeup", new Runnable() {
            @Override
            public void run() {
                camera.moveCamera(0.0f, 1.5f, 5.0f);
                camera.setYaw(0.0f);
                camera.setPitch(-5.0f);
                artifacts.requestCapture("closeup");
            }
        });

        // t=2.0s — exit
        scheduler.schedule(2.0, "shutdown", new Runnable() {
            @Override
            public void run() {
                LOGGER.info("[MeshTestScene] All captures done. Shutting down.");
                // running = false;
            }
        });
    }

    /**
     * Renders a single mesh at the given world translation with the given shader mode.
     * Skips silently if the mesh slot is {@code null}.
     */
    private void renderMesh(CgMesh mesh, float tx, float ty, float tz, int mode) {
        if (mesh == null) return;

        Matrix4f model = new Matrix4f().translate(tx, ty, tz);
        program.bindings().mat4("u_model", model).set1i("u_mode", mode);
        program.bind();

        mesh.drawDirect();
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    public void dispose() {
        deleteMeshSafe(meshUnitCube);
        deleteMeshSafe(meshQuad2D);
        deleteMeshSafe(meshPlane);
        deleteMeshSafe(meshUvSphere);
        deleteMeshSafe(meshIcosahedron);
        deleteMeshSafe(meshObj);
        deleteMeshSafe(meshGltf);

        program.delete();
        LOGGER.info("[MeshTestScene] Disposed.");
    }

    private static void deleteMeshSafe(CgMesh mesh) {
        if (mesh != null) {
            mesh.delete();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean uses3DCamera() {
        return true;
    }

    @Override
    public boolean shouldShutdownOnComplete() {
        return false;
    }
}
