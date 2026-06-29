package com.example.distantlod.data;

import com.mojang.serialization.Codec;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.MapColor;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.chunk.PalettedContainer;

import java.util.HashMap;
import java.util.Map;

/**
 * Replaces the height-band placeholder colors with real per-column surface
 * block map colors, decoded straight from a chunk's saved {@code sections}
 * NBT - the same per-section block-state palette format vanilla itself
 * writes.
 *
 * Reuses Minecraft's own {@link PalettedContainer} codec rather than
 * hand-rolling palette/bit-unpacking (the same pattern as reusing
 * {@code PackedIntegerArray} for heightmaps): vanilla's codec already
 * handles every edge case (single-value sections with no {@code data} array,
 * variable bit widths, etc.) correctly.
 */
public final class DistantBlockColorDecoder {

    private static final Codec<PalettedContainer<BlockState>> BLOCK_STATE_CODEC =
            PalettedContainer.createPalettedContainerCodec(
                    Block.STATE_IDS, BlockState.CODEC,
                    PalettedContainer.PaletteProvider.BLOCK_STATE, Blocks.AIR.getDefaultState());

    private DistantBlockColorDecoder() {}

    /**
     * Overwrites entries in {@code colors} with real map colors wherever the
     * surface block can be resolved; columns that fail to resolve (missing
     * section, air at the recorded height, decode failure) are left as-is so
     * the caller's height-band placeholder still shows through for them.
     */
    public static void decorateColors(NbtCompound root, int[] heights, int[] colors) {
        Map<Integer, NbtCompound> sectionsByY = sectionsByY(root);
        Map<Integer, PalettedContainer<BlockState>> decodedSections = new HashMap<>();

        int size = LodChunkData.SIZE;
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                int idx = z * size + x;
                int worldY = heights[idx];
                int sectionY = Math.floorDiv(worldY, 16);

                PalettedContainer<BlockState> container = decodedSections.computeIfAbsent(
                        sectionY, key -> decodeSection(sectionsByY.get(key)));
                if (container == null) {
                    continue;
                }

                BlockState state = container.get(x, Math.floorMod(worldY, 16), z);
                if (state == null) {
                    continue;
                }
                // getMapColor ignores both arguments for every vanilla block (it just
                // returns a precomputed field), so a null BlockView/BlockPos is safe -
                // there is no live world to pass here anyway.
                MapColor mapColor = state.getMapColor(null, null);
                if (mapColor == MapColor.CLEAR) {
                    continue; // air or similar; keep the height-band placeholder
                }
                int rgb = mapColor.getRenderColor(MapColor.Brightness.NORMAL);
                colors[idx] = 0xFF000000 | (rgb & 0x00FFFFFF);
            }
        }
    }

    private static Map<Integer, NbtCompound> sectionsByY(NbtCompound root) {
        Map<Integer, NbtCompound> result = new HashMap<>();
        NbtList sections = root.getList("sections", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < sections.size(); i++) {
            NbtCompound section = sections.getCompound(i);
            if (section.contains("block_states")) {
                result.put((int) section.getByte("Y"), section);
            }
        }
        return result;
    }

    private static PalettedContainer<BlockState> decodeSection(NbtCompound section) {
        if (section == null) {
            return null;
        }
        NbtElement blockStates = section.get("block_states");
        if (blockStates == null) {
            return null;
        }
        return BLOCK_STATE_CODEC.parse(NbtOps.INSTANCE, blockStates).result().orElse(null);
    }
}
