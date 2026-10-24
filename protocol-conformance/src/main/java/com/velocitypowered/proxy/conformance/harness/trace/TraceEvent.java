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

package com.velocitypowered.proxy.conformance.harness.trace;

import com.velocitypowered.proxy.conformance.harness.wire.Direction;
import com.velocitypowered.proxy.conformance.harness.wire.Phase;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * One decompressed packet observed at a capture point.
 *
 * @param nanos monotonic capture time
 * @param dir which way it travelled
 * @param phase the phase it was read in
 * @param id the packet id
 * @param payload the packet body, without the id
 */
public record TraceEvent(long nanos, Direction dir, Phase phase, int id, byte[] payload) {

  /**
   * Returns a short content hash of {@code id + payload}, for alignment and for trace lines.
   *
   * @return eight hex characters
   */
  public String hash() {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update((byte) id);
      digest.update((byte) (id >>> 8));
      digest.update(payload);
      return HexFormat.of().formatHex(digest.digest(), 0, 4);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Returns whether two events carry the same packet.
   *
   * @param other the other event
   * @return whether id and payload are identical
   */
  public boolean sameContent(TraceEvent other) {
    return id == other.id && Arrays.equals(payload, other.payload);
  }
}
