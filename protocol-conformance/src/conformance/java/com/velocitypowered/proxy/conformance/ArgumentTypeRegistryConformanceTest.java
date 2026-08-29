/*
 * Copyright (C) 2018-2025 Velocity Contributors
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

package com.velocitypowered.proxy.conformance;

import static com.velocitypowered.api.network.ProtocolVersion.MINECRAFT_1_19;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.protocol.packet.brigadier.ArgumentIdentifier;
import com.velocitypowered.proxy.protocol.packet.brigadier.ArgumentPropertyRegistry;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Checks Velocity's Brigadier argument type table against the {@code minecraft:command_argument_type}
 * registry as emitted by Mojang's data generator.
 *
 * <p>Argument ids became numeric in 1.19; before that they were namespaced strings that do not
 * appear in the generated registries, so only 1.19+ is covered here.</p>
 */
class ArgumentTypeRegistryConformanceTest {

  static Stream<ProtocolVersion> pinnedVersions() {
    return ProtocolVersion.SUPPORTED_VERSIONS.stream()
        .filter(v -> v.noLessThan(MINECRAFT_1_19))
        .filter(v -> VanillaData.argumentTypes(v) != null);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("pinnedVersions")
  @DisplayName("argument type ids match the vanilla registry")
  void matchesVanilla(ProtocolVersion version) {
    Map<Integer, String> vanilla = VanillaData.argumentTypes(version);
    assertNotNull(vanilla, "no extracted data for " + version);

    assertEquals(new TreeMap<>(vanilla), new TreeMap<>(velocityTable(version)),
        "Velocity's argument type table diverges from vanilla for " + version
            + "; update ArgumentPropertyRegistry to match");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("pinnedVersions")
  @DisplayName("no two argument identifiers share a wire id")
  void hasNoCollisions(ProtocolVersion version) {
    Map<Integer, List<String>> byId = new TreeMap<>();
    for (ArgumentIdentifier identifier : registry().keySet()) {
      Integer id = identifier.getIdByProtocolVersion(version);
      // A negative id is a sentinel: -1 marks a type removed in this version, and CrossStitch's
      // wrapper sits at -256. Neither is reachable from the wire.
      if (id == null || id < 0) {
        continue;
      }
      byId.computeIfAbsent(id, k -> new ArrayList<>()).add(identifier.getIdentifier());
    }

    List<String> collisions = byId.entrySet().stream()
        .filter(e -> e.getValue().size() > 1)
        .map(e -> e.getKey() + " -> " + e.getValue())
        .toList();

    assertTrue(collisions.isEmpty(),
        "colliding argument ids for " + version + ": " + collisions);
  }

  static Map<Integer, String> velocityTable(ProtocolVersion version) {
    Map<Integer, String> byId = new TreeMap<>();
    for (ArgumentIdentifier identifier : registry().keySet()) {
      Integer id = identifier.getIdByProtocolVersion(version);
      if (id == null || id < 0) {
        continue;
      }
      byId.put(id, identifier.getIdentifier());
    }
    return byId;
  }

  @SuppressWarnings("unchecked")
  private static Map<ArgumentIdentifier, ?> registry() {
    try {
      Field field = ArgumentPropertyRegistry.class.getDeclaredField("byIdentifier");
      field.setAccessible(true);
      return (Map<ArgumentIdentifier, ?>) field.get(null);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("ArgumentPropertyRegistry layout changed", e);
    }
  }
}
