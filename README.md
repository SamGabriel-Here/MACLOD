# Distant LOD

A learning prototype of a Distant Horizons–style far-terrain renderer for
Minecraft, built on **Fabric 1.20.1** and set up to run cleanly on **Apple
Silicon macOS** (M-series).

This is the Week 1–2 milestone: a working render hook that draws four coloured
walls ~300 blocks out, far past the normal terrain. If you can see them, your
rendering pipeline works on macOS and you have a place to hang real LOD
geometry.

## Prerequisites (macOS / Apple Silicon)

- **JDK 17, ARM64.** Minecraft 1.20.1 runs on Java 17. Get the native
  Apple-Silicon build (e.g. Azul Zulu 17 aarch64) so you are not on Rosetta.
  Verify: `java -version` should mention `17` and `aarch64`.
- **IntelliJ IDEA** (Community is fine). Easiest path — it downloads the Gradle
  wrapper and dependencies for you on import.

## First-time setup

This scaffold ships without the Gradle wrapper JAR (it's a binary). Generate it
once, from the project root:

```bash
# If you have Gradle installed (brew install gradle):
gradle wrapper --gradle-version 8.8
```

…or just **open the folder in IntelliJ IDEA** and let it import the Gradle
project — it creates the wrapper and pulls everything automatically. No separate
Gradle install needed in that case.

## Build & run

```bash
./gradlew genSources    # decompile MC with Yarn names (nice for exploring)
./gradlew runClient     # launches a dev Minecraft with the mod loaded
```

Create a new world, then look toward each compass direction:

- North → **red** wall
- South → **green** wall
- East  → **blue** wall
- West  → **yellow** wall

Seeing them confirms the render hook, camera-relative math, and far-distance
draw all work.

## Project layout

```
distant-lod/
├── build.gradle            # Fabric Loom build, Java 17, Yarn mappings
├── settings.gradle         # Fabric maven for the Loom plugin
├── gradle.properties       # all version pins live here
├── src/main/
│   ├── java/com/example/distantlod/
│   │   ├── DistantLodClient.java     # client entrypoint
│   │   ├── render/LodRenderer.java   # << the Week 1 render hook
│   │   └── data/LodChunkData.java    # compact LOD snapshot (Week 3-4 stub)
│   └── resources/
│       ├── fabric.mod.json
│       └── assets/distantlod/shaders/  # reference GLSL for the custom-shader path
```

## Why it runs on macOS

`LodRenderer` draws through Minecraft's own `RenderSystem` and the built-in
`position_color` core shader. That already targets the OpenGL 3.2+ Core Profile
Apple's GL requires: VAO-backed, no immediate mode, and `DrawMode.QUADS` is
triangulated by Minecraft (the driver never sees `GL_QUADS`). When you move to
your own VAO/VBO + GLSL, keep the same rules — they're enforced strictly on
macOS:

- No immediate mode (`glBegin/glEnd`), no fixed-function pipeline.
- Always bind a VAO before drawing.
- Use `GL_TRIANGLES` + an index buffer, never `GL_QUADS`.
- Restore all GL state after your draw (macOS is unforgiving about state leaks).
- Keep GLSL at `#version 150` (GL 3.2) or whatever version MC creates.

The reference shaders in `assets/distantlod/shaders/` show the distance-fade
seam trick for later.

## Roadmap

1. **Week 1–2 (done):** render hook + far test quads.
2. **Week 3–4:** capture loaded chunks into `LodChunkData` (heightmap + top
   biome colour per column) on a background thread.
3. **Week 5–6:** group chunks into region tiles; build coarse heightfield meshes
   on an `ExecutorService` pool (never the main thread); handle load/unload.
4. **Week 7–8:** swap to your own VAO/VBO + the GLSL here, add the distance-fade
   blend zone, and persist the LOD cache to disk (`MappedByteBuffer` on APFS).

## Notes

- Client-only mod (`"environment": "client"`), so it can join vanilla servers.
- Version pins are in `gradle.properties`; bump them against
  <https://fabricmc.net/develop> when you upgrade.
- When Mojang's Vulkan/MoltenVK switch lands, the renderer layer needs a rewrite
  but the LOD **data** pipeline (capture → snapshot → mesh) carries over.
```
