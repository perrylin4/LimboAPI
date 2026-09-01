/*
 * Copyright (C) 2021 - 2025 Elytrium
 *
 * The LimboAPI (excluding the LimboAPI plugin) is licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in the api top-level directory.
 */

package net.elytrium.limboapi.api.protocol.packets.data;

import java.util.HashMap;
import java.util.Map;
import net.elytrium.limboapi.api.chunk.VirtualBiome;
import net.elytrium.limboapi.api.chunk.data.ChunkSnapshot;

/**
 * For ChunkData packet.
 */
public class BiomeData {

  private static final int MAX_BIOMES_PER_SECTION = 4 * 4 * 4;
  // 1.13 - 1.17 clients always use a fixed 256-block world (16 sections * 64 biomes per section).
  private static final int LEGACY_BIOME_COUNT = 16 * MAX_BIOMES_PER_SECTION;

  private final int[] post115Biomes = new int[LEGACY_BIOME_COUNT];
  private final byte[] pre115Biomes = new byte[256];

  public BiomeData(ChunkSnapshot chunk) {
    VirtualBiome[] biomes = chunk.getBiomes();
    int sectionCount = biomes.length / MAX_BIOMES_PER_SECTION;
    if (sectionCount == 0) {
      sectionCount = 1;
    }

    for (int i = 0; i < Math.min(LEGACY_BIOME_COUNT, biomes.length); ++i) {
      this.post115Biomes[i] = biomes[i].getID();
    }

    // Down sample 3D biomes to 2D XZ.
    Map<Integer, Integer> samples = new HashMap<>(64);
    for (int posX = 0; posX < 16; posX += 4) {
      for (int posZ = 0; posZ < 16; posZ += 4) {
        samples.clear();
        for (int section = 0; section < sectionCount; ++section) {
          int index = section * MAX_BIOMES_PER_SECTION + ((posZ >> 2 & 3) << 2) + (posX >> 2 & 3);
          VirtualBiome biome = biomes[index];
          samples.put(biome.getID(), samples.getOrDefault(biome.getID(), 0) + 1);
        }
        int id = samples.entrySet()
            .stream()
            .max(Map.Entry.comparingByValue())
            .orElseThrow()
            .getKey();
        for (int i = posX; i < posX + 4; ++i) {
          for (int j = posZ; j < posZ + 4; ++j) {
            this.pre115Biomes[(j << 4) + i] = (byte) id;
          }
        }
      }
    }
  }

  public int[] getPost115Biomes() {
    return this.post115Biomes;
  }

  public byte[] getPre115Biomes() {
    return this.pre115Biomes;
  }
}
