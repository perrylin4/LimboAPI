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
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import net.elytrium.limboapi.LimboAPI;
import net.elytrium.limboapi.api.LimboFactory;
import net.elytrium.limboapi.api.chunk.VirtualBlock;
import net.elytrium.limboapi.api.chunk.VirtualChunk;
import net.elytrium.limboapi.api.chunk.VirtualWorld;
import net.elytrium.limboapi.api.file.WorldFile;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.ByteArrayBinaryTag;
import net.kyori.adventure.nbt.ByteBinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.DoubleBinaryTag;
import net.kyori.adventure.nbt.FloatBinaryTag;
import net.kyori.adventure.nbt.IntArrayBinaryTag;
import net.kyori.adventure.nbt.IntBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.kyori.adventure.nbt.LongArrayBinaryTag;
import net.kyori.adventure.nbt.LongBinaryTag;
import net.kyori.adventure.nbt.ShortBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;
import org.slf4j.Logger;

public class WorldEditSchemFile implements WorldFile {

  private static final List<String> AIR_BLOCKS = List.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air");

  private static final int TAG_END = 0;
  private static final int TAG_BYTE = 1;
  private static final int TAG_SHORT = 2;
  private static final int TAG_INT = 3;
  private static final int TAG_LONG = 4;
  private static final int TAG_FLOAT = 5;
  private static final int TAG_DOUBLE = 6;
  private static final int TAG_BYTE_ARRAY = 7;
  private static final int TAG_STRING = 8;
  private static final int TAG_LIST = 9;
  private static final int TAG_COMPOUND = 10;
  private static final int TAG_INT_ARRAY = 11;
  private static final int TAG_LONG_ARRAY = 12;

  private short width;
  private short height;
  private short length;
  private CompoundBinaryTag palette;
  private ListBinaryTag blockEntities;

  // Transient state used only while streaming the file from disk. Never grows large.
  private Map<String, Object> paletteMap;
  private List<Object> blockEntitiesList;
  private boolean dataDecoded;

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

