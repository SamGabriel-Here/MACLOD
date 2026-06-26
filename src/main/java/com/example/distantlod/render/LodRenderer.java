package com.example.distantlod.render;

import com.example.distantlod.data.ChunkLodStore;
import com.example.distantlod.data.DistantLodStore;
import com.example.distantlod.data.LodChunkData;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.joml.Matrix4f;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Renders two proof-of-concept LOD layers:
 *
 * <ul>
 *   <li>a small translucent near overlay built from live loaded chunks, and</li>
 *   <li>an opaque distant overlay built from singleplayer region files on disk.</li>
 * </ul>
 *
 * The important milestone here is that per-chunk geometry is uploaded once into
 * Minecraft {@link VertexBuffer}s and reused between frames. That keeps this
 * macOS-friendly (Minecraft owns the GL objects) while avoiding the worst part
 * of the first prototype: rebuilding every quad every frame.
 */
public final class LodRenderer {

    /** Cap how many chunk-rings around the camera get the live debug overlay. */
    private static final int DEBUG_RENDER_RADIUS_CHUNKS = 3;
    /** Lift the debug mesh above the real terrain so it is visually distinct. */
    private static final float DEBUG_HEIGHT_OFFSET = 6.0f;
    /** Translucent so real terrain stays visible underneath. */
    private static final float DEBUG_ALPHA = 0.55f;

    /** Minimum disk-read radius, in chunks, when vanilla render distance is low. */
    private static final int DISTANT_MIN_RADIUS_CHUNKS = 20;
    /** Additional chunks beyond vanilla render distance to read from disk. */
    private static final int DISTANT_EXTRA_CHUNKS = 4;
    /** Safety cap while this prototype still uses one mesh per chunk. */
    private static final int DISTANT_MAX_RADIUS_CHUNKS = 36;
    /** Avoid flooding the region-reader queue in one render tick. */
    private static final int MAX_DISTANT_REQUESTS_PER_FRAME = 32;
    private static final float DISTANT_HEIGHT_OFFSET = 4.0f;
    private static final float DISTANT_ALPHA = 0.95f;

    private static final Map<Long, CachedChunkMesh> NEAR_MESHES = new ConcurrentHashMap<>();
    private static final Map<Long, CachedChunkMesh> DISTANT_MESHES = new ConcurrentHashMap<>();
    private static volatile DebugStats lastStats = DebugStats.empty();

    private LodRenderer() {}

