package com.example.distantlod.render;

import com.example.distantlod.data.ChunkLodStore;
import com.example.distantlod.data.DistantLodStore;
import com.example.distantlod.data.LodChunkData;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
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
import java.util.List;

/**
 * Week 3-4 milestone: render a flat heightmap mesh built from real captured
 * chunk data ({@link ChunkLodStore}), proving the capture -> mesh -> render
 * pipeline end to end. Each loaded column becomes one flat quad at its
 * captured surface height, tinted with its captured map color, floated
 * {@link #DEBUG_HEIGHT_OFFSET} blocks above the terrain so it's visible
 * without fighting vanilla terrain in the depth buffer.
 *
 * This intentionally still only covers chunks the client has already loaded
 * (no terrain beyond the normal horizon yet) and still draws through
 * Minecraft's own Tessellator + built-in position_color shader, for the same
 * macOS Core Profile safety reasons as the Week 1-2 test walls.
 */
public final class LodRenderer {

    /** Cap how many chunk-rings around the camera we draw, to bound vertex count. */
    private static final int DEBUG_RENDER_RADIUS_CHUNKS = 3;
    /** Lift the debug mesh well above eye level so it reads as a distinct overlay, not a wall at head height. */
    private static final float DEBUG_HEIGHT_OFFSET = 6.0f;
    /** Translucent so real terrain stays visible underneath. */
    private static final float DEBUG_ALPHA = 0.55f;

    /** Minimum disk-read radius, in chunks, when vanilla render distance is low. */
    private static final int DISTANT_MIN_RADIUS_CHUNKS = 20;
    /** Additional chunks beyond vanilla render distance to read from disk. */
    private static final int DISTANT_EXTRA_CHUNKS = 4;
    /** Safety cap while this prototype still rebuilds debug quads every frame. */
    private static final int DISTANT_MAX_RADIUS_CHUNKS = 36;
    private static final float DISTANT_HEIGHT_OFFSET = 10.0f;
    // Opaque, unlike the near debug ring: there's no real terrain underneath this
    // to see through (vanilla never loads/renders it), so translucency just makes
    // long sightlines over varied terrain stack hundreds of overlapping quads into
    // a dense, washed-out haze instead of looking like solid ground.
    private static final float DISTANT_ALPHA = 1.0f;

    private LodRenderer() {}

    public static void register() {
        WorldRenderEvents.AFTER_TRANSLUCENT.register(LodRenderer::onAfterTranslucent);
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

        List<LodChunkData> nearby = new ArrayList<>();
        for (LodChunkData data : ChunkLodStore.snapshot().values()) {
            if (Math.abs(data.chunkX() - camChunkX) <= DEBUG_RENDER_RADIUS_CHUNKS
                    && Math.abs(data.chunkZ() - camChunkZ) <= DEBUG_RENDER_RADIUS_CHUNKS) {
                nearby.add(data);
            }
        }

        List<LodChunkData> distant = collectDistant(context, camChunkX, camChunkZ);

        if (nearby.isEmpty() && distant.isEmpty()) {
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

        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        buffer.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);

        Matrix4f m = matrices.peek().getPositionMatrix();
        for (LodChunkData data : nearby) {
            addChunkQuads(buffer, m, cam, data, DEBUG_HEIGHT_OFFSET, DEBUG_ALPHA);
        }
        for (LodChunkData data : distant) {
            addChunkQuads(buffer, m, cam, data, DISTANT_HEIGHT_OFFSET, DISTANT_ALPHA);
        }

        tessellator.draw();

        RenderSystem.setShaderFogStart(prevFogStart);
        RenderSystem.setShaderFogEnd(prevFogEnd);
        RenderSystem.disableBlend();
        RenderSystem.enableCull();
    }

    /**
     * Collects already-cached disk-read chunks beyond the loaded horizon, and
     * kicks off async loads for any in range that aren't cached yet. Singleplayer
     * + overworld only: there's no save folder to read for a remote server, and
     * other dimensions aren't wired up yet.
     */
    private static List<LodChunkData> collectDistant(WorldRenderContext context, int camChunkX, int camChunkZ) {
        ClientWorld world = context.world();
        if (world == null || !World.OVERWORLD.equals(world.getRegistryKey())) {
            return List.of();
        }
        MinecraftClient client = MinecraftClient.getInstance();
        MinecraftServer server = client.getServer();
        if (server == null) {
            return List.of();
        }
        Path saveRoot = server.getSavePath(WorldSavePath.ROOT);
        int vanillaViewDistance = client.options.getViewDistance().getValue();
        int loadedHorizonChunks = Math.max(DEBUG_RENDER_RADIUS_CHUNKS, vanillaViewDistance);
        int targetRadiusChunks = Math.max(DISTANT_MIN_RADIUS_CHUNKS, vanillaViewDistance + DISTANT_EXTRA_CHUNKS);
        int distantRadiusChunks = Math.min(targetRadiusChunks, DISTANT_MAX_RADIUS_CHUNKS);
        if (distantRadiusChunks <= loadedHorizonChunks) {
            return List.of(); // render distance is already at/above this prototype's safety cap
        }

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
                } else {
                    DistantLodStore.requestLoad(world, saveRoot, cx, cz);
                }
            }
        }
        return distant;
    }

    private static void addChunkQuads(BufferBuilder buf, Matrix4f m, Vec3d cam, LodChunkData data,
                                       float heightOffset, float alpha) {
        int baseX = data.chunkX() * LodChunkData.SIZE;
        int baseZ = data.chunkZ() * LodChunkData.SIZE;

        for (int z = 0; z < LodChunkData.SIZE; z++) {
            for (int x = 0; x < LodChunkData.SIZE; x++) {
                int worldX = baseX + x;
                int worldZ = baseZ + z;
                float y = (float) (data.height(x, z) + heightOffset - cam.y);
                float x0 = (float) (worldX - cam.x);
                float x1 = (float) (worldX + 1 - cam.x);
                float z0 = (float) (worldZ - cam.z);
                float z1 = (float) (worldZ + 1 - cam.z);

                int packed = data.color(x, z);
                float r = ((packed >> 16) & 0xFF) / 255.0f;
                float g = ((packed >> 8) & 0xFF) / 255.0f;
                float b = (packed & 0xFF) / 255.0f;

                buf.vertex(m, x0, y, z0).color(r, g, b, alpha).next();
                buf.vertex(m, x1, y, z0).color(r, g, b, alpha).next();
                buf.vertex(m, x1, y, z1).color(r, g, b, alpha).next();
                buf.vertex(m, x0, y, z1).color(r, g, b, alpha).next();
            }
        }
    }
}
