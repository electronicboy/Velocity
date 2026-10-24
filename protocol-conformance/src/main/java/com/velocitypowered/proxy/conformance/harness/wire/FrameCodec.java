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

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.function.IntSupplier;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Minecraft packet framing: a VarInt length prefix and, once compression is enabled, a VarInt
 * uncompressed-length header followed by either the raw packet ({@code 0}) or a zlib stream.
 *
 * <p>Packets are always handed in and out as {@code VarInt id + payload}, decompressed.</p>
 */
public final class FrameCodec {

  /** The largest frame vanilla will accept: three VarInt bytes of length. */
  public static final int MAX_FRAME = 2097151;
  /** The largest uncompressed packet vanilla will accept. */
  public static final int MAX_UNCOMPRESSED = 8388608;

  private int threshold = -1;
  private final IntSupplier thresholdSource;

  /**
   * Creates a codec whose threshold is set explicitly.
   */
  public FrameCodec() {
    this.thresholdSource = null;
  }

  /**
   * Creates a codec that asks {@code thresholdSource} for the threshold once each frame has been
   * read. A relay needs this: compression switches on in one direction because of a packet seen
   * in the other, possibly while this side is already blocked waiting for its next frame.
   *
   * @param thresholdSource supplies the current threshold
   */
  public FrameCodec(IntSupplier thresholdSource) {
    this.thresholdSource = thresholdSource;
  }

  /**
   * Enables compression for packets of at least {@code threshold} bytes, or disables it when
   * negative.
   *
   * @param threshold the threshold
   */
  public void setCompressionThreshold(int threshold) {
    this.threshold = threshold;
  }

  public int compressionThreshold() {
    return thresholdSource == null ? threshold : thresholdSource.getAsInt();
  }

  /**
   * Frames a packet.
   *
   * @param packet {@code id + payload}
   * @return the bytes to put on the wire
   */
  public byte[] encode(byte[] packet) {
    Buf body = new Buf();
    int threshold = compressionThreshold();
    if (threshold >= 0) {
      if (packet.length >= threshold) {
        body.writeVarInt(packet.length);
        body.writeBytes(deflate(packet));
      } else {
        body.writeVarInt(0);
        body.writeBytes(packet);
      }
    } else {
      body.writeBytes(packet);
    }
    byte[] bodyBytes = body.toByteArray();
    return new Buf().writeVarInt(bodyBytes.length).writeBytes(bodyBytes).toByteArray();
  }

  /**
   * Reads the next frame.
   *
   * @param in the (already decrypted) stream
   * @return the decompressed {@code id + payload}, or empty on a clean end of stream
   * @throws IOException on a read error or a malformed frame
   */
  public Optional<byte[]> decode(InputStream in) throws IOException {
    int length = Buf.readVarIntFrom(in);
    if (length == -1) {
      return Optional.empty();
    }
    if (length <= 0 || length > MAX_FRAME) {
      throw new IOException("bad frame length " + length);
    }
    byte[] frame = in.readNBytes(length);
    if (frame.length != length) {
      throw new EOFException("truncated frame: wanted " + length + ", got " + frame.length);
    }
    if (compressionThreshold() < 0) {
      return Optional.of(frame);
    }

    Buf buf = new Buf(frame);
    int dataLength = buf.readVarInt();
    byte[] rest = buf.readRemaining();
    if (dataLength == 0) {
      return Optional.of(rest);
    }
    if (dataLength > MAX_UNCOMPRESSED) {
      throw new IOException("bad uncompressed length " + dataLength);
    }
    return Optional.of(inflate(rest, dataLength));
  }

  private static byte[] deflate(byte[] input) {
    Deflater deflater = new Deflater();
    try {
      deflater.setInput(input);
      deflater.finish();
      ByteArrayOutputStream out = new ByteArrayOutputStream(input.length / 2 + 16);
      byte[] chunk = new byte[8192];
      while (!deflater.finished()) {
        int n = deflater.deflate(chunk);
        out.write(chunk, 0, n);
      }
      return out.toByteArray();
    } finally {
      deflater.end();
    }
  }

  private static byte[] inflate(byte[] input, int expected) throws IOException {
    Inflater inflater = new Inflater();
    try {
      inflater.setInput(input);
      byte[] out = new byte[expected];
      int total = 0;
      while (total < expected) {
        int n = inflater.inflate(out, total, expected - total);
        if (n == 0 && (inflater.finished() || inflater.needsInput())) {
          break;
        }
        total += n;
      }
      if (total != expected) {
        throw new IOException("decompressed " + total + " bytes, header claimed " + expected);
      }
      return out;
    } catch (DataFormatException e) {
      throw new IOException("bad zlib stream", e);
    } finally {
      inflater.end();
    }
  }
}
