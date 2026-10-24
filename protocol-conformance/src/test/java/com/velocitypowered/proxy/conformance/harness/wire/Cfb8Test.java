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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.Random;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * Tests the byte-wise AES/CFB8 stream wrappers.
 */
class Cfb8Test {

  private static final byte[] SECRET = {
      0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77,
      (byte) 0x88, (byte) 0x99, (byte) 0xaa, (byte) 0xbb,
      (byte) 0xcc, (byte) 0xdd, (byte) 0xee, (byte) 0xff
  };

  private static byte[] plaintext(int length) {
    byte[] bytes = new byte[length];
    new Random(42).nextBytes(bytes);
    return bytes;
  }

  private static byte[] jdk(int mode, byte[] input) throws Exception {
    Cipher cipher = Cipher.getInstance("AES/CFB8/NoPadding");
    cipher.init(mode, new SecretKeySpec(SECRET, "AES"), new IvParameterSpec(SECRET));
    return cipher.doFinal(input);
  }

  private static byte[] encrypt(byte[] plaintext) throws Exception {
    ByteArrayOutputStream sink = new ByteArrayOutputStream();
    OutputStream out = Cfb8.encrypting(sink, SECRET);
    // Mix single-byte and bulk writes: both must share one cipher state.
    out.write(plaintext[0]);
    out.write(plaintext, 1, 99);
    for (int i = 100; i < 110; i++) {
      out.write(plaintext[i]);
    }
    out.write(plaintext, 110, plaintext.length - 110);
    out.flush();
    return sink.toByteArray();
  }

  @Test
  void encryptThenDecryptRoundTrips() throws Exception {
    byte[] plaintext = plaintext(1000);
    byte[] ciphertext = encrypt(plaintext);
    assertEquals(plaintext.length, ciphertext.length);
    assertFalse(Arrays.equals(plaintext, ciphertext));

    InputStream in = Cfb8.decrypting(new ByteArrayInputStream(ciphertext), SECRET);
    byte[] decrypted = new byte[plaintext.length];
    decrypted[0] = (byte) in.read();
    assertEquals(499, in.readNBytes(decrypted, 1, 499));
    assertEquals(500, in.readNBytes(decrypted, 500, 500));
    assertEquals(-1, in.read());
    assertArrayEquals(plaintext, decrypted);
  }

  @Test
  void matchesJdkCfb8WithSecretAsKeyAndIv() throws Exception {
    byte[] plaintext = plaintext(1000);
    byte[] ciphertext = encrypt(plaintext);
    assertArrayEquals(jdk(Cipher.ENCRYPT_MODE, plaintext), ciphertext);

    byte[] fromJdk = jdk(Cipher.ENCRYPT_MODE, plaintext);
    assertArrayEquals(plaintext,
        Cfb8.decrypting(new ByteArrayInputStream(fromJdk), SECRET).readAllBytes());
  }

  @Test
  void decryptedBytesAreAvailableWithoutFurtherInput() throws Exception {
    // The JDK's CipherInputStream holds plaintext back until more ciphertext arrives, which
    // deadlocks a request/response exchange. Every byte that has arrived must be readable now,
    // with the pipe still open and nothing more on the way.
    final int length = 37;
    byte[] plaintext = plaintext(length);
    PipedInputStream pipe = new PipedInputStream(4096);
    PipedOutputStream writer = new PipedOutputStream(pipe);
    writer.write(jdk(Cipher.ENCRYPT_MODE, plaintext));
    writer.flush();

    InputStream in = Cfb8.decrypting(pipe, SECRET);
    byte[] decrypted = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
      byte[] out = new byte[length];
      out[0] = (byte) in.read();
      int read = 1;
      while (read < length) {
        int n = in.read(out, read, length - read);
        if (n < 0) {
          throw new AssertionError("unexpected end of stream after " + read + " bytes");
        }
        read += n;
      }
      return out;
    });
    assertArrayEquals(plaintext, decrypted);
    writer.close();
  }
}