    public static void register() {
        WorldRenderEvents.AFTER_TRANSLUCENT.register(LodRenderer::onAfterTranslucent);
        HudRenderCallback.EVENT.register(LodRenderer::onHudRender);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            clearMeshes();
            lastStats = DebugStats.empty();
        });
    }

    private static void onAfterTranslucent(WorldRenderContext context) {
        MatrixStack matrices = context.matrixStack();
        Camera camera = context.camera();
        if (matrices == null || camera == null) {
            return;
        }
        Vec3d cam = camera.getPos();
        int camChunkX = MathHelper.floor(cam.x) >> 4;
        int camChunkZ = MathHelper.floor(cam.z) >> 4;

        List<LodChunkData> nearby = collectNearby(camChunkX, camChunkZ);
        DistantCollectResult distantResult = collectDistant(context, camChunkX, camChunkZ);
        List<LodChunkData> distant = distantResult.chunks();
        lastStats = new DebugStats(
                nearby.size(), distant.size(),
                NEAR_MESHES.size(), DISTANT_MESHES.size(),
                distantResult.requestsQueued(), distantResult.radiusChunks(), distantResult.loadedHorizonChunks());

        if (nearby.isEmpty() && distant.isEmpty()) {
            pruneMeshes(NEAR_MESHES, Set.of());
            pruneMeshes(DISTANT_MESHES, Set.of());
            return;
        }

        float prevFogStart = RenderSystem.getShaderFogStart();
        float prevFogEnd = RenderSystem.getShaderFogEnd();

        RenderSystem.enableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderFogStart(100000.0f);
        RenderSystem.setShaderFogEnd(100000.0f);
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);

        Matrix4f baseModelView = matrices.peek().getPositionMatrix();
        Matrix4f projection = RenderSystem.getProjectionMatrix();
        ShaderProgram shader = RenderSystem.getShader();

        renderMeshes(nearby, NEAR_MESHES, baseModelView, projection, shader, cam, DEBUG_HEIGHT_OFFSET, DEBUG_ALPHA);
        renderMeshes(distant, DISTANT_MESHES, baseModelView, projection, shader, cam, DISTANT_HEIGHT_OFFSET, DISTANT_ALPHA);
        VertexBuffer.unbind();

        RenderSystem.setShaderFogStart(prevFogStart);
        RenderSystem.setShaderFogEnd(prevFogEnd);
        RenderSystem.disableBlend();
        RenderSystem.enableCull();
    }

    private static void onHudRender(DrawContext context, float tickDelta) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.options.hudHidden || client.textRenderer == null) {
            return;
        }

        DebugStats stats = lastStats;
        String line1 = "MACLOD 0.2: near=" + stats.nearChunks()
                + " far=" + stats.distantChunks()
                + " queued=" + stats.requestsQueued();
        String line2 = "meshes near=" + stats.nearMeshes()
                + " far=" + stats.distantMeshes()
                + " radius=" + stats.radiusChunks()
                + " vanilla=" + stats.loadedHorizonChunks();

        TextRenderer text = client.textRenderer;
        int width = Math.max(text.getWidth(line1), text.getWidth(line2)) + 8;
        context.fill(4, 4, 8 + width, 28, 0x99000000);
        context.drawTextWithShadow(text, line1, 8, 7, 0x55FF55);
        context.drawTextWithShadow(text, line2, 8, 18, 0xAACCFF);
    }

    private static List<LodChunkData> collectNearby(int camChunkX, int camChunkZ) {
        List<LodChunkData> nearby = new ArrayList<>();
        for (LodChunkData data : ChunkLodStore.snapshot().values()) {
            if (Math.abs(data.chunkX() - camChunkX) <= DEBUG_RENDER_RADIUS_CHUNKS
                    && Math.abs(data.chunkZ() - camChunkZ) <= DEBUG_RENDER_RADIUS_CHUNKS) {
                nearby.add(data);
            }
        }
        return nearby;
    }

    /**
     * Collects cached disk-read chunks beyond the loaded horizon and schedules a
     * small number of new async reads each frame. Singleplayer overworld only.
     */
    private static DistantCollectResult collectDistant(WorldRenderContext context, int camChunkX, int camChunkZ) {
        ClientWorld world = context.world();
        if (world == null || !World.OVERWORLD.equals(world.getRegistryKey())) {
            return DistantCollectResult.empty();
        }
        MinecraftClient client = MinecraftClient.getInstance();
        MinecraftServer server = client.getServer();
        if (server == null) {
            return DistantCollectResult.empty();
        }
        Path saveRoot = server.getSavePath(WorldSavePath.ROOT);
        int vanillaViewDistance = client.options.getViewDistance().getValue();
        int loadedHorizonChunks = Math.max(DEBUG_RENDER_RADIUS_CHUNKS, vanillaViewDistance);
        int targetRadiusChunks = Math.max(DISTANT_MIN_RADIUS_CHUNKS, vanillaViewDistance + DISTANT_EXTRA_CHUNKS);
        int distantRadiusChunks = Math.min(targetRadiusChunks, DISTANT_MAX_RADIUS_CHUNKS);
        if (distantRadiusChunks <= loadedHorizonChunks) {
            return new DistantCollectResult(List.of(), 0, distantRadiusChunks, loadedHorizonChunks);
        }

        int requestsThisFrame = 0;
        List<LodChunkData> distant = new ArrayList<>();
        for (int dz = -distantRadiusChunks; dz <= distantRadiusChunks; dz++) {
            for (int dx = -distantRadiusChunks; dx <= distantRadiusChunks; dx++) {
                if (Math.abs(dx) <= loadedHorizonChunks && Math.abs(dz) <= loadedHorizonChunks) {
                    continue; // leave vanilla-loaded terrain to Minecraft / the near debug overlay
                }
                int cx = camChunkX + dx;
                int cz = camChunkZ + dz;
                long key = ChunkPos.toLong(cx, cz);
                if (ChunkLodStore.snapshot().containsKey(key)) {
                    continue; // client already has live data for this one
                }

                LodChunkData data = DistantLodStore.getCached(key);
                if (data != null) {
                    distant.add(data);
                } else if (requestsThisFrame < MAX_DISTANT_REQUESTS_PER_FRAME
                        && DistantLodStore.requestLoad(world, saveRoot, cx, cz)) {
                    requestsThisFrame++;
                }
            }
        }
        return new DistantCollectResult(distant, requestsThisFrame, distantRadiusChunks, loadedHorizonChunks);
    }

    private static void renderMeshes(List<LodChunkData> dataList,
                                     Map<Long, CachedChunkMesh> cache,
                                     Matrix4f baseModelView,
                                     Matrix4f projection,
                                     ShaderProgram shader,
                                     Vec3d cam,
                                     float heightOffset,
                                     float alpha) {
        Set<Long> activeKeys = new HashSet<>();
        for (LodChunkData data : dataList) {
            long key = ChunkPos.toLong(data.chunkX(), data.chunkZ());
            activeKeys.add(key);

            CachedChunkMesh mesh = cache.get(key);
            if (mesh == null || !mesh.matches(data, heightOffset, alpha)) {
                if (mesh != null) {
                    mesh.close();
                }
                mesh = CachedChunkMesh.build(data, heightOffset, alpha);
                cache.put(key, mesh);
            }

            Matrix4f modelView = new Matrix4f(baseModelView).translate(
                    (float) (mesh.baseX() - cam.x),
                    (float) (-cam.y),
                    (float) (mesh.baseZ() - cam.z)
            );
            mesh.draw(modelView, projection, shader);
        }
        pruneMeshes(cache, activeKeys);
    }

    private static void pruneMeshes(Map<Long, CachedChunkMesh> cache, Set<Long> activeKeys) {
        cache.entrySet().removeIf(entry -> {
            if (activeKeys.contains(entry.getKey())) {
                return false;
            }
            entry.getValue().close();
            return true;
        });
    }

    private static void clearMeshes() {
        pruneMeshes(NEAR_MESHES, Set.of());
        pruneMeshes(DISTANT_MESHES, Set.of());
    }

    private record DistantCollectResult(List<LodChunkData> chunks, int requestsQueued, int radiusChunks, int loadedHorizonChunks) {
        static DistantCollectResult empty() {
            return new DistantCollectResult(List.of(), 0, 0, 0);
        }
    }

    private record DebugStats(int nearChunks, int distantChunks, int nearMeshes, int distantMeshes,
                              int requestsQueued, int radiusChunks, int loadedHorizonChunks) {
        static DebugStats empty() {
            return new DebugStats(0, 0, 0, 0, 0, 0, 0);
        }
    }

    private static final class CachedChunkMesh {
        private static final Matrix4f IDENTITY = new Matrix4f();
        private static final int BYTES_PER_VERTEX_ESTIMATE = 32;
        /** Top faces + one vertical face for each internal E/S height transition. */
        private static final int MAX_QUADS_PER_CHUNK = LodChunkData.SIZE * LodChunkData.SIZE
                + (LodChunkData.SIZE - 1) * LodChunkData.SIZE * 2;
        private static final int VERTICES_PER_CHUNK = MAX_QUADS_PER_CHUNK * 4;

        private final LodChunkData data;
        private final float heightOffset;
        private final float alpha;
        private final VertexBuffer vertexBuffer;

        private CachedChunkMesh(LodChunkData data, float heightOffset, float alpha, VertexBuffer vertexBuffer) {
            this.data = data;
            this.heightOffset = heightOffset;
            this.alpha = alpha;
            this.vertexBuffer = vertexBuffer;
        }

        static CachedChunkMesh build(LodChunkData data, float heightOffset, float alpha) {
            BufferBuilder buffer = new BufferBuilder(VERTICES_PER_CHUNK * BYTES_PER_VERTEX_ESTIMATE);
            buffer.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);

            for (int z = 0; z < LodChunkData.SIZE; z++) {
                for (int x = 0; x < LodChunkData.SIZE; x++) {
                    float y = data.height(x, z) + heightOffset;
                    int packed = data.color(x, z);

                    addTopFace(buffer, x, z, y, packed, alpha);

                    if (x < LodChunkData.SIZE - 1) {
                        float eastY = data.height(x + 1, z) + heightOffset;
                        if (Float.compare(y, eastY) != 0) {
                            addVerticalFaceX(buffer, x + 1, z, Math.min(y, eastY), Math.max(y, eastY), packed, alpha);
                        }
                    }

                    if (z < LodChunkData.SIZE - 1) {
                        float southY = data.height(x, z + 1) + heightOffset;
                        if (Float.compare(y, southY) != 0) {
                            addVerticalFaceZ(buffer, x, z + 1, Math.min(y, southY), Math.max(y, southY), packed, alpha);
                        }
                    }
                }
            }

            VertexBuffer vertexBuffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
            vertexBuffer.upload(buffer.end());
            return new CachedChunkMesh(data, heightOffset, alpha, vertexBuffer);
        }


        private static void addTopFace(BufferBuilder buffer, int x, int z, float y, int packed, float alpha) {
            Color color = Color.fromPacked(packed, 1.0f);
            float x0 = x;
            float x1 = x + 1;
            float z0 = z;
            float z1 = z + 1;
            buffer.vertex(IDENTITY, x0, y, z0).color(color.r(), color.g(), color.b(), alpha).next();
            buffer.vertex(IDENTITY, x1, y, z0).color(color.r(), color.g(), color.b(), alpha).next();
            buffer.vertex(IDENTITY, x1, y, z1).color(color.r(), color.g(), color.b(), alpha).next();
            buffer.vertex(IDENTITY, x0, y, z1).color(color.r(), color.g(), color.b(), alpha).next();
        }

        private static void addVerticalFaceX(BufferBuilder buffer, int x, int z, float y0, float y1, int packed, float alpha) {
            Color color = Color.fromPacked(packed, 0.62f);
            float z0 = z;
            float z1 = z + 1;
            buffer.vertex(IDENTITY, x, y1, z0).color(color.r(), color.g(), color.b(), alpha).next();
            buffer.vertex(IDENTITY, x, y1, z1).color(color.r(), color.g(), color.b(), alpha).next();
            buffer.vertex(IDENTITY, x, y0, z1).color(color.r(), color.g(), color.b(), alpha).next();
            buffer.vertex(IDENTITY, x, y0, z0).color(color.r(), color.g(), color.b(), alpha).next();
        }

        private static void addVerticalFaceZ(BufferBuilder buffer, int x, int z, float y0, float y1, int packed, float alpha) {
            Color color = Color.fromPacked(packed, 0.70f);
            float x0 = x;
            float x1 = x + 1;
            buffer.vertex(IDENTITY, x0, y1, z).color(color.r(), color.g(), color.b(), alpha).next();
            buffer.vertex(IDENTITY, x1, y1, z).color(color.r(), color.g(), color.b(), alpha).next();
            buffer.vertex(IDENTITY, x1, y0, z).color(color.r(), color.g(), color.b(), alpha).next();
            buffer.vertex(IDENTITY, x0, y0, z).color(color.r(), color.g(), color.b(), alpha).next();
        }

        private record Color(float r, float g, float b) {
            static Color fromPacked(int packed, float shade) {
                float r = ((packed >> 16) & 0xFF) / 255.0f * shade;
                float g = ((packed >> 8) & 0xFF) / 255.0f * shade;
                float b = (packed & 0xFF) / 255.0f * shade;
                return new Color(r, g, b);
            }
        }

        boolean matches(LodChunkData otherData, float otherHeightOffset, float otherAlpha) {
            return this.data == otherData
                    && Float.compare(this.heightOffset, otherHeightOffset) == 0
                    && Float.compare(this.alpha, otherAlpha) == 0;
        }

        int baseX() {
            return data.chunkX() * LodChunkData.SIZE;
        }

        int baseZ() {
            return data.chunkZ() * LodChunkData.SIZE;
        }

        void draw(Matrix4f modelView, Matrix4f projection, ShaderProgram shader) {
            vertexBuffer.draw(modelView, projection, shader);
        }

        void close() {
            vertexBuffer.close();
        }
    }
}
