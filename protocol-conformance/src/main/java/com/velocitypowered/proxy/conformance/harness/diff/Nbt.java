/*
 * Copyright (C) 2026 Velocity Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.velocitypowered.proxy.conformance.harness.diff;

import com.velocitypowered.proxy.conformance.harness.wire.Buf;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reads network NBT into a canonical string in which compound key order does not matter. Vanilla
 * stores compounds in hash maps, so key order on the wire carries no meaning; two encodings that
 * differ only in order are the same data.
 */
final class Nbt {

  private Nbt() {
  }

  /**
   * Reads one tag and returns its canonical form.
   *
   * @param buf positioned at the tag type
   * @param named whether the root carries a name (network NBT before 1.20.2)
   * @return the canonical form
   */
  static String readCanonical(Buf buf, boolean named) {
    int type = buf.readUnsignedByte();
    if (type == 0) {
      return "end";
    }
    if (named) {
      readUtf(buf);
    }
    return payload(buf, type, 0);
  }

  private static String payload(Buf buf, int type, int depth) {
    if (depth > 512) {
      throw new IllegalArgumentException("NBT nested too deeply");
    }
    return switch (type) {
      case 1 -> buf.readByte() + "b";
      case 2 -> buf.readShort() + "s";
      case 3 -> buf.readInt() + "i";
      case 4 -> buf.readLong() + "l";
      case 5 -> Float.floatToRawIntBits(buf.readFloat()) + "f";
      case 6 -> Double.doubleToRawLongBits(buf.readDouble()) + "d";
      case 7 -> "B" + HexFormat.of().formatHex(buf.readBytes(length(buf)));
      case 8 -> "\"" + readUtf(buf) + "\"";
      case 9 -> {
        int element = buf.readUnsignedByte();
        int count = buf.readInt();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
          sb.append(payload(buf, element, depth + 1)).append(',');
        }
        yield sb.append(']').toString();
      }
      case 10 -> {
        Map<String, String> entries = new TreeMap<>();
        while (true) {
          int child = buf.readUnsignedByte();
          if (child == 0) {
            break;
          }
          String name = readUtf(buf);
          entries.put(name, payload(buf, child, depth + 1));
        }
        yield entries.toString();
      }
      case 11 -> {
        int count = length(buf);
        StringBuilder sb = new StringBuilder("I[");
        for (int i = 0; i < count; i++) {
          sb.append(buf.readInt()).append(',');
        }
        yield sb.append(']').toString();
      }
      case 12 -> {
        int count = length(buf);
        StringBuilder sb = new StringBuilder("L[");
        for (int i = 0; i < count; i++) {
          sb.append(buf.readLong()).append(',');
        }
        yield sb.append(']').toString();
      }
      default -> throw new IllegalArgumentException("bad NBT tag type " + type);
    };
  }

  private static int length(Buf buf) {
    int length = buf.readInt();
    if (length < 0 || length > buf.readableBytes() * 8 + 8) {
      throw new IllegalArgumentException("bad NBT array length " + length);
    }
    return length;
  }

  private static String readUtf(Buf buf) {
    // Modified UTF-8; the harness only needs a faithful key, so plain UTF-8 decoding suffices.
    return new String(buf.readBytes(buf.readUnsignedShort()), StandardCharsets.UTF_8);
  }
}
