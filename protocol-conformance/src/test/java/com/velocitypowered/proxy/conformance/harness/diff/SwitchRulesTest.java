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

import static com.velocitypowered.proxy.conformance.harness.diff.DiffFixtures.ctx;
import static com.velocitypowered.proxy.conformance.harness.diff.DiffFixtures.event;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.proxy.conformance.harness.trace.TraceEvent;
import com.velocitypowered.proxy.conformance.harness.wire.Direction;
import com.velocitypowered.proxy.conformance.harness.wire.Phase;
import org.junit.jupiter.api.Test;

/**
 * Tests Velocity's server-switch tolerances.
 */
class SwitchRulesTest {

  private static final ProxyRules VELOCITY = ProxyRules.forKind("velocity");
  // Ids from fixtures/packets.json.
  private static final int LOGIN = 43;
  private static final int PLAYER_INFO_REMOVE = 61;
  private static final int PLAYER_INFO_UPDATE = 62;
  private static final int RESPAWN = 76;

  private static TraceEvent play(int id, int... payload) {
    byte[] bytes = new byte[payload.length];
    for (int i = 0; i < payload.length; i++) {
      bytes[i] = (byte) payload[i];
    }
    return event(Direction.CLIENTBOUND, Phase.PLAY, id, bytes);
  }

  private static RuleContext at(int protocol) {
    return ctx(Direction.CLIENTBOUND, protocol, null);
  }

  @Test
  void respawnStartingLegacyEpochIsTheSwitchDance() {
    assertTrue(VELOCITY.tolerateSwitchInjection(at(763), play(RESPAWN, 1, 2), true)
        .isPresent());
  }

  @Test
  void respawnIsNotExpectedWhereConfigurationResetsTheClient() {
    assertFalse(VELOCITY.tolerateSwitchInjection(at(774), play(RESPAWN, 1, 2), true)
        .isPresent());
  }

  @Test
  void respawnIsNotExpectedInTheEpochBeingLeft() {
    assertFalse(VELOCITY.tolerateSwitchInjection(at(763), play(RESPAWN, 1, 2), false)
        .isPresent());
  }

  @Test
  void tabListRemovalEndsTheEpochBeingLeft() {
    assertTrue(VELOCITY.tolerateSwitchInjection(at(774), play(PLAYER_INFO_REMOVE, 1, 0), false)
        .isPresent());
    assertFalse(VELOCITY.tolerateSwitchInjection(at(774), play(PLAYER_INFO_REMOVE, 1, 0), true)
        .isPresent());
  }

  @Test
  void legacyTabListRemovalIsTheRemoveAction() {
    assertTrue(VELOCITY.tolerateSwitchInjection(at(340), play(PLAYER_INFO_UPDATE, 4, 1), false)
        .isPresent());
    assertFalse(VELOCITY.tolerateSwitchInjection(at(340), play(PLAYER_INFO_UPDATE, 0, 1), false)
        .isPresent());
  }

  @Test
  void legacyJoinGameMayFlipOnlyTheDimension() {
    TraceEvent sent = play(LOGIN, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1, 20, 4);
    TraceEvent flipped = play(LOGIN, 0, 0, 0, 1, 0, -1, -1, -1, -1, 1, 20, 4);
    TraceEvent other = play(LOGIN, 0, 0, 0, 2, 0, 0, 0, 0, 0, 1, 20, 4);
    assertTrue(VELOCITY.tolerateSwitchMismatch(at(340), sent, flipped, true).isPresent());
    assertFalse(VELOCITY.tolerateSwitchMismatch(at(340), sent, other, true).isPresent());
    assertFalse(VELOCITY.tolerateSwitchMismatch(at(340), sent, flipped, false).isPresent());
    assertFalse(VELOCITY.tolerateSwitchMismatch(at(754), sent, flipped, true).isPresent());
  }
}
