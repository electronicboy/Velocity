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
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/**
 * A minimal Minecraft wire buffer: sequential reads over a byte array, or appends to a growable
 * one. Written from the protocol documentation rather than shared with any proxy, so that it can
 * act as an independent oracle.
 */
public final class Buf {

  private byte[] data;
  private int readerIndex;
  private int writerIndex;

  /**
   * Creates an empty buffer for writing.
   */
  public Buf() {
    this.data = new byte[64];
  }

  /**
   * Wraps {@code bytes} for reading.
   *
   * @param bytes the bytes to read
   */
  public Buf(byte[] bytes) {
    this.data = bytes;
    this.writerIndex = bytes.length;
  }

  public int readableBytes() {
    return writerIndex - readerIndex;
  }

  public int readerIndex() {
    return readerIndex;
  }

  /**
   * Reads one byte.
   *
   * @return the byte
   */
  public byte readByte() {
    ensureReadable(1);
    return data[readerIndex++];
  }

  public int readUnsignedByte() {
    return readByte() & 0xFF;
  }

  public boolean readBoolean() {
    return readByte() != 0;
  }

  public short readShort() {
    return (short) ((readUnsignedByte() << 8) | readUnsignedByte());
  }

  public int readUnsignedShort() {
    return readShort() & 0xFFFF;
  }

  public int readInt() {
    return (readUnsignedByte() << 24) | (readUnsignedByte() << 16) | (readUnsignedByte() << 8)
        | readUnsignedByte();
  }

  public long readLong() {
    return ((long) readInt() << 32) | (readInt() & 0xFFFFFFFFL);
  }

  public float readFloat() {
    return Float.intBitsToFloat(readInt());
  }

  public double readDouble() {
    return Double.longBitsToDouble(readLong());
  }

  /**
   * Reads a VarInt.
   *
   * @return the value
   */
  public int readVarInt() {
    int value = 0;
    for (int i = 0; i < 5; i++) {
      byte b = readByte();
      value |= (b & 0x7F) << (i * 7);
      if ((b & 0x80) == 0) {
        return value;
      }
    }
    throw new IllegalStateException("VarInt too long");
  }

  /**
   * Reads a VarLong.
   *
   * @return the value
   */
  public long readVarLong() {
    long value = 0;
    for (int i = 0; i < 10; i++) {
      byte b = readByte();
      value |= (long) (b & 0x7F) << (i * 7);
      if ((b & 0x80) == 0) {
        return value;
      }
    }
    throw new IllegalStateException("VarLong too long");
  }

  /**
   * Reads a VarInt-prefixed UTF-8 string.
   *
   * @return the string
   */
  public String readString() {
    int length = readVarInt();
    if (length < 0 || length > readableBytes()) {
      throw new IllegalStateException("bad string length " + length);
    }
    String value = new String(data, readerIndex, length, StandardCharsets.UTF_8);
    readerIndex += length;
    return value;
  }

  public UUID readUuid() {
    return new UUID(readLong(), readLong());
  }

  /**
   * Reads {@code length} raw bytes.
   *
   * @param length how many bytes
   * @return the bytes
   */
  public byte[] readBytes(int length) {
    ensureReadable(length);
    byte[] out = Arrays.copyOfRange(data, readerIndex, readerIndex + length);
    readerIndex += length;
    return out;
  }

  public byte[] readByteArray() {
    return readBytes(readVarInt());
  }

  public byte[] readRemaining() {
    return readBytes(readableBytes());
  }

  /**
   * Skips {@code length} bytes.
   *
   * @param length how many bytes
   */
  public void skip(int length) {
    ensureReadable(length);
    readerIndex += length;
  }

  /**
   * Appends one byte.
   *
   * @param value the byte
   * @return this buffer
   */
  public Buf writeByte(int value) {
    ensureWritable(1);
    data[writerIndex++] = (byte) value;
    return this;
  }

  public Buf writeBoolean(boolean value) {
    return writeByte(value ? 1 : 0);
  }

