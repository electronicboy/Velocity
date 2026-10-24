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

/**
 * A connection phase, named as in Mojang's {@code packets.json}.
 */
public enum Phase {
  HANDSHAKE("handshake"),
  STATUS("status"),
  LOGIN("login"),
  CONFIGURATION("configuration"),
  PLAY("play");

  private final String id;

  Phase(String id) {
    this.id = id;
  }

  public String id() {
    return id;
  }

  /**
   * Looks a phase up by its {@code packets.json} name.
   *
   * @param id the name
   * @return the phase
   */
  public static Phase byId(String id) {
    for (Phase phase : values()) {
      if (phase.id.equals(id)) {
        return phase;
      }
    }
    throw new IllegalArgumentException("unknown phase " + id);
  }
}
