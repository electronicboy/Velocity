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

import com.velocitypowered.proxy.conformance.harness.wire.Direction;
import com.velocitypowered.proxy.conformance.harness.wire.Phase;

/**
 * One difference between what was sent and what arrived.
 *
 * @param kind what sort of difference
 * @param dir which way the packet travelled
 * @param phase the phase it travelled in
 * @param packet the Mojang packet name
 * @param sourceIndex index in the sending side's capture, or {@code -1}
 * @param sinkIndex index in the receiving side's capture, or {@code -1}
 * @param detail human-readable explanation; for {@link Kind#TOLERATED}, the rule's justification
 */
public record Finding(Kind kind, Direction dir, Phase phase, String packet, int sourceIndex,
                      int sinkIndex, String detail) {

  /**
   * Finding classification.
   */
  public enum Kind {
    /** Same packet type, different bytes. */
    MISMATCH,
    /** Sent but never delivered. */
    DROPPED,
    /** Delivered but never sent. */
    INJECTED,
    /** Delivered intact but out of order. */
    REORDERED,
    /** A difference a proxy rule explains; not a failure. */
    TOLERATED;

    public boolean isFailure() {
      return this != TOLERATED;
    }
  }

  @Override
  public String toString() {
    return String.format("%-9s %s %-13s %-40s src=%-5d sink=%-5d %s", kind, dir.arrow(),
        phase, packet, sourceIndex, sinkIndex, detail);
  }
}
