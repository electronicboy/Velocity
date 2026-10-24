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

package com.velocitypowered.proxy.conformance.harness.wire;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Tests the harness's own wire primitives against hand-computed encodings.
 */
class BufTest {

  private static byte[] hex(String hex) {
    return HexFormat.of().parseHex(hex);
  }

  @Test
  void varIntRoundTripsWithExpectedLengths() {
    int[] values = {0, 1, 127, 128, 255, 2147483647, -1};
    int[] sizes = {1, 1, 1, 2, 2, 5, 5};
    for (int i = 0; i < values.length; i++) {
      byte[] encoded = new Buf().writeVarInt(values[i]).toByteArray();
      assertEquals(sizes[i], encoded.length, "encoded length of " + values[i]);
      assertEquals(sizes[i], Buf.varIntSize(values[i]), "varIntSize of " + values[i]);
      Buf read = new Buf(encoded);
      assertEquals(values[i], read.readVarInt());
      assertEquals(0, read.readableBytes());
    }
  }

  @Test
  void varIntMatchesKnownEncodings() {
    assertArrayEquals(hex("00"), new Buf().writeVarInt(0).toByteArray());
    assertArrayEquals(hex("7f"), new Buf().writeVarInt(127).toByteArray());
    assertArrayEquals(hex("8001"), new Buf().writeVarInt(128).toByteArray());
    assertArrayEquals(hex("ff01"), new Buf().writeVarInt(255).toByteArray());
    assertArrayEquals(hex("ffffffff07"), new Buf().writeVarInt(Integer.MAX_VALUE).toByteArray());
    assertArrayEquals(hex("ffffffff0f"), new Buf().writeVarInt(-1).toByteArray());
  }

  @Test
  void varIntLongerThanFiveBytesIsRejected() {
    Buf buf = new Buf(hex("ffffffffff01"));
    assertThrows(IllegalStateException.class, buf::readVarInt);
  }

  @Test
  void varLongRoundTrips() {
    long[] values = {0, 127, 128, 2147483648L, Long.MAX_VALUE, -1};
    int[] sizes = {1, 1, 2, 5, 9, 10};
    for (int i = 0; i < values.length; i++) {
      byte[] encoded = new Buf().writeVarLong(values[i]).toByteArray();
      assertEquals(sizes[i], encoded.length, "encoded length of " + values[i]);
      Buf read = new Buf(encoded);
      assertEquals(values[i], read.readVarLong());
      assertEquals(0, read.readableBytes());
    }
    assertArrayEquals(hex("ffffffffffffffffff01"), new Buf().writeVarLong(-1).toByteArray());
  }

  @Test
  void stringIsPrefixedWithItsUtf8ByteLength() {
    // 2 + 3 + 4 bytes of UTF-8, but only 4 UTF-16 chars.
    String value = "é✓🎉";
    byte[] encoded = new Buf().writeString(value).toByteArray();
    assertEquals(10, encoded.length);
    assertEquals(9, encoded[0]);
    assertArrayEquals(value.getBytes(StandardCharsets.UTF_8),
        Arrays.copyOfRange(encoded, 1, encoded.length));
    Buf read = new Buf(encoded);
    assertEquals(value, read.readString());
    assertEquals(0, read.readableBytes());
  }

  @Test
  void stringLongerThanTheBufferIsRejected() {
    Buf buf = new Buf(hex("05616263"));
    assertThrows(IllegalStateException.class, buf::readString);
  }

  @Test
  void uuidIsSixteenBigEndianBytes() {
    UUID id = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
    byte[] encoded = new Buf().writeUuid(id).toByteArray();
    assertArrayEquals(hex("069a79f444e94726a5befca90e38aaf5"), encoded);
    assertEquals(id, new Buf(encoded).readUuid());
  }

  @Test
  void readVarIntFromReturnsMinusOneOnCleanEof() throws IOException {
    assertEquals(-1, Buf.readVarIntFrom(new ByteArrayInputStream(new byte[0])));

    ByteArrayInputStream in = new ByteArrayInputStream(hex("ac02"));
    assertEquals(300, Buf.readVarIntFrom(in));
    assertEquals(-1, Buf.readVarIntFrom(in));
  }

  @Test
  void readVarIntFromThrowsOnTruncatedVarInt() {
    assertThrows(EOFException.class,
        () -> Buf.readVarIntFrom(new ByteArrayInputStream(hex("80"))));
    assertThrows(EOFException.class,
        () -> Buf.readVarIntFrom(new ByteArrayInputStream(hex("ffffff"))));
  }

  @Test
  void readVarIntFromRejectsOverlongVarInt() {
    assertThrows(IOException.class,
        () -> Buf.readVarIntFrom(new ByteArrayInputStream(hex("ffffffffff01"))));
  }

  @Test
  void packetPrependsVarIntId() {
    assertArrayEquals(hex("8001aabb"), Buf.packet(128, hex("aabb")));
    assertArrayEquals(hex("26"), Buf.packet(0x26, new byte[0]));
  }
}