    this.decode(blockDataBuf);
  }

  public WorldEditSchemFile(Path file) throws IOException {
    try (InputStream stream = Files.newInputStream(file)) {
      this.init(stream);
    }
  }

  public WorldEditSchemFile(InputStream gzipStream) throws IOException {
    this.init(gzipStream);
  }

  private void init(InputStream gzipStream) throws IOException {
    try (GZIPInputStream gzip = new GZIPInputStream(gzipStream)) {
      NbtInput in = new NbtInput(gzip);
      int rootType = in.u8();
      if (rootType != TAG_COMPOUND) {
        throw new IllegalArgumentException("Schematic root is not a compound tag");
      }
      in.skipString(); // root tag name
      this.readCompound(in);
    }

    if (!this.dataDecoded) {
      throw new IllegalArgumentException("Invalid worldedit file format. Please open an issue on GitHub.");
    }

    this.palette = (CompoundBinaryTag) toBinaryTag(Objects.requireNonNull(this.paletteMap, "Missing Palette"));
    this.blockEntities = this.blockEntitiesList == null
        ? ListBinaryTag.empty()
        : (ListBinaryTag) toBinaryTag(this.blockEntitiesList);
  }

  private void readCompound(NbtInput in) throws IOException {
    int type;
    while ((type = in.u8()) != TAG_END) {
      String name = in.string();
      this.readTag(in, type, name);
    }
  }

  private void readTag(NbtInput in, int type, String name) throws IOException {
    switch (name) {
      case "Width":
        requireType(type, TAG_SHORT, name);
        this.width = in.s16();
        return;
      case "Height":
        requireType(type, TAG_SHORT, name);
        this.height = in.s16();
        return;
      case "Length":
        requireType(type, TAG_SHORT, name);
        this.length = in.s16();
        return;
      case "Palette":
        requireType(type, TAG_COMPOUND, name);
        this.paletteMap = readCompoundPayload(in);
        return;
      case "BlockEntities":
        requireType(type, TAG_LIST, name);
        this.blockEntitiesList = readListPayload(in);
        return;
      case "Data":
      case "BlockData":
        requireType(type, TAG_BYTE_ARRAY, name);
        if (!this.dataDecoded) {
          this.dataDecoded = true;
          this.decodeVarIntStream(in, in.i32());
        } else {
          in.skipBytes(in.i32());
        }
        return;
      default:
        // Recurse into compounds so nested "Schematic"/"Blocks" containers are still visited.
        if (type == TAG_COMPOUND) {
          this.readCompound(in);
        } else if (type == TAG_LIST) {
          readListPayload(in); // small in practice (e.g. Metadata lists), discard
        } else {
          skipPayload(in, type);
        }
    }
  }

  private void decodeVarIntStream(NbtInput in, long byteCount) throws IOException {
    if (this.width <= 0 || this.height <= 0 || this.length <= 0 || this.paletteMap == null) {
      throw new IllegalArgumentException("Schematic is missing dimensions or palette before block data.");
    }

    int paletteSize = this.paletteMap.size();
    boolean[] isAirIndex = new boolean[Math.max(1, paletteSize)];
    for (String air : AIR_BLOCKS) {
      Object value = this.paletteMap.get(air);
      if (value instanceof Number) {
        int airIndex = ((Number) value).intValue();
        if (airIndex >= 0 && airIndex < isAirIndex.length) {
          isAirIndex[airIndex] = true;
        }
      }
    }

    Logger logger = LimboAPI.getLogger();
    logger.info("Decoding schematic block data into a sparse chunk structure.");

    long totalBlocks = (long) this.width * this.height * this.length;
    long widthByLength = (long) this.width * this.length;
    byte[] chunk = new byte[65536];
    long remaining = byteCount;
    long index = 0;
    int carry = 0;
    int shift = 0;

    while (remaining > 0) {
      int want = (int) Math.min(remaining, chunk.length);
      in.readFully(chunk, 0, want);
      remaining -= want;

      for (int i = 0; i < want; i++) {
        int b = chunk[i] & 0xFF;
        carry |= (b & 0x7F) << shift;
        if ((b & 0x80) == 0) {
          if (index >= totalBlocks) {
            throw new IllegalArgumentException("Block data contains more blocks than expected.");
          }

          int paletteIndex = carry;
          if (!(paletteIndex >= 0 && paletteIndex < isAirIndex.length && isAirIndex[paletteIndex])) {
            int posX = (int) (index % this.width);
            int posZ = (int) ((index / this.width) % this.length);
            int posY = (int) (index / widthByLength);

            long chunkKey = getChunkIndex(posX >> 4, posZ >> 4);
            this.chunks.computeIfAbsent(chunkKey, key -> new ArrayList<>())
                .add(new int[] {posX & 15, posY, posZ & 15, paletteIndex});
          }
          carry = 0;
          shift = 0;
          index++;
        } else {
          shift += 7;
          if (shift > 28) {
            throw new IllegalArgumentException("VarInt is too big");
          }
        }
      }
    }

    if (index != totalBlocks) {
      throw new IllegalArgumentException("Block data contains fewer blocks than expected: " + index + " != " + totalBlocks);
    }

    logger.info("Decoded {} chunks containing non-air blocks.", this.chunks.size());
  }

  private void decode(ByteBuf blockDataBuf) {
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

  private static void requireType(int actual, int expected, String name) {
    if (actual != expected) {
      throw new IllegalArgumentException("Unexpected NBT type for " + name + ": " + actual + " (expected " + expected + ")");
    }
  }

  private static Map<String, Object> readCompoundPayload(NbtInput in) throws IOException {
    Map<String, Object> map = new LinkedHashMap<>();
    int type;
    while ((type = in.u8()) != TAG_END) {
      String name = in.string();
      map.put(name, readPayload(in, type));
    }
    return map;
  }

  private static List<Object> readListPayload(NbtInput in) throws IOException {
    int elementType = in.u8();
    int length = in.i32();
    if (length < 0 || length > 65536) {
      throw new IllegalArgumentException("Unreasonable list length: " + length);
    }
    List<Object> list = new ArrayList<>(length);
    for (int i = 0; i < length; i++) {
      list.add(readPayload(in, elementType));
    }
    return list;
  }

  private static Object readPayload(NbtInput in, int type) throws IOException {
    switch (type) {
      case TAG_BYTE:
        return (byte) in.u8();
      case TAG_SHORT:
        return in.s16();
      case TAG_INT:
        return in.i32();
      case TAG_LONG:
        return in.i64();
      case TAG_FLOAT:
        return Float.intBitsToFloat(in.i32());
      case TAG_DOUBLE:
        return Double.longBitsToDouble(in.i64());
      case TAG_BYTE_ARRAY: {
        int length = in.i32();
        if (length < 0) {
          throw new IllegalArgumentException("Negative byte array length");
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes, 0, length);
        return bytes;
      }
      case TAG_STRING:
        return in.string();
      case TAG_LIST:
        return readListPayload(in);
      case TAG_COMPOUND:
        return readCompoundPayload(in);
      case TAG_INT_ARRAY: {
        int length = in.i32();
        if (length < 0 || length > 65536) {
          throw new IllegalArgumentException("Unreasonable int array length: " + length);
        }
        int[] arr = new int[length];
        for (int i = 0; i < length; i++) {
          arr[i] = in.i32();
        }
        return arr;
      }
      case TAG_LONG_ARRAY: {
        int length = in.i32();
        if (length < 0 || length > 65536) {
          throw new IllegalArgumentException("Unreasonable long array length: " + length);
        }
        long[] arr = new long[length];
        for (int i = 0; i < length; i++) {
          arr[i] = in.i64();
        }
        return arr;
      }
      default:
        throw new IllegalArgumentException("Unknown NBT tag type: " + type);
    }
  }

  private static void skipPayload(NbtInput in, int type) throws IOException {
    switch (type) {
      case TAG_BYTE:
        in.skipBytes(1);
        return;
      case TAG_SHORT:
        in.skipBytes(2);
        return;
      case TAG_INT:
      case TAG_FLOAT:
        in.skipBytes(4);
        return;
      case TAG_LONG:
      case TAG_DOUBLE:
        in.skipBytes(8);
        return;
      case TAG_BYTE_ARRAY: {
        int length = in.i32();
        in.skipBytes(length);
        return;
      }
      case TAG_STRING:
        in.skipString();
        return;
      case TAG_LIST: {
        int elementType = in.u8();
        int length = in.i32();
        if (length < 0) {
          throw new IllegalArgumentException("Negative list length");
        }
        for (int i = 0; i < length; i++) {
          skipPayload(in, elementType);
        }
        return;
      }
      case TAG_COMPOUND: {
        int childType;
        while ((childType = in.u8()) != TAG_END) {
          in.skipString();
          skipPayload(in, childType);
        }
        return;
      }
      case TAG_INT_ARRAY: {
        int length = in.i32();
        in.skipBytes(length * 4L);
        return;
      }
      case TAG_LONG_ARRAY: {
        int length = in.i32();
        in.skipBytes(length * 8L);
        return;
      }
      default:
        throw new IllegalArgumentException("Unknown NBT tag type: " + type);
    }
  }

  private static BinaryTag toBinaryTag(Object value) {
    if (value instanceof Byte) {
      return ByteBinaryTag.byteBinaryTag((Byte) value);
    } else if (value instanceof Short) {
      return ShortBinaryTag.shortBinaryTag((Short) value);
    } else if (value instanceof Integer) {
      return IntBinaryTag.intBinaryTag((Integer) value);
    } else if (value instanceof Long) {
      return LongBinaryTag.longBinaryTag((Long) value);
    } else if (value instanceof Float) {
      return FloatBinaryTag.floatBinaryTag((Float) value);
    } else if (value instanceof Double) {
      return DoubleBinaryTag.doubleBinaryTag((Double) value);
    } else if (value instanceof String) {
      return StringBinaryTag.stringBinaryTag((String) value);
    } else if (value instanceof byte[]) {
      return ByteArrayBinaryTag.byteArrayBinaryTag((byte[]) value);
    } else if (value instanceof int[]) {
      return IntArrayBinaryTag.intArrayBinaryTag((int[]) value);
    } else if (value instanceof long[]) {
      return LongArrayBinaryTag.longArrayBinaryTag((long[]) value);
    } else if (value instanceof List) {
      List<BinaryTag> tags = new ArrayList<>();
      for (Object element : (List<?>) value) {
        tags.add(toBinaryTag(element));
      }
      return ListBinaryTag.from(tags);
    } else if (value instanceof Map) {
      CompoundBinaryTag.Builder builder = CompoundBinaryTag.builder();
      for (Map.Entry<String, Object> entry : ((Map<String, Object>) value).entrySet()) {
        builder.put(entry.getKey(), toBinaryTag(entry.getValue()));
      }
      return builder.build();
    } else {
      throw new IllegalArgumentException("Unsupported schematic value: " + value);
    }
  }

  private static final class NbtInput {
    private final InputStream in;

    NbtInput(InputStream in) {
      this.in = in;
    }

    int u8() throws IOException {
      int b = this.in.read();
      if (b < 0) {
        throw new IOException("Unexpected end of schematic NBT stream");
      }
      return b;
    }

    int u16() throws IOException {
      return (this.u8() << 8) | this.u8();
    }

    short s16() throws IOException {
      return (short) this.u16();
    }

    int i32() throws IOException {
      return (this.u8() << 24) | (this.u8() << 16) | (this.u8() << 8) | this.u8();
    }

    long i64() throws IOException {
      return ((long) this.i32() << 32) | (this.i32() & 0xFFFFFFFFL);
    }

    String string() throws IOException {
      int length = this.u16();
      byte[] bytes = new byte[length];
      this.readFully(bytes, 0, length);
      return new String(bytes, StandardCharsets.UTF_8);
    }

    void skipString() throws IOException {
      this.skipBytes(this.u16());
    }

    void readFully(byte[] buf, int off, int len) throws IOException {
      int read = 0;
      while (read < len) {
        int r = this.in.read(buf, off + read, len - read);
        if (r < 0) {
          throw new IOException("Unexpected end of schematic NBT stream");
        }
        read += r;
      }
    }

    void skipBytes(long n) throws IOException {
      long remaining = n;
      byte[] scratch = new byte[4096];
      while (remaining > 0) {
        int r = this.in.read(scratch, 0, (int) Math.min(remaining, scratch.length));
        if (r < 0) {
          throw new IOException("Unexpected end of schematic NBT stream");
        }
        remaining -= r;
      }
    }
  }
}
