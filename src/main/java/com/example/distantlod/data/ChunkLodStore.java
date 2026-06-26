package com.example.distantlod.data;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.minecraft.util.math.ChunkPos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds the most recent {@link LodChunkData} for every chunk the client
 * currently has loaded, kept up to date via {@link ClientChunkEvents}.
 *
 * Capture happens directly on chunk load (cheap: 256 heightmap + block-state
 * lookups), no background thread needed yet — that becomes worthwhile once
 * mesh building (not just data capture) gets expensive.
 */
public final class ChunkLodStore {

    private static final Map<Long, LodChunkData> CHUNKS = new ConcurrentHashMap<>();

    private ChunkLodStore() {}

    public static void register() {
        ClientChunkEvents.CHUNK_LOAD.register((world, chunk) ->
                CHUNKS.put(ChunkPos.toLong(chunk.getPos().x, chunk.getPos().z), ChunkLodCapture.capture(world, chunk)));

        ClientChunkEvents.CHUNK_UNLOAD.register((world, chunk) ->
                CHUNKS.remove(ChunkPos.toLong(chunk.getPos().x, chunk.getPos().z)));
    }

    public static Map<Long, LodChunkData> snapshot() {
        return CHUNKS;
    }

    public static void clear() {
        CHUNKS.clear();
    }
}
