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

package com.velocitypowered.proxy.conformance.harness;

import com.google.gson.JsonObject;
import com.velocitypowered.proxy.conformance.harness.wire.Json;
import com.velocitypowered.proxy.conformance.harness.wire.PacketTable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Packet tables built from the small JSON fixtures under {@code src/test/resources/fixtures}.
 */
public final class Fixtures {

  private Fixtures() {
  }

  /**
   * Reads a JSON object from the test resources.
   *
   * @param resource the absolute resource path
   * @return the parsed object
   */
  public static JsonObject json(String resource) {
    try (InputStream in = Fixtures.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("missing test resource " + resource);
      }
      try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
        return Json.GSON.fromJson(reader, JsonObject.class);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Returns a table in the shape of the data generator's {@code packets.json}, with modern names
   * ({@code minecraft:login_finished}) and a few packets per phase.
   *
   * @return the table
   */
  public static PacketTable packets() {
    return PacketTable.fromPacketsJson(json("/fixtures/packets.json"));
  }

  /**
   * Returns a table in the shape of a minecraft-data {@code protocol.json}, with Prismarine names.
   *
   * @return the table
   */
  public static PacketTable minecraftData() {
    return PacketTable.fromMinecraftData(json("/fixtures/minecraft-data-protocol.json"),
        "1.12.2");
  }
}
