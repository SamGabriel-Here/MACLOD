package com.example.distantlod.data;

import net.minecraft.block.BlockState;
import net.minecraft.block.MapColor;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Builds a {@link LodChunkData} snapshot from a loaded chunk: top-surface
 * height (ignoring leaves, so canopy doesn't dominate the silhouette) and the
 * vanilla map color of the surface block, per column.
 */
public final class ChunkLodCapture {

    private ChunkLodCapture() {}

    public static LodChunkData capture(ClientWorld world, WorldChunk chunk) {
        int size = LodChunkData.SIZE;
        int[] heights = new int[size * size];
        int[] colors = new int[size * size];

        int baseX = chunk.getPos().getStartX();
        int baseZ = chunk.getPos().getStartZ();
        BlockPos.Mutable pos = new BlockPos.Mutable();

        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                int worldX = baseX + x;
                int worldZ = baseZ + z;
                // MOTION_BLOCKING (not _NO_LEAVES, which is server-only/LIVE_WORLD
                // purpose and never synced to the client - reading it here would
                // silently yield garbage heights). -1: getTopY returns the empty
                // space above the surface block.
                int topY = world.getTopY(Heightmap.Type.MOTION_BLOCKING, worldX, worldZ) - 1;

                int idx = z * size + x;
                heights[idx] = topY;

                pos.set(worldX, topY, worldZ);
                BlockState state = world.getBlockState(pos);
                MapColor mapColor = state.getMapColor(world, pos);
                int rgb = mapColor.getRenderColor(MapColor.Brightness.NORMAL);
                colors[idx] = 0xFF000000 | (rgb & 0x00FFFFFF);
            }
        }

        return new LodChunkData(chunk.getPos().x, chunk.getPos().z, heights, colors);
    }
}
