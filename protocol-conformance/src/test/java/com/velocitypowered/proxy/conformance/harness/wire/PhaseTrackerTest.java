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

import static com.velocitypowered.proxy.conformance.harness.wire.Direction.CLIENTBOUND;
import static com.velocitypowered.proxy.conformance.harness.wire.Direction.SERVERBOUND;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.velocitypowered.proxy.conformance.harness.Fixtures;
import org.junit.jupiter.api.Test;

/**
 * Tests that the phase tracker follows a connection the way vanilla does.
 */
class PhaseTrackerTest {

  // Ids from fixtures/packets.json.
  private static final int LOGIN_FINISHED = 2;
  private static final int LOGIN_COMPRESSION = 3;
  private static final int LOGIN_ACKNOWLEDGED = 3;
  private static final int CONFIG_FINISH = 3;
  private static final int PLAY_CONFIGURATION_ACKNOWLEDGED = 15;
  private static final byte[] EMPTY = new byte[0];

  private static byte[] handshake(int protocol, int nextState) {
    return new Buf().writeVarInt(protocol).writeString("localhost").writeShort(25565)
        .writeVarInt(nextState).toByteArray();
  }

  private static void assertPhase(PhaseTracker tracker, Phase expected) {
    assertEquals(expected, tracker.phase(CLIENTBOUND), "clientbound phase");
    assertEquals(expected, tracker.phase(SERVERBOUND), "serverbound phase");
  }

  private static PhaseTracker inLogin(PacketTable table, int protocol) {
    PhaseTracker tracker = new PhaseTracker(table, protocol);
    tracker.observe(SERVERBOUND, 0, handshake(protocol, 2));
    assertPhase(tracker, Phase.LOGIN);
    return tracker;
  }

  @Test
  void startsInHandshake() {
    PhaseTracker tracker = new PhaseTracker(Fixtures.packets(), 774);
    assertPhase(tracker, Phase.HANDSHAKE);
    assertEquals(-1, tracker.compressionThreshold());
    assertEquals(774, tracker.protocol());
  }

  @Test
  void handshakeNextStateOneIsStatus() {
    PhaseTracker tracker = new PhaseTracker(Fixtures.packets(), 774);
    tracker.observe(SERVERBOUND, 0, handshake(774, 1));
    assertPhase(tracker, Phase.STATUS);
  }

  @Test
  void handshakeNextStateTwoIsLogin() {
    inLogin(Fixtures.packets(), 774);
  }

  @Test
  void loginCompressionSetsThreshold() {
    PhaseTracker tracker = inLogin(Fixtures.packets(), 774);
    tracker.observe(CLIENTBOUND, LOGIN_COMPRESSION, new Buf().writeVarInt(256).toByteArray());
    assertEquals(256, tracker.compressionThreshold());
    assertPhase(tracker, Phase.LOGIN);
  }

  @Test
  void loginSuccessGoesStraightToPlayBefore1202() {
    // Pre-1.20.2 tables come from minecraft-data, where login success is game_profile.
    PhaseTracker tracker = inLogin(Fixtures.minecraftData(), 763);
    tracker.observe(CLIENTBOUND, 2, EMPTY);
    assertPhase(tracker, Phase.PLAY);
  }

  @Test
  void loginFinishedAlsoGoesStraightToPlayBefore1202() {
    PhaseTracker tracker = inLogin(Fixtures.packets(), 763);
    tracker.observe(CLIENTBOUND, LOGIN_FINISHED, EMPTY);
    assertPhase(tracker, Phase.PLAY);
  }

  @Test
  void loginSuccessWaitsForAcknowledgementFrom1202() {
    PhaseTracker tracker = inLogin(Fixtures.packets(), 764);
    tracker.observe(CLIENTBOUND, LOGIN_FINISHED, EMPTY);
    assertPhase(tracker, Phase.LOGIN);
    // A clientbound packet with the acknowledgement's id is not the acknowledgement.
    tracker.observe(CLIENTBOUND, LOGIN_ACKNOWLEDGED, new Buf().writeVarInt(64).toByteArray());
    assertPhase(tracker, Phase.LOGIN);
    tracker.observe(SERVERBOUND, LOGIN_ACKNOWLEDGED, EMPTY);
    assertPhase(tracker, Phase.CONFIGURATION);
  }

  @Test
  void serverboundFinishConfigurationEntersPlay() {
    PhaseTracker tracker = inLogin(Fixtures.packets(), 774);
    tracker.observe(SERVERBOUND, LOGIN_ACKNOWLEDGED, EMPTY);
    assertPhase(tracker, Phase.CONFIGURATION);

    tracker.observe(CLIENTBOUND, CONFIG_FINISH, EMPTY);
    assertPhase(tracker, Phase.CONFIGURATION);
    tracker.observe(SERVERBOUND, CONFIG_FINISH, EMPTY);
    assertPhase(tracker, Phase.PLAY);
  }

  @Test
  void serverboundConfigurationAcknowledgedReturnsToConfiguration() {
    PhaseTracker tracker = inLogin(Fixtures.packets(), 774);
    tracker.observe(SERVERBOUND, LOGIN_ACKNOWLEDGED, EMPTY);
    tracker.observe(SERVERBOUND, CONFIG_FINISH, EMPTY);
    assertPhase(tracker, Phase.PLAY);

    tracker.observe(CLIENTBOUND, PLAY_CONFIGURATION_ACKNOWLEDGED, EMPTY);
    assertPhase(tracker, Phase.PLAY);
    tracker.observe(SERVERBOUND, PLAY_CONFIGURATION_ACKNOWLEDGED, EMPTY);
    assertPhase(tracker, Phase.CONFIGURATION);
  }
}
