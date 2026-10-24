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

import java.util.EnumMap;
import java.util.Map;

/**
 * Follows one connection through its phases from the packets crossing it, the way vanilla does.
 *
 * <p>Transitions out of login (1.20.2+), configuration and back into configuration are driven by
 * the client's acknowledgement, because that is when the vanilla server switches its own outbound
 * protocol. Before 1.20.2, login success moves both directions straight to play.</p>
 */
public final class PhaseTracker {

  private final PacketTable table;
  private final int protocol;
  private final Map<Direction, Phase> phases = new EnumMap<>(Direction.class);
  private int compressionThreshold = -1;

  /**
   * Creates a tracker for a fresh connection, which starts in the handshake phase.
   *
   * @param table the version's packet table
   * @param protocol the version's protocol number
   */
  public PhaseTracker(PacketTable table, int protocol) {
    this.table = table;
    this.protocol = protocol;
    phases.put(Direction.CLIENTBOUND, Phase.HANDSHAKE);
    phases.put(Direction.SERVERBOUND, Phase.HANDSHAKE);
  }

  public synchronized Phase phase(Direction dir) {
    return phases.get(dir);
  }

  public synchronized int compressionThreshold() {
    return compressionThreshold;
  }

  public int protocol() {
    return protocol;
  }

  public PacketTable table() {
    return table;
  }

  /**
   * Records a packet that has just crossed the connection, in the phase it was read in.
   *
   * @param dir its direction
   * @param id its id
   * @param payload its body
   */
  public synchronized void observe(Direction dir, int id, byte[] payload) {
    Phase phase = phases.get(dir);
    switch (phase) {
      case HANDSHAKE -> {
        if (dir == Direction.SERVERBOUND && id == 0) {
          Buf buf = new Buf(payload);
          buf.readVarInt();
          buf.readString();
          buf.readUnsignedShort();
          int next = buf.readVarInt();
          setBoth(next == 1 ? Phase.STATUS : Phase.LOGIN);
        }
      }
      case LOGIN -> {
        if (dir == Direction.CLIENTBOUND) {
          if (table.is(Phase.LOGIN, dir, id, "minecraft:login_compression")) {
            compressionThreshold = new Buf(payload).readVarInt();
          } else if (table.is(Phase.LOGIN, dir, id, "minecraft:login_finished",
              "minecraft:game_profile")) {
            if (!Protocols.hasConfiguration(protocol)) {
              setBoth(Phase.PLAY);
            }
          }
        } else if (table.is(Phase.LOGIN, dir, id, "minecraft:login_acknowledged")) {
          setBoth(Phase.CONFIGURATION);
        }
      }
      case CONFIGURATION -> {
        if (dir == Direction.SERVERBOUND
            && table.is(Phase.CONFIGURATION, dir, id, "minecraft:finish_configuration")) {
          setBoth(Phase.PLAY);
        }
      }
      case PLAY -> {
        if (dir == Direction.SERVERBOUND
            && table.is(Phase.PLAY, dir, id, "minecraft:configuration_acknowledged")) {
          setBoth(Phase.CONFIGURATION);
        }
      }
      default -> {
      }
    }
  }

  private void setBoth(Phase phase) {
    phases.put(Direction.CLIENTBOUND, phase);
    phases.put(Direction.SERVERBOUND, phase);
  }
}
