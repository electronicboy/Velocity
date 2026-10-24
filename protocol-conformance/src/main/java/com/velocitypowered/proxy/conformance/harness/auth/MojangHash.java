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

package com.velocitypowered.proxy.conformance.harness.auth;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The session server id hash: SHA-1 over the base server id, the shared secret and the encoded
 * public key, printed as a signed two's-complement hexadecimal number.
 */
public final class MojangHash {

  private MojangHash() {
  }

  /**
   * Computes the server id the client sends to {@code join} and the proxy sends to
   * {@code hasJoined}.
   *
   * @param serverId the base server id from the encryption request (empty since 1.7)
   * @param sharedSecret the AES key
   * @param publicKey the proxy's encoded RSA public key
   * @return the hash
   */
  public static String serverId(String serverId, byte[] sharedSecret, byte[] publicKey) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-1");
      digest.update(serverId.getBytes(StandardCharsets.ISO_8859_1));
      digest.update(sharedSecret);
      digest.update(publicKey);
      return new BigInteger(digest.digest()).toString(16);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Hashes a plain string, for checking against the published reference values.
   *
   * @param input the string
   * @return the hash
   */
  static String of(String input) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-1");
      return new BigInteger(digest.digest(input.getBytes(StandardCharsets.UTF_8))).toString(16);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
