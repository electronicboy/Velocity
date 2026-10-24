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

package com.velocitypowered.proxy.conformance.harness.vanilla;

import com.velocitypowered.proxy.conformance.harness.wire.Protocols;
import java.net.URI;

/**
 * A vanilla release (or snapshot) as published by Mojang.
 *
 * @param id the version id, e.g. {@code 1.21.11} or {@code 26.4-snapshot-5}
 * @param protocol the network protocol number
 * @param javaMajor the minimum Java major version Mojang ships for it
 * @param serverUrl where the server jar is published
 * @param sha1 the server jar's published sha1
 */
public record VanillaVersion(String id, int protocol, int javaMajor, URI serverUrl, String sha1) {

  public boolean atLeast(int level) {
    return Protocols.atLeast(protocol, level);
  }

  @Override
  public String toString() {
    return id + " (protocol " + (protocol >= 0x40000000
        ? "0x" + Integer.toHexString(protocol) : Integer.toString(protocol)) + ")";
  }
}
