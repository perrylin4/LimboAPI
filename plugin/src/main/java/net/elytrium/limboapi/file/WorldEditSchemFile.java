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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
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

  private final short width;
  private final short height;
  private final short length;
  private final int[] blocks;
  private final CompoundBinaryTag palette;
  private final ListBinaryTag blockEntities;

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

    this.blocks = new int[this.width * this.height * this.length];
    for (int i = 0; i < this.blocks.length; i++) {
      this.blocks[i] = ProtocolUtils.readVarInt(blockDataBuf);
    }
  }

  @Override
  public void toWorld(LimboFactory factory, VirtualWorld world, int offsetX, int offsetY, int offsetZ, int lightLevel) {
    int paletteSize = this.palette.keySet().size();
    int tilesX = (this.width + 15) / 16;
    int tilesZ = (this.length + 15) / 16;
    int totalTiles = tilesX * tilesZ;
    AtomicInteger parsedPalette = new AtomicInteger(0);
    AtomicInteger completedTiles = new AtomicInteger(0);
    final Logger logger = LimboAPI.getLogger();

    // Report import progress every 10 seconds until the import finishes. It covers both the
    // palette-parsing phase and the chunk-filling phase.
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
      Thread thread = new Thread(runnable, "LimboAPI-world-import-progress");
      thread.setDaemon(true);
      return thread;
    });
    final ScheduledFuture<?> progressTask = scheduler.scheduleAtFixedRate(() -> {
      int paletteDone = parsedPalette.get();
      int tilesDone = completedTiles.get();
      logger.info(String.format(
          "World import progress: palette %d/%d (%.1f%%), chunk tiles %d/%d (%.1f%%)",
          paletteDone, paletteSize, paletteDone * 100.0 / Math.max(1, paletteSize),
          tilesDone, totalTiles, tilesDone * 100.0 / Math.max(1, totalTiles)));
    }, 10, 10, TimeUnit.SECONDS);

    logger.info("Parsing NBT into paletted blocks.");
    VirtualBlock[] palettedBlocks = new VirtualBlock[paletteSize];
    this.palette.forEach((entry) -> {
      palettedBlocks[((IntBinaryTag) entry.getValue()).value()] = factory.createSimpleBlock(entry.getKey());
      parsedPalette.incrementAndGet();
    });
    logger.info("Palette parsed: 100% ({}/{}).", paletteSize, paletteSize);
    logger.info("Writing paletted blocks into chunks.");

    // Fill the world chunk by chunk (16x16 tiles), in parallel. Each thread owns whole tiles, so a
    // SimpleChunk is never written by more than one thread at a time (SimpleWorld is now safe for
    // concurrent chunk creation). Air blocks are skipped, so fully empty sections are never allocated,
    // which speeds up and reduces RAM usage of large/tall schematic imports considerably.
    IntStream.range(0, totalTiles).parallel().forEach(tileIndex -> {
      int chunkOffsetX = (tileIndex / tilesZ) * 16;
      int chunkOffsetZ = (tileIndex % tilesZ) * 16;
      int tileWidth = Math.min(16, this.width - chunkOffsetX);
      int tileLength = Math.min(16, this.length - chunkOffsetZ);

      VirtualChunk chunk = world.getChunkOrNew(offsetX + chunkOffsetX, offsetZ + chunkOffsetZ);
      for (int posX = 0; posX < tileWidth; ++posX) {
        for (int posZ = 0; posZ < tileLength; ++posZ) {
          for (int posY = 0; posY < this.height; ++posY) {
            int index = (posY * this.length + chunkOffsetZ + posZ) * this.width + chunkOffsetX + posX;
            VirtualBlock block = palettedBlocks[this.blocks[index]];
            if (!block.isAir()) {
              chunk.setBlock(posX, posY + offsetY, posZ, block);
            }
          }
        }
      }

      completedTiles.incrementAndGet();
    });

    progressTask.cancel(false);
    scheduler.shutdown();
    logger.info("World import finished: 100% ({} palette entries, {} chunk tiles).", paletteSize, totalTiles);
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
}
