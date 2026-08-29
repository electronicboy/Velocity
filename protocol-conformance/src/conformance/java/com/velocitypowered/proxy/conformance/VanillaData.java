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

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.velocitypowered.api.network.ProtocolVersion;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reads the registry data extracted from Mojang's data generator.
 *
 * <p>Nothing is committed: the data is re-pulled from Mojang and extracted into the build directory
 * on every run.</p>
 */
final class VanillaData {

  private static final Gson GSON = new Gson();

  private VanillaData() {
  }

  static Path directory() {
    String configured = System.getProperty("velocity.conformance.data");
    if (configured == null) {
      throw new IllegalStateException(
          "velocity.conformance.data is not set; run via the conformanceTest task");
    }
    return Path.of(configured);
  }

  /**
   * Returns the vanilla {@code minecraft:command_argument_type} table for the given version, keyed
   * by wire id, or {@code null} if nothing was extracted for it.
   */
  static Map<Integer, String> argumentTypes(ProtocolVersion version) {
    JsonObject root = read(version.getVersionIntroducedIn());
    if (root == null || !root.has("command_argument_type")) {
      return null;
    }

    Map<Integer, String> byId = new TreeMap<>();
    for (Map.Entry<String, JsonElement> entry
        : root.getAsJsonObject("command_argument_type").entrySet()) {
      byId.put(entry.getValue().getAsInt(), entry.getKey());
    }
    return byId;
  }


  private static JsonObject read(String name) {
    Path path = directory().resolve(name + ".json");
    if (!Files.isRegularFile(path)) {
      return null;
    }
    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      return GSON.fromJson(reader, JsonObject.class);
    } catch (IOException e) {
      throw new IllegalStateException("Unable to read " + path, e);
    }
  }
}
