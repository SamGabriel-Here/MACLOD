package com.example.distantlod.render;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

/**
 * Week 1-2 milestone: prove the render pipeline works on macOS by drawing four
 * large coloured walls in a ring ~300 blocks from the player — well past the
 * normal terrain. If you can see them through/over the world, the hook,
 * camera-relative math, and far-distance draw all work, and you have a place to
 * hang real LOD geometry later.
 *
 * Why this is macOS-safe: it draws through Minecraft's own {@link RenderSystem}
 * and the built-in {@code position_color} core shader, which already targets the
 * OpenGL 3.2+ Core Profile that macOS requires (VAO-backed, no immediate-mode,
 * no GL_QUADS at the driver level — Minecraft triangulates DrawMode.QUADS for
 * you). When you move to your own VAO/VBO + GLSL, keep these same rules.
 */
public final class LodRenderer {

    /** How far out the test ring sits, in blocks. */
    private static final double RING_DISTANCE = 300.0;
    /** Half-width of each wall along its horizontal axis. */
    private static final float HALF_WIDTH = 80.0f;
    /** World Y the walls span. */
    private static final float Y_BOTTOM = 50.0f;
    private static final float Y_TOP = 140.0f;

    private LodRenderer() {}

    public static void register() {
        // AFTER_TRANSLUCENT runs once vanilla terrain + water are drawn, so our
        // geometry composites correctly against the existing depth buffer.
        WorldRenderEvents.AFTER_TRANSLUCENT.register(LodRenderer::onAfterTranslucent);
    }

    private static void onAfterTranslucent(WorldRenderContext context) {
        MatrixStack matrices = context.matrixStack();
        Camera camera = context.camera();
        if (matrices == null || camera == null) {
            return;
        }
        Vec3d cam = camera.getPos();

        // --- Save the GL state we are about to change (macOS punishes leaks) ---
        float prevFogStart = RenderSystem.getShaderFogStart();
        float prevFogEnd = RenderSystem.getShaderFogEnd();
        boolean prevCull = true; // vanilla leaves cull enabled here

        RenderSystem.enableDepthTest();
        RenderSystem.disableCull();            // walls visible from both sides
        // Push fog out so the far walls are not faded to sky colour during the test.
        RenderSystem.setShaderFogStart(100000.0f);
        RenderSystem.setShaderFogEnd(100000.0f);
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);

        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        buffer.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);

        // Four walls around the player. Colours make orientation obvious:
        //   north (-Z) red, south (+Z) green, east (+X) blue, west (-X) yellow.
        addWall(buffer, matrices, cam, 0.0, -RING_DISTANCE, true,  1.0f, 0.25f, 0.25f);
        addWall(buffer, matrices, cam, 0.0,  RING_DISTANCE, true,  0.25f, 1.0f, 0.30f);
        addWall(buffer, matrices, cam,  RING_DISTANCE, 0.0, false, 0.25f, 0.45f, 1.0f);
        addWall(buffer, matrices, cam, -RING_DISTANCE, 0.0, false, 1.0f, 0.90f, 0.25f);

        tessellator.draw();

        // --- Restore state ---
        RenderSystem.setShaderFogStart(prevFogStart);
        RenderSystem.setShaderFogEnd(prevFogEnd);
        if (prevCull) {
            RenderSystem.enableCull();
        }
    }

    /**
     * Adds one quad to the buffer. The world-render matrix stack here is
     * camera-relative (camera rotation applied, translation at origin), so a
     * point at world (camX+offX, y, camZ+offZ) is given as (offX, y-camY, offZ).
     *
     * @param spanX true  -> wall runs along the X axis (faces north/south)
     *              false -> wall runs along the Z axis (faces east/west)
     */
    private static void addWall(BufferBuilder buf, MatrixStack matrices, Vec3d cam,
                                double offX, double offZ, boolean spanX,
                                float r, float g, float b) {
        Matrix4f m = matrices.peek().getPositionMatrix();
        float cx = (float) offX;
        float cz = (float) offZ;
        float y0 = (float) (Y_BOTTOM - cam.y);
        float y1 = (float) (Y_TOP - cam.y);

        if (spanX) {
            float x0 = cx - HALF_WIDTH;
            float x1 = cx + HALF_WIDTH;
            buf.vertex(m, x0, y0, cz).color(r, g, b, 1.0f).next();
            buf.vertex(m, x1, y0, cz).color(r, g, b, 1.0f).next();
            buf.vertex(m, x1, y1, cz).color(r, g, b, 1.0f).next();
            buf.vertex(m, x0, y1, cz).color(r, g, b, 1.0f).next();
        } else {
            float z0 = cz - HALF_WIDTH;
            float z1 = cz + HALF_WIDTH;
            buf.vertex(m, cx, y0, z0).color(r, g, b, 1.0f).next();
            buf.vertex(m, cx, y0, z1).color(r, g, b, 1.0f).next();
            buf.vertex(m, cx, y1, z1).color(r, g, b, 1.0f).next();
            buf.vertex(m, cx, y1, z0).color(r, g, b, 1.0f).next();
        }
    }
}
