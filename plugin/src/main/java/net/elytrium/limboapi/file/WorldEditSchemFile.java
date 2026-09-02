/*
 * Copyright (C) 2021 - 2025 Elytrium
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.elytrium.limboapi.file;

import com.velocitypowered.proxy.protocol.ProtocolUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.elytrium.limboapi.LimboAPI;
import net.elytrium.limboapi.api.LimboFactory;
import net.elytrium.limboapi.api.chunk.VirtualBlock;
import net.elytrium.limboapi.api.chunk.VirtualChunk;
import net.elytrium.limboapi.api.chunk.VirtualWorld;
import net.elytrium.limboapi.api.file.WorldFile;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.IntBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import org.slf4j.Logger;

public class WorldEditSchemFile implements WorldFile {

  private static final List<String> AIR_BLOCKS = List.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air");

  private final short width;
  private final short height;
  private final short length;
  private final CompoundBinaryTag palette;
  private final ListBinaryTag blockEntities;

  // Sparse, per-chunk storage of only the non-air blocks: each chunk maps to a list of
  // [localX, worldY, localZ, paletteIndex] int arrays (one per non-air block). This avoids
  // materializing the whole width*height*length bounding box (which for large mostly-air schematics
  // used to be an int array of several GB, even when the compressed .schem file is only a couple of MB).
  private final Map<Long, List<int[]>> chunks = new HashMap<>();

  public WorldEditSchemFile(CompoundBinaryTag rootTag) {
    ByteBuf blockDataBuf;

    if (rootTag.contains("Width")) {
      // Check is it old worldedit schema
      this.width = rootTag.getShort("Width");
      this.height = rootTag.getShort("Height");
      this.length = rootTag.getShort("Length");
      this.palette = rootTag.getCompound("Palette");

      blockDataBuf = Unpooled.wrappedBuffer(rootTag.getByteArray("BlockData"));

      this.blockEntities = rootTag.getList("BlockEntities");

    } else if (rootTag.getCompound("Schematic").contains("Blocks")) {
      // Check is it new worldedit schema
      CompoundBinaryTag schematicTag = rootTag.getCompound("Schematic");

      this.width = schematicTag.getShort("Width");
      this.height = schematicTag.getShort("Height");
      this.length = schematicTag.getShort("Length");

      CompoundBinaryTag blocksTag = schematicTag.getCompound("Blocks");
      this.palette = blocksTag.getCompound("Palette");

      blockDataBuf = Unpooled.wrappedBuffer(blocksTag.getByteArray("Data"));

      this.blockEntities = blocksTag.getList("BlockEntities");
    } else {
      // Unknown schema, throw exception
      throw new IllegalArgumentException("Invalid worldedit file format. Please open an issue on GitHub.");
    }

    int paletteSize = this.palette.keySet().size();
    boolean[] isAirIndex = new boolean[Math.max(1, paletteSize)];
    for (String air : AIR_BLOCKS) {
      if (this.palette.keySet().contains(air)) {
        int airIndex = ((IntBinaryTag) Objects.requireNonNull(this.palette.get(air))).value();
        if (airIndex >= 0 && airIndex < isAirIndex.length) {
          isAirIndex[airIndex] = true;
        }
      }
    }

    Logger logger = LimboAPI.getLogger();
    logger.info("Decoding schematic block data into a sparse chunk structure.");
    long totalBlocks = (long) this.width * this.height * this.length;
    long widthByLength = (long) this.width * this.length;
    for (long index = 0; index < totalBlocks; index++) {
      int paletteIndex = ProtocolUtils.readVarInt(blockDataBuf);
      if (paletteIndex >= 0 && paletteIndex < isAirIndex.length && isAirIndex[paletteIndex]) {
        continue;
      }

      int posX = (int) (index % this.width);
      int posZ = (int) ((index / this.width) % this.length);
      int posY = (int) (index / widthByLength);

      long chunkKey = getChunkIndex(posX >> 4, posZ >> 4);
      this.chunks.computeIfAbsent(chunkKey, key -> new ArrayList<>())
          .add(new int[] {posX & 15, posY, posZ & 15, paletteIndex});
    }

    logger.info("Decoded {} chunks containing non-air blocks.", this.chunks.size());
  }

  @Override
  public void toWorld(LimboFactory factory, VirtualWorld world, int offsetX, int offsetY, int offsetZ, int lightLevel) {
    int paletteSize = this.palette.keySet().size();
    final Logger logger = LimboAPI.getLogger();
    AtomicInteger completedChunks = new AtomicInteger(0);

    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
      Thread thread = new Thread(runnable, "LimboAPI-world-import-progress");
      thread.setDaemon(true);
      return thread;
    });
    final ScheduledFuture<?> progressTask = scheduler.scheduleAtFixedRate(() -> {
      int done = completedChunks.get();
      logger.info(String.format("World import progress: %.1f%% (%d/%d chunks)",
          done * 100.0 / Math.max(1, this.chunks.size()), done, this.chunks.size()));
    }, 10, 10, TimeUnit.SECONDS);

    logger.info("Parsing NBT into paletted blocks.");
    VirtualBlock[] palettedBlocks = new VirtualBlock[paletteSize];
    this.palette.forEach((entry) ->
        palettedBlocks[((IntBinaryTag) entry.getValue()).value()] = factory.createSimpleBlock(entry.getKey())
    );
    logger.info("Palette parsed: 100% ({}/{}).", paletteSize, paletteSize);

    logger.info("Writing paletted blocks into chunks.");
    for (Map.Entry<Long, List<int[]>> entry : this.chunks.entrySet()) {
      long chunkKey = entry.getKey();
      int chunkX = (int) (chunkKey >> 32);
      int chunkZ = (int) chunkKey;

      VirtualChunk chunk = world.getChunkOrNew(offsetX + chunkX * 16, offsetZ + chunkZ * 16);

      for (int[] blockData : entry.getValue()) {
        int paletteIndex = blockData[3];
        if (paletteIndex < 0 || paletteIndex >= palettedBlocks.length) {
          continue;
        }

        VirtualBlock block = palettedBlocks[paletteIndex];
        if (!block.isAir()) {
          chunk.setBlock(blockData[0], blockData[1] + offsetY, blockData[2], block);
        }
      }

      completedChunks.incrementAndGet();
    }

    progressTask.cancel(false);
    scheduler.shutdown();
    logger.info("World import finished: 100% ({} chunks).", this.chunks.size());

    logger.info("Writing block entities into chunks...");
    for (BinaryTag blockEntity : this.blockEntities) {
      CompoundBinaryTag blockEntityData = (CompoundBinaryTag) blockEntity;
      int[] posTag = blockEntityData.getIntArray("Pos");
      world.setBlockEntity(
          offsetX + posTag[0],
          offsetY + posTag[1],
          offsetZ + posTag[2],
          blockEntityData,
          factory.getBlockEntity(blockEntityData.getString("Id")));
    }

    logger.info("Filling sky light...");
    world.fillSkyLight(lightLevel);
    logger.info("World conversion finished.");
  }

  private static long getChunkIndex(int chunkX, int chunkZ) {
    return (long) chunkX << 32 | chunkZ & 0xFFFFFFFFL;
  }
}
