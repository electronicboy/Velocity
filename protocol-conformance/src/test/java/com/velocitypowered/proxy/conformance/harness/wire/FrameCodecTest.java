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
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.Inflater;
import org.junit.jupiter.api.Test;

/**
 * Tests framing and the vanilla compression threshold rule.
 */
class FrameCodecTest {

  private static byte[] hex(String hex) {
    return HexFormat.of().parseHex(hex);
  }

  /** A packet of exactly {@code size} bytes: id 0x26 followed by a recognisable pattern. */
  private static byte[] packetOfSize(int size) {
    byte[] packet = new byte[size];
    packet[0] = 0x26;
    for (int i = 1; i < size; i++) {
      packet[i] = (byte) (i * 31);
    }
    return packet;
  }

  private static byte[] inflate(byte[] input, int size) throws Exception {
    Inflater inflater = new Inflater();
    try {
      inflater.setInput(input);
      byte[] out = new byte[size];
      assertEquals(size, inflater.inflate(out));
      assertTrue(inflater.finished());
      return out;
    } finally {
      inflater.end();
    }
  }

  private static byte[] decodeOne(FrameCodec codec, byte[] frame) throws IOException {
    return codec.decode(new ByteArrayInputStream(frame)).orElseThrow();
  }

  @Test
  void uncompressedFrameIsLengthAndPacket() throws IOException {
    FrameCodec codec = new FrameCodec();
    byte[] packet = hex("26010203");
    byte[] frame = codec.encode(packet);
    assertArrayEquals(hex("0426010203"), frame);
    assertArrayEquals(packet, decodeOne(codec, frame));
  }

  @Test
  void packetAtThresholdIsCompressed() throws Exception {
    FrameCodec codec = new FrameCodec();
    codec.setCompressionThreshold(256);
    byte[] packet = packetOfSize(256);
    byte[] frame = codec.encode(packet);

    Buf buf = new Buf(frame);
    int frameLength = buf.readVarInt();
    assertEquals(buf.readableBytes(), frameLength);
    assertEquals(256, buf.readVarInt());
    assertArrayEquals(packet, inflate(buf.readRemaining(), 256));
    assertArrayEquals(packet, decodeOne(codec, frame));
  }

  @Test
  void packetBelowThresholdIsFramedRaw() throws IOException {
    FrameCodec codec = new FrameCodec();
    codec.setCompressionThreshold(256);
    byte[] packet = packetOfSize(255);
    byte[] frame = codec.encode(packet);

    Buf buf = new Buf(frame);
    assertEquals(256, buf.readVarInt());
    assertEquals(0, buf.readVarInt());
    assertArrayEquals(packet, buf.readRemaining());
    assertArrayEquals(packet, decodeOne(codec, frame));
  }

  @Test
  void decodesHandBuiltFrameWithZeroDataLength() throws IOException {
    FrameCodec codec = new FrameCodec();
    codec.setCompressionThreshold(256);
    // frame length 3, data length 0 (not compressed), id 0x26, payload 0x07
    assertArrayEquals(hex("2607"), decodeOne(codec, hex("03002607")));
  }

  @Test
  void supplierThresholdIsReadLazily() throws IOException {
    AtomicInteger threshold = new AtomicInteger(-1);
    FrameCodec codec = new FrameCodec(threshold::get);
    byte[] packet = packetOfSize(10);

    assertArrayEquals(new Buf().writeVarInt(10).writeBytes(packet).toByteArray(),
        codec.encode(packet));
    threshold.set(0);
    Buf compressed = new Buf(codec.encode(packet));
    compressed.readVarInt();
    assertEquals(10, compressed.readVarInt());

    // The same hand-built frame means two different things depending on the threshold at the
    // time it is decoded.
    byte[] frame = hex("03002607");
    threshold.set(-1);
    assertArrayEquals(hex("002607"), decodeOne(codec, frame));
    threshold.set(64);
    assertArrayEquals(hex("2607"), decodeOne(codec, frame));
  }

  @Test
  void supplierThresholdIsReadAfterTheFrameArrives() throws IOException {
    // A relay is blocked in decode() when the other direction turns compression on; the frame it
    // then receives must be parsed under the new threshold.
    AtomicInteger threshold = new AtomicInteger(-1);
    FrameCodec codec = new FrameCodec(threshold::get);
    InputStream in = new FilterInputStream(new ByteArrayInputStream(hex("03002607"))) {
      @Override
      public int read() throws IOException {
        int b = super.read();
        threshold.set(64);
        return b;
      }
    };
    assertArrayEquals(hex("2607"), codec.decode(in).orElseThrow());
  }

  @Test
  void decodeReturnsEmptyOnEof() throws IOException {
    FrameCodec codec = new FrameCodec();
    assertEquals(Optional.empty(), codec.decode(new ByteArrayInputStream(new byte[0])));

    ByteArrayInputStream in = new ByteArrayInputStream(hex("0126"));
    assertArrayEquals(hex("26"), codec.decode(in).orElseThrow());
    assertEquals(Optional.empty(), codec.decode(in));
  }

  @Test
  void truncatedFrameThrows() {
    FrameCodec codec = new FrameCodec();
    assertThrows(IOException.class,
        () -> codec.decode(new ByteArrayInputStream(hex("052601"))));
  }

  @Test
  void compressedFrameClaimingMoreThanItHoldsThrows() throws IOException {
    FrameCodec sender = new FrameCodec();
    sender.setCompressionThreshold(0);
    byte[] honest = sender.encode(packetOfSize(100));
    Buf buf = new Buf(honest);
    buf.readVarInt();
    assertEquals(100, buf.readVarInt());
    byte[] zlib = buf.readRemaining();

    // Same zlib stream, but the header claims 200 bytes.
    byte[] body = new Buf().writeVarInt(200).writeBytes(zlib).toByteArray();
    byte[] lying = new Buf().writeVarInt(body.length).writeBytes(body).toByteArray();

    FrameCodec receiver = new FrameCodec();
    receiver.setCompressionThreshold(64);
    IOException e = assertThrows(IOException.class, () -> decodeOne(receiver, lying));
    assertTrue(e.getMessage().contains("decompressed 100 bytes, header claimed 200"),
        e.getMessage());
  }

  @Test
  void compressedFrameWithPresetDictionaryThrowsInsteadOfSpinning() {
    // A zlib header with FDICT set (0x78 0xbb), a dictionary id, then a few bytes. The inflater
    // makes no progress until given a dictionary, which vanilla never supplies.
    byte[] zlib = hex("78bb0000000100");
    byte[] body = new Buf().writeVarInt(100).writeBytes(zlib).toByteArray();
    byte[] frame = new Buf().writeVarInt(body.length).writeBytes(body).toByteArray();
    FrameCodec codec = new FrameCodec();
    codec.setCompressionThreshold(64);
    assertTimeoutPreemptively(Duration.ofSeconds(5),
        () -> assertThrows(IOException.class, () -> decodeOne(codec, frame)));
  }

  @Test
  void oversizedFrameLengthIsRejected() {
    FrameCodec codec = new FrameCodec();
    byte[] frame = Arrays.copyOf(new Buf().writeVarInt(FrameCodec.MAX_FRAME + 1).toByteArray(),
        8);
    assertThrows(IOException.class, () -> codec.decode(new ByteArrayInputStream(frame)));
  }
}
