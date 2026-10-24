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

package com.velocitypowered.proxy.conformance;

import com.velocitypowered.api.network.ProtocolVersion;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes the Minecraft versions the vanilla conformance suite covers by default: the newest
 * release name of every protocol Velocity supports, one per line.
 *
 * <p>The newest name is the one players actually run (1.8.9 rather than 1.8), and Mojang
 * publishes a server jar for it.</p>
 */
public final class ListSupportedVersions {

  private ListSupportedVersions() {
  }

  /**
   * Entry point.
   *
   * @param args the output file
   * @throws IOException if it cannot be written
   */
  public static void main(String[] args) throws IOException {
    List<String> lines = new ArrayList<>();
    for (ProtocolVersion version : ProtocolVersion.SUPPORTED_VERSIONS) {
      lines.add(version.getMostRecentSupportedVersion());
    }
    Path output = Path.of(args[0]);
    Files.createDirectories(output.getParent());
    Files.write(output, lines, StandardCharsets.UTF_8);
  }
}