  public Buf writeShort(int value) {
    return writeByte(value >>> 8).writeByte(value);
  }

  public Buf writeInt(int value) {
    return writeByte(value >>> 24).writeByte(value >>> 16).writeByte(value >>> 8).writeByte(value);
  }

  public Buf writeLong(long value) {
    return writeInt((int) (value >>> 32)).writeInt((int) value);
  }

  public Buf writeFloat(float value) {
    return writeInt(Float.floatToIntBits(value));
  }

  public Buf writeDouble(double value) {
    return writeLong(Double.doubleToLongBits(value));
  }

  /**
   * Appends a VarInt.
   *
   * @param value the value
   * @return this buffer
   */
  public Buf writeVarInt(int value) {
    while ((value & ~0x7F) != 0) {
      writeByte((value & 0x7F) | 0x80);
      value >>>= 7;
    }
    return writeByte(value);
  }

  /**
   * Appends a VarLong.
   *
   * @param value the value
   * @return this buffer
   */
  public Buf writeVarLong(long value) {
    while ((value & ~0x7FL) != 0) {
      writeByte((int) ((value & 0x7F) | 0x80));
      value >>>= 7;
    }
    return writeByte((int) value);
  }

  /**
   * Appends a VarInt-prefixed UTF-8 string.
   *
   * @param value the string
   * @return this buffer
   */
  public Buf writeString(String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    writeVarInt(bytes.length);
    return writeBytes(bytes);
  }

  public Buf writeUuid(UUID value) {
    return writeLong(value.getMostSignificantBits()).writeLong(value.getLeastSignificantBits());
  }

  /**
   * Appends raw bytes.
   *
   * @param bytes the bytes
   * @return this buffer
   */
  public Buf writeBytes(byte[] bytes) {
    ensureWritable(bytes.length);
    System.arraycopy(bytes, 0, data, writerIndex, bytes.length);
    writerIndex += bytes.length;
    return this;
  }

  public Buf writeByteArray(byte[] bytes) {
    return writeVarInt(bytes.length).writeBytes(bytes);
  }

  public byte[] toByteArray() {
    return Arrays.copyOfRange(data, readerIndex, writerIndex);
  }

  /**
   * Returns the encoded length of {@code value} as a VarInt.
   *
   * @param value the value
   * @return its length in bytes
   */
  public static int varIntSize(int value) {
    int size = 1;
    while ((value & ~0x7F) != 0) {
      size++;
      value >>>= 7;
    }
    return size;
  }

  /**
   * Reads a VarInt directly from a stream.
   *
   * @param in the stream
   * @return the value, or {@code -1} on a clean end of stream before the first byte
   * @throws IOException on a read error or a truncated VarInt
   */
  public static int readVarIntFrom(InputStream in) throws IOException {
    int value = 0;
    for (int i = 0; i < 5; i++) {
      int b = in.read();
      if (b == -1) {
        if (i == 0) {
          return -1;
        }
        throw new EOFException("truncated VarInt");
      }
      value |= (b & 0x7F) << (i * 7);
      if ((b & 0x80) == 0) {
        return value;
      }
    }
    throw new IOException("VarInt too long");
  }

  /**
   * Concatenates a VarInt packet id and a payload.
   *
   * @param id the packet id
   * @param payload the packet body
   * @return {@code id + payload}
   */
  public static byte[] packet(int id, byte[] payload) {
    ByteArrayOutputStream out = new ByteArrayOutputStream(payload.length + 5);
    Buf idBuf = new Buf().writeVarInt(id);
    out.writeBytes(idBuf.toByteArray());
    out.writeBytes(payload);
    return out.toByteArray();
  }

  private void ensureReadable(int length) {
    if (length < 0 || readerIndex + length > writerIndex) {
      throw new IllegalStateException("read past end: need " + length + ", have "
          + readableBytes());
    }
  }

  private void ensureWritable(int length) {
    if (writerIndex + length > data.length) {
      data = Arrays.copyOf(data, Math.max(data.length * 2, writerIndex + length));
    }
  }
}
