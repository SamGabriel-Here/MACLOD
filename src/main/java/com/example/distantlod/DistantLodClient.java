package com.example.distantlod;

import com.example.distantlod.data.ChunkLodStore;
import com.example.distantlod.render.LodRenderer;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client entrypoint. This is a client-only mod: there is no server/common
 * initializer because LOD rendering happens entirely on the client.
 *
 * Roadmap:
 *   Week 1-2  (DONE)  Render hook + far-distance test quads.
 *   Week 3-4  (DONE)  Capture loaded chunks into LodChunkData, render real
 *                      heightmap mesh from it (see ChunkLodStore, LodRenderer).
 *   Week 5-6          Background mesh builder (ExecutorService) -> real LOD
 *                      geometry beyond the normal horizon (needs region-file
 *                      reading, not just loaded-chunk capture).
 *   Week 7-8          Distance-fade shader, disk cache, tune near/far rings.
 */
public class DistantLodClient implements ClientModInitializer {
    public static final String MOD_ID = "distantlod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        LOGGER.info("[Distant LOD] Initializing far-terrain renderer (1.20.1)");
        ChunkLodStore.register();
        LodRenderer.register();
    }
}
