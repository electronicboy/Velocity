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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.protocol.ProtocolUtils.Direction;
import com.velocitypowered.proxy.protocol.StateRegistry;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Checks the packet ids Velocity registers in {@link StateRegistry} against the
 * {@code reports/packets.json} emitted by Mojang's data generator.
 *
 * <p>The data generator only emits the packet report from 1.21 onwards, so older versions are not
 * covered here. Velocity's classes are related to Mojang's names by {@link VelocityPacketNames};
 * a class missing from that table fails the test, so a packet cannot be registered without saying
 * which vanilla packet it is.</p>
 *
 * <p>Divergences that are understood but not yet fixed are listed in {@link #KNOWN_DIVERGENCES}.
 * They are reported rather than failed, and a listed divergence that no longer diverges fails, so
 * a fix cannot land without the entry being removed.</p>
 */
class PacketIdConformanceTest {

  /**
   * Understood differences between Velocity's ids and vanilla's, each with the reason it is
   * tolerated for now.
   */
  private static final List<KnownDivergence> KNOWN_DIVERGENCES = List.of();

  static Stream<ProtocolVersion> versionsWithPacketReport() {
    return ProtocolVersion.SUPPORTED_VERSIONS.stream()
        .filter(v -> VanillaData.packets(v) != null);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("versionsWithPacketReport")
  @DisplayName("packet ids match the vanilla packet report")
  void matchesVanilla(ProtocolVersion version) {
    Map<String, Map<String, Map<String, Integer>>> vanilla = VanillaData.packets(version);
    assertNotNull(vanilla, "no extracted packet report for " + version);

    List<String> unmapped = new ArrayList<>();
    List<String> mismatches = new ArrayList<>();
    List<String> tolerated = new ArrayList<>();

    for (StateRegistry state : StateRegistry.values()) {
      String phase = VelocityPacketNames.phase(state);
      for (Direction direction : Direction.values()) {
        String side = VelocityPacketNames.direction(direction);
        Map<String, Integer> vanillaIds = vanilla.getOrDefault(phase, Map.of())
            .getOrDefault(side, Map.of());

        for (Map.Entry<Class<?>, Integer> registered
            : velocityTable(state, direction, version).entrySet()) {
          Class<?> packet = registered.getKey();
          int velocityId = registered.getValue();
          String name = VelocityPacketNames.name(state, direction, packet, version);
          if (name == null) {
            unmapped.add(packet.getName() + " (" + phase + " " + side + ")");
            continue;
          }

          Integer vanillaId = vanillaIds.get(name);
          if (Objects.equals(vanillaId, velocityId)) {
            continue;
          }

          String detail = describe(packet, phase, side, name, velocityId, vanillaId, vanillaIds);
          KnownDivergence known = knownDivergence(state, direction, packet, version);
          if (known != null) {
            tolerated.add(detail + " [known: " + known.reason() + "]");
          } else {
            mismatches.add(detail);
          }
        }
      }
    }

    if (!tolerated.isEmpty()) {
      System.out.println("known packet id divergences for " + version + ":\n  "
          + String.join("\n  ", tolerated));
    }

    // One message for both, so a missing name does not hide an id mismatch elsewhere.
    StringBuilder failure = new StringBuilder();
    if (!unmapped.isEmpty()) {
      failure.append("Velocity registers packets for ").append(version)
          .append(" with no vanilla name; add them to VelocityPacketNames:\n  ")
          .append(String.join("\n  ", unmapped)).append('\n');
    }
    if (!mismatches.isEmpty()) {
      failure.append("Velocity's packet ids diverge from vanilla for ").append(version)
          .append(":\n  ").append(String.join("\n  ", mismatches)).append('\n');
    }
    assertTrue(failure.isEmpty(), failure.toString());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("versionsWithPacketReport")
  @DisplayName("known divergences still diverge")
  void knownDivergencesStillDiverge(ProtocolVersion version) {
    Map<String, Map<String, Map<String, Integer>>> vanilla = VanillaData.packets(version);
    assertNotNull(vanilla, "no extracted packet report for " + version);

    List<String> fixed = new ArrayList<>();
    for (KnownDivergence known : KNOWN_DIVERGENCES) {
      if (known.version() != version) {
        continue;
      }
      Integer velocityId = velocityTable(known.state(), known.direction(), version)
          .get(known.packet());
      String name = VelocityPacketNames.name(known.state(), known.direction(), known.packet(),
          version);
      Integer vanillaId = vanilla.getOrDefault(VelocityPacketNames.phase(known.state()), Map.of())
          .getOrDefault(VelocityPacketNames.direction(known.direction()), Map.of())
          .get(name);
      String packet = known.packet().getName() + " (" + known.state() + " " + known.direction()
          + ")";
      if (velocityId == null) {
        fixed.add(packet + " is no longer registered");
      } else if (velocityId.equals(vanillaId)) {
        fixed.add(packet + " now matches vanilla id " + hex(vanillaId));
      }
    }

    assertTrue(fixed.isEmpty(),
        "known divergences no longer diverge for " + version + "; remove them from "
            + "KNOWN_DIVERGENCES:\n  " + String.join("\n  ", fixed));
  }

  @Test
  @DisplayName("every mapped name exists in a vanilla packet report")
  void namesExistInVanilla() {
    List<ProtocolVersion> versions = versionsWithPacketReport().toList();
    assertFalse(versions.isEmpty(), "no extracted packet reports");

    Map<String, Set<String>> vanillaNames = new HashMap<>();
    for (ProtocolVersion version : versions) {
      VanillaData.packets(version).forEach((phase, directions) ->
          directions.forEach((side, ids) ->
              vanillaNames.computeIfAbsent(phase + " " + side, k -> new HashSet<>())
                  .addAll(ids.keySet())));
    }

    Set<String> unknown = new TreeSet<>();
    for (VelocityPacketNames.Mapping mapping : VelocityPacketNames.mappings()) {
      String key = VelocityPacketNames.phase(mapping.state()) + " "
          + VelocityPacketNames.direction(mapping.direction());
      if (!vanillaNames.getOrDefault(key, Set.of()).contains(mapping.name())) {
        unknown.add(mapping.packet().getSimpleName() + " -> " + mapping.name() + " (" + key
            + ")");
      }
    }

    assertTrue(unknown.isEmpty(),
        "VelocityPacketNames maps to names no tested vanilla version has:\n  "
            + String.join("\n  ", unknown));
  }

  private static String describe(Class<?> packet, String phase, String side, String name,
      int velocityId, Integer vanillaId, Map<String, Integer> vanillaIds) {
    String owner = vanillaIds.entrySet().stream()
        .filter(e -> e.getValue() == velocityId)
        .map(Map.Entry::getKey)
        .findFirst()
        .orElse("nothing");
    return packet.getSimpleName() + " (" + phase + " " + side + ", " + name + "): velocity "
        + hex(velocityId) + ", vanilla " + (vanillaId == null ? "absent" : hex(vanillaId))
        + "; vanilla " + hex(velocityId) + " is " + owner;
  }

  private static String hex(int id) {
    return String.format("0x%02X", id);
  }

  private static KnownDivergence knownDivergence(StateRegistry state, Direction direction,
      Class<?> packet, ProtocolVersion version) {
    for (KnownDivergence known : KNOWN_DIVERGENCES) {
      if (known.state() == state && known.direction() == direction
          && known.packet() == packet && known.version() == version) {
        return known;
      }
    }
    return null;
  }

  /**
   * Returns the classes Velocity registers for the given phase, direction and version with their
   * ids, ordered by id.
   */
  static Map<Class<?>, Integer> velocityTable(StateRegistry state, Direction direction,
      ProtocolVersion version) {
    Map<Class<?>, Integer> table = registry(state.getProtocolRegistry(direction, version));
    Map<Class<?>, Integer> ordered = new LinkedHashMap<>();
    table.entrySet().stream()
        .sorted(Map.Entry.<Class<?>, Integer>comparingByValue()
            .thenComparing(e -> e.getKey().getName(), Comparator.naturalOrder()))
        .forEach(e -> ordered.put(e.getKey(), e.getValue()));
    return ordered;
  }

  @SuppressWarnings("unchecked")
  private static Map<Class<?>, Integer> registry(
      StateRegistry.PacketRegistry.ProtocolRegistry registry) {
    try {
      Field field = StateRegistry.PacketRegistry.ProtocolRegistry.class
          .getDeclaredField("packetClassToId");
      field.setAccessible(true);
      return (Map<Class<?>, Integer>) field.get(registry);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("StateRegistry layout changed", e);
    }
  }

  /**
   * A tolerated difference between Velocity's id and vanilla's for one packet in one version.
   */
  private record KnownDivergence(StateRegistry state, Direction direction, Class<?> packet,
      ProtocolVersion version, String reason) {
  }
}
