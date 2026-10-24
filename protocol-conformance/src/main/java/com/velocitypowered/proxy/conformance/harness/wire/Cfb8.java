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

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES/CFB8 as Minecraft uses it (key and IV both the shared secret), done a byte at a time over
 * AES/ECB.
 *
 * <p>The JDK's {@code CipherInputStream} holds decrypted bytes back until more ciphertext
 * arrives, which deadlocks a request/response exchange such as login acknowledgement: the last
 * packet the server sends before waiting is never delivered. A byte-wise implementation never
 * holds anything back.</p>
 */
public final class Cfb8 {

  private final Cipher block;
  private final byte[] register;
  private final boolean decrypt;

  private Cfb8(byte[] secret, boolean decrypt) throws GeneralSecurityException {
    this.block = Cipher.getInstance("AES/ECB/NoPadding");
    this.block.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(secret, "AES"));
    this.register = secret.clone();
    this.decrypt = decrypt;
  }

  /**
   * Transforms one byte.
   *
   * @param in a plaintext byte when encrypting, a ciphertext byte when decrypting
   * @return the transformed byte
   */
  public int update(int in) {
    byte[] keystream;
    try {
      keystream = block.doFinal(register);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
    int out = (in ^ keystream[0]) & 0xFF;
    int cipherByte = decrypt ? in : out;
    System.arraycopy(register, 1, register, 0, register.length - 1);
    register[register.length - 1] = (byte) cipherByte;
    return out;
  }

  /**
   * Wraps a stream so that everything read from it is decrypted.
   *
   * @param in the ciphertext stream
   * @param secret the shared secret
   * @return the plaintext stream
   * @throws GeneralSecurityException if AES is unavailable
   */
  public static InputStream decrypting(InputStream in, byte[] secret)
      throws GeneralSecurityException {
    Cfb8 cfb = new Cfb8(secret, true);
    return new FilterInputStream(in) {
      @Override
      public int read() throws IOException {
        int b = super.read();
        return b == -1 ? -1 : cfb.update(b);
      }

      @Override
      public int read(byte[] b, int off, int len) throws IOException {
        int n = super.read(b, off, len);
        for (int i = 0; i < n; i++) {
          b[off + i] = (byte) cfb.update(b[off + i] & 0xFF);
        }
        return n;
      }

      @Override
      public long skip(long n) {
        throw new UnsupportedOperationException();
      }
    };
  }

  /**
   * Wraps a stream so that everything written to it is encrypted.
   *
   * @param out the ciphertext stream
   * @param secret the shared secret
   * @return the plaintext stream
   * @throws GeneralSecurityException if AES is unavailable
   */
  public static OutputStream encrypting(OutputStream out, byte[] secret)
      throws GeneralSecurityException {
    Cfb8 cfb = new Cfb8(secret, false);
    return new FilterOutputStream(out) {
      @Override
      public void write(int b) throws IOException {
        out.write(cfb.update(b & 0xFF));
      }

      @Override
      public void write(byte[] b, int off, int len) throws IOException {
        byte[] encrypted = new byte[len];
        for (int i = 0; i < len; i++) {
          encrypted[i] = (byte) cfb.update(b[off + i] & 0xFF);
        }
        out.write(encrypted);
      }
    };
  }
}
