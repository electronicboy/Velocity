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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;

/**
 * The packet ids of one protocol version, per phase and direction, keyed by Mojang's resource
 * names ({@code minecraft:keep_alive}).
 *
 * <p>Built from the server jar's own {@code packets.json} where the data generator emits one
 * (1.21+), and otherwise from PrismarineJS minecraft-data, whose names are normalised to Mojang's
 * through a committed mapping. Names the mapping does not know are kept as
 * {@code legacy:<prismarine name>}.</p>
 */
public final class PacketTable {

  private static final Map<String, String> PRISMARINE_NAMES = loadPrismarineNames();

  private final String source;
  private final Map<Phase, Map<Direction, NavigableMap<Integer, String>>> byId =
      new EnumMap<>(Phase.class);
  private final Map<Phase, Map<Direction, Map<String, Integer>>> byName = new EnumMap<>(Phase.class);

  private PacketTable(String source) {
    this.source = source;
  }

  public String source() {
    return source;
  }

  /**
   * Builds a table from the data generator's {@code reports/packets.json}.
   *
   * @param packets the parsed report
   * @return the table
   */
  public static PacketTable fromPacketsJson(JsonObject packets) {
    PacketTable table = new PacketTable("packets.json");
    for (Map.Entry<String, JsonElement> phase : packets.entrySet()) {
      for (Map.Entry<String, JsonElement> dir : phase.getValue().getAsJsonObject().entrySet()) {
        for (Map.Entry<String, JsonElement> packet : dir.getValue().getAsJsonObject().entrySet()) {
          table.put(Phase.byId(phase.getKey()), direction(dir.getKey()), packet.getKey(),
              packet.getValue().getAsJsonObject().get("protocol_id").getAsInt());
        }
      }
    }
    return table;
  }

  /**
   * Builds a table from a minecraft-data {@code protocol.json}.
   *
   * @param protocol the parsed protocol definition
   * @param label where it came from, for reports
   * @return the table
   */
  public static PacketTable fromMinecraftData(JsonObject protocol, String label) {
    PacketTable table = new PacketTable("minecraft-data " + label);
    for (Map.Entry<String, JsonElement> phaseEntry : protocol.entrySet()) {
      Phase phase = switch (phaseEntry.getKey()) {
        case "handshaking" -> Phase.HANDSHAKE;
        case "status" -> Phase.STATUS;
        case "login" -> Phase.LOGIN;
        case "configuration" -> Phase.CONFIGURATION;
        case "play" -> Phase.PLAY;
        default -> null;
      };
      if (phase == null) {
        continue;
      }
      for (Map.Entry<String, JsonElement> dirEntry
          : phaseEntry.getValue().getAsJsonObject().entrySet()) {
        Direction dir = "toClient".equals(dirEntry.getKey())
            ? Direction.CLIENTBOUND : Direction.SERVERBOUND;
        JsonObject mappings = idMappings(dirEntry.getValue().getAsJsonObject());
        if (mappings == null) {
          continue;
        }
        for (Map.Entry<String, JsonElement> mapping : mappings.entrySet()) {
          int id = Integer.decode(mapping.getKey());
          String prismarine = mapping.getValue().getAsString();
          String key = phase.id() + "/" + dir.id() + "/" + prismarine;
          table.put(phase, dir, PRISMARINE_NAMES.getOrDefault(key, "legacy:" + prismarine), id);
        }
      }
    }
    return table;
  }

  /**
   * Returns the id of {@code name}, if this version has it.
   *
   * @param phase the phase
   * @param dir the direction
   * @param name the Mojang resource name
   * @return the id
   */
  public OptionalInt id(Phase phase, Direction dir, String name) {
    Integer id = byName.getOrDefault(phase, Map.of()).getOrDefault(dir, Map.of()).get(name);
    return id == null ? OptionalInt.empty() : OptionalInt.of(id);
  }

  /**
   * Returns the id of the first of {@code names} this version has. Used where Mojang renamed a
   * packet without changing its role.
   *
   * @param phase the phase
   * @param dir the direction
   * @param names candidate names, preferred first
   * @return the id
   */
  public OptionalInt idOfAny(Phase phase, Direction dir, String... names) {
    for (String name : names) {
      OptionalInt id = id(phase, dir, name);
      if (id.isPresent()) {
        return id;
      }
    }
    return OptionalInt.empty();
  }

  /**
   * Returns the name of the packet with {@code id}.
   *
   * @param phase the phase
   * @param dir the direction
   * @param id the packet id
   * @return the Mojang resource name
   */
  public Optional<String> name(Phase phase, Direction dir, int id) {
    return Optional.ofNullable(
        byId.getOrDefault(phase, Map.of()).getOrDefault(dir, Collections.emptyNavigableMap())
            .get(id));
  }

  /**
   * Returns a printable name, falling back to the hex id.
   *
   * @param phase the phase
   * @param dir the direction
   * @param id the packet id
   * @return the name
   */
  public String describe(Phase phase, Direction dir, int id) {
    return name(phase, dir, id).orElse(String.format("unknown:0x%02X", id));
  }

  public Set<String> names(Phase phase, Direction dir) {
    return Collections.unmodifiableSet(
        byName.getOrDefault(phase, Map.of()).getOrDefault(dir, Map.of()).keySet());
  }

  /**
   * Returns whether {@code id} is the packet called any of {@code names}.
   *
   * @param phase the phase
   * @param dir the direction
   * @param id the packet id
   * @param names candidate names
   * @return whether it matches
   */
  public boolean is(Phase phase, Direction dir, int id, String... names) {
    Optional<String> name = name(phase, dir, id);
    if (name.isEmpty()) {
      return false;
    }
    for (String candidate : names) {
      if (candidate.equals(name.get())) {
        return true;
      }
    }
    return false;
  }

  private void put(Phase phase, Direction dir, String name, int id) {
    byId.computeIfAbsent(phase, k -> new EnumMap<>(Direction.class))
        .computeIfAbsent(dir, k -> new TreeMap<>()).put(id, name);
    byName.computeIfAbsent(phase, k -> new EnumMap<>(Direction.class))
        .computeIfAbsent(dir, k -> new HashMap<>()).putIfAbsent(name, id);
  }

  private static Direction direction(String id) {
    return "clientbound".equals(id) ? Direction.CLIENTBOUND : Direction.SERVERBOUND;
  }

  private static JsonObject idMappings(JsonObject directionDefinition) {
    // types.packet = ["container", [{"name": "name", "type": ["mapper", {"mappings": {...}}]}, ...]]
    try {
      JsonObject nameField = directionDefinition.getAsJsonObject("types")
          .getAsJsonArray("packet").get(1).getAsJsonArray().get(0).getAsJsonObject();
      return nameField.getAsJsonArray("type").get(1).getAsJsonObject().getAsJsonObject("mappings");
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static Map<String, String> loadPrismarineNames() {
    try (InputStream in = PacketTable.class.getResourceAsStream("/prismarine-names.json")) {
      if (in == null) {
        throw new IllegalStateException("prismarine-names.json missing from the harness");
      }
      try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
        JsonObject names = Json.GSON.fromJson(reader, JsonObject.class).getAsJsonObject("names");
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : names.entrySet()) {
          out.put(entry.getKey(), entry.getValue().getAsString());
        }
        return out;
      }
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
