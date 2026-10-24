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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests protocol ordering, in particular that snapshots sort after every release.
 */
class ProtocolsTest {

  private static final int SNAPSHOT_1 = 0x40000000 | 1;
  private static final int SNAPSHOT_290 = 0x40000000 | 290;

  @Test
  void releasesKeepTheirNaturalOrder() {
    assertEquals(774, Protocols.order(774));
    assertTrue(Protocols.order(Protocols.V1_8) < Protocols.order(Protocols.V1_20_2));
    assertTrue(Protocols.order(773) < Protocols.order(774));
  }

  @Test
  void snapshotsSortAfterEveryRelease() {
    for (int release : new int[] {Protocols.V1_7_6, Protocols.V1_21_9, 774, 0x3FFFFFFF}) {
      assertTrue(Protocols.order(SNAPSHOT_1) > Protocols.order(release),
          "snapshot 1 after release " + release);
    }
    assertTrue(Protocols.order(SNAPSHOT_290) > Protocols.order(SNAPSHOT_1));
  }

  @Test
  void atLeastUsesTheSnapshotOrder() {
    assertTrue(Protocols.atLeast(764, Protocols.V1_20_2));
    assertTrue(Protocols.atLeast(774, Protocols.V1_20_2));
    assertFalse(Protocols.atLeast(763, Protocols.V1_20_2));
    assertTrue(Protocols.atLeast(SNAPSHOT_1, Protocols.V1_21_9));
    assertFalse(Protocols.atLeast(Protocols.V1_21_9, SNAPSHOT_1));
    assertTrue(Protocols.atLeast(SNAPSHOT_290, SNAPSHOT_290));
    assertFalse(Protocols.atLeast(SNAPSHOT_1, SNAPSHOT_290));
  }

  @Test
  void configurationPhaseStartsAt1202() {
    assertFalse(Protocols.hasConfiguration(763));
    assertTrue(Protocols.hasConfiguration(764));
    assertTrue(Protocols.hasConfiguration(SNAPSHOT_1));
  }
}
