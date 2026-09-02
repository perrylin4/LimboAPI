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
import net.elytrium.limboapi.api.LimboFactory;
import net.elytrium.limboapi.api.chunk.VirtualBlock;
import net.elytrium.limboapi.api.chunk.VirtualChunk;
import net.elytrium.limboapi.api.chunk.VirtualWorld;
import net.elytrium.limboapi.api.file.WorldFile;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.IntBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;

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
    VirtualBlock[] palettedBlocks = new VirtualBlock[this.palette.keySet().size()];
    this.palette.forEach((entry) -> palettedBlocks[((IntBinaryTag) entry.getValue()).value()] = factory.createSimpleBlock(entry.getKey()));

    // Fill the world chunk by chunk instead of block by block: a single chunk lookup per 16x16 tile
    // (world.setBlock would otherwise do a 3x3 hash-map lookup per block) and skip air blocks entirely.
    // Sections that end up with no blocks stay unallocated, which speeds up and reduces RAM usage of
    // large/tall schematic imports considerably.
    for (int chunkOffsetX = 0; chunkOffsetX < this.width; chunkOffsetX += 16) {
      int tileWidth = Math.min(16, this.width - chunkOffsetX);
      for (int chunkOffsetZ = 0; chunkOffsetZ < this.length; chunkOffsetZ += 16) {
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
      }
    }

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

    world.fillSkyLight(lightLevel);
  }
}
