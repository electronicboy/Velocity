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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.proxy.conformance.harness.Fixtures;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Tests packet tables built from both upstream formats.
 */
class PacketTableTest {

  @Test
  void packetsJsonLooksUpBothWays() {
    PacketTable table = Fixtures.packets();
    assertEquals("packets.json", table.source());

    assertEquals(OptionalInt.of(38), table.id(Phase.PLAY, CLIENTBOUND, "minecraft:keep_alive"));
    assertEquals(OptionalInt.of(27), table.id(Phase.PLAY, SERVERBOUND, "minecraft:keep_alive"));
    assertEquals(OptionalInt.empty(), table.id(Phase.PLAY, CLIENTBOUND, "minecraft:nope"));
    assertEquals(OptionalInt.empty(),
        table.id(Phase.STATUS, CLIENTBOUND, "minecraft:keep_alive"));

    assertEquals(Optional.of("minecraft:keep_alive"), table.name(Phase.PLAY, CLIENTBOUND, 38));
    assertEquals(Optional.of("minecraft:custom_payload"),
        table.name(Phase.PLAY, SERVERBOUND, 21));
    assertEquals(Optional.empty(), table.name(Phase.PLAY, CLIENTBOUND, 39));
    assertEquals(Optional.of("minecraft:intention"), table.name(Phase.HANDSHAKE, SERVERBOUND, 0));
    assertEquals(Optional.empty(), table.name(Phase.HANDSHAKE, CLIENTBOUND, 0));
  }

  @Test
  void describeFallsBackToHexId() {
    PacketTable table = Fixtures.packets();
    assertEquals("minecraft:keep_alive", table.describe(Phase.PLAY, CLIENTBOUND, 38));
    assertEquals("unknown:0x27", table.describe(Phase.PLAY, CLIENTBOUND, 39));
    assertEquals("unknown:0x7F", table.describe(Phase.PLAY, CLIENTBOUND, 127));
  }

  @Test
  void idOfAnyPrefersTheFirstNamePresent() {
    PacketTable table = Fixtures.packets();
    assertEquals(OptionalInt.of(2), table.idOfAny(Phase.LOGIN, CLIENTBOUND,
        "minecraft:game_profile", "minecraft:login_finished"));
    assertEquals(OptionalInt.of(0), table.idOfAny(Phase.LOGIN, CLIENTBOUND,
        "minecraft:login_disconnect", "minecraft:login_finished"));
    assertEquals(OptionalInt.empty(), table.idOfAny(Phase.LOGIN, CLIENTBOUND,
        "minecraft:game_profile", "minecraft:success"));
  }

  @Test
  void isMatchesAnyCandidateName() {
    PacketTable table = Fixtures.packets();
    assertTrue(table.is(Phase.PLAY, CLIENTBOUND, 38,
        "minecraft:custom_payload", "minecraft:keep_alive"));
    assertFalse(table.is(Phase.PLAY, SERVERBOUND, 38, "minecraft:keep_alive"));
    assertFalse(table.is(Phase.PLAY, CLIENTBOUND, 39, "minecraft:keep_alive"));
    assertFalse(table.is(Phase.PLAY, CLIENTBOUND, 38));
  }

  @Test
  void namesListsOnePhaseAndDirection() {
    PacketTable table = Fixtures.packets();
    assertEquals(Set.of("minecraft:hello", "minecraft:key", "minecraft:custom_query_answer",
        "minecraft:login_acknowledged"), table.names(Phase.LOGIN, SERVERBOUND));
    assertEquals(Set.of(), table.names(Phase.HANDSHAKE, CLIENTBOUND));
  }

  @Test
  void minecraftDataNamesAreMappedToMojangNames() {
    PacketTable table = Fixtures.minecraftData();
    assertEquals("minecraft-data 1.12.2", table.source());

    assertEquals(OptionalInt.of(0x1f),
        table.id(Phase.PLAY, CLIENTBOUND, "minecraft:keep_alive"));
    assertEquals(OptionalInt.of(0x0b),
        table.id(Phase.PLAY, SERVERBOUND, "minecraft:keep_alive"));
    assertEquals(Optional.of("minecraft:custom_payload"),
        table.name(Phase.PLAY, CLIENTBOUND, 0x18));

    assertEquals(Optional.of("minecraft:game_profile"), table.name(Phase.LOGIN, CLIENTBOUND, 2));
    assertEquals(Optional.of("minecraft:login_compression"),
        table.name(Phase.LOGIN, CLIENTBOUND, 3));
    assertEquals(Optional.of("minecraft:login_disconnect"),
        table.name(Phase.LOGIN, CLIENTBOUND, 0));
    assertEquals(Optional.of("minecraft:hello"), table.name(Phase.LOGIN, SERVERBOUND, 0));
    assertEquals(Optional.of("minecraft:intention"), table.name(Phase.HANDSHAKE, SERVERBOUND, 0));
    assertEquals(Optional.of("minecraft:legacy_server_list_ping"),
        table.name(Phase.HANDSHAKE, SERVERBOUND, 0xfe));
  }

  @Test
  void minecraftDataKeepsUnmappedNamesAsLegacy() {
    PacketTable table = Fixtures.minecraftData();
    assertEquals(Optional.of("legacy:mystery_packet"), table.name(Phase.LOGIN, CLIENTBOUND, 5));
    assertEquals(OptionalInt.of(5),
        table.id(Phase.LOGIN, CLIENTBOUND, "legacy:mystery_packet"));
  }

  @Test
  void minecraftDataSkipsSectionsWithoutPackets() {
    PacketTable table = Fixtures.minecraftData();
    // "types" is not a phase, and handshaking/toClient has no packet mapper.
    assertEquals(Set.of(), table.names(Phase.HANDSHAKE, CLIENTBOUND));
    assertEquals(Set.of(), table.names(Phase.CONFIGURATION, CLIENTBOUND));
    assertEquals(Set.of("minecraft:login_disconnect", "minecraft:hello",
            "minecraft:game_profile", "minecraft:login_compression", "legacy:mystery_packet"),
        table.names(Phase.LOGIN, CLIENTBOUND));
  }
}
