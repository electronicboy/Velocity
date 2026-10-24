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

/**
 * Protocol numbers at which wire behaviour the harness cares about changed.
 *
 * <p>Snapshot protocols carry bit 30 ({@code 0x40000000 | n}). They always belong to the newest
 * line of development, so for ordering purposes they sort after every release.</p>
 */
public final class Protocols {

  public static final int V1_7_6 = 5;
  public static final int V1_8 = 47;
  public static final int V1_9 = 107;
  public static final int V1_12 = 335;
  public static final int V1_13 = 393;
  public static final int V1_14 = 477;
  public static final int V1_16 = 735;
  public static final int V1_17 = 755;
  public static final int V1_18 = 757;
  public static final int V1_19 = 759;
  public static final int V1_19_1 = 760;
  public static final int V1_19_3 = 761;
  public static final int V1_20_2 = 764;
  public static final int V1_20_3 = 765;
  public static final int V1_20_5 = 766;
  public static final int V1_21 = 767;
  public static final int V1_21_2 = 768;
  public static final int V1_21_4 = 769;
  public static final int V1_21_5 = 770;
  public static final int V1_21_6 = 771;
  public static final int V1_21_9 = 773;
  public static final int V26_3 = 777;

  private static final int SNAPSHOT_BIT = 0x40000000;

  private Protocols() {
  }

  /**
   * Maps a protocol number onto a total order in which snapshots follow every release.
   *
   * @param protocol the protocol number
   * @return a sortable key
   */
  public static long order(int protocol) {
    if ((protocol & SNAPSHOT_BIT) != 0) {
      return (1L << 32) + (protocol & ~SNAPSHOT_BIT);
    }
    return protocol;
  }

  public static boolean atLeast(int protocol, int level) {
    return order(protocol) >= order(level);
  }

  public static boolean hasConfiguration(int protocol) {
    return atLeast(protocol, V1_20_2);
  }
}
