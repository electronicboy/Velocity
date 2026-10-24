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

package com.velocitypowered.proxy.conformance.harness.diff;

import com.velocitypowered.proxy.conformance.harness.Fixtures;
import com.velocitypowered.proxy.conformance.harness.trace.TraceEvent;
import com.velocitypowered.proxy.conformance.harness.wire.Buf;
import com.velocitypowered.proxy.conformance.harness.wire.Direction;
import com.velocitypowered.proxy.conformance.harness.wire.PacketTable;
import com.velocitypowered.proxy.conformance.harness.wire.Phase;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builders for synthetic traces and command tree packets.
 */
final class DiffFixtures {

  static final PacketTable TABLE = Fixtures.packets();

  // Ids from fixtures/packets.json.
  static final int PLAY_CB_COMMANDS = 16;
  static final int PLAY_CB_CUSTOM_PAYLOAD = 24;
  static final int PLAY_CB_KEEP_ALIVE = 38;
  static final int PLAY_CB_SYSTEM_CHAT = 114;
  static final int PLAY_CB_TAB_LIST = 120;
  static final int PLAY_SB_CHAT = 8;
  static final int PLAY_SB_CUSTOM_PAYLOAD = 21;
  static final int PLAY_SB_KEEP_ALIVE = 27;
  static final int CONFIG_SB_CLIENT_INFORMATION = 0;
  static final int CONFIG_SB_CUSTOM_PAYLOAD = 2;
  static final int CONFIG_SB_KEEP_ALIVE = 4;

  static final int TYPE_ROOT = 0;
  static final int TYPE_LITERAL = 1;
  static final int TYPE_ARGUMENT = 2;
  static final int EXECUTABLE = 0x04;
  static final int REDIRECT = 0x08;

  private DiffFixtures() {
  }

  static RuleContext ctx(Direction dir, int protocol, String[] argumentTypes) {
    return new RuleContext(dir, TABLE, protocol, argumentTypes);
  }

  static TraceEvent event(Direction dir, Phase phase, int id, byte[] payload) {
    return new TraceEvent(0, dir, phase, id, payload);
  }

  static TraceEvent clientboundPlay(int id, int... payload) {
    byte[] bytes = new byte[payload.length];
    for (int i = 0; i < payload.length; i++) {
      bytes[i] = (byte) payload[i];
    }
    return event(Direction.CLIENTBOUND, Phase.PLAY, id, bytes);
  }

  /** A keep-alive with an 8-byte id: distinct content per value, same packet type. */
  static TraceEvent keepAlive(long value) {
    return event(Direction.CLIENTBOUND, Phase.PLAY, PLAY_CB_KEEP_ALIVE,
        new Buf().writeLong(value).toByteArray());
  }

  static byte[] brand(String brand) {
    return new Buf().writeString("minecraft:brand").writeString(brand).toByteArray();
  }

  static byte[] channelPayload(String channel, byte[] data) {
    return new Buf().writeString(channel).writeBytes(data).toByteArray();
  }

  /**
   * One command node, referring to others by key so that a tree can be written out in any order.
   */
  record Spec(String key, int flags, List<String> children, String redirect, String name,
              String parser, byte[] properties) {
  }

  static Spec root(String... children) {
    return new Spec("root", TYPE_ROOT, List.of(children), null, null, null, null);
  }

  static Spec literal(String name, boolean executable, String... children) {
    return new Spec(name, TYPE_LITERAL | (executable ? EXECUTABLE : 0), List.of(children), null,
        name, null, null);
  }

  static Spec redirect(String name, String target) {
    return new Spec(name, TYPE_LITERAL | REDIRECT, List.of(), target, name, null, null);
  }

  static Spec argument(String key, String name, String parser, byte[] properties,
      String... children) {
    return new Spec(key, TYPE_ARGUMENT | EXECUTABLE, List.of(children), null, name, parser,
        properties);
  }

  static byte[] intRange(int min, int max) {
    return new Buf().writeByte(0x03).writeInt(min).writeInt(max).toByteArray();
  }

  static byte[] identifier(String id) {
    return new Buf().writeString(id).toByteArray();
  }

  /**
   * Encodes a command tree packet body with nodes numbered in list order.
   *
   * @param nodes the nodes, in wire order
   * @param protocol the protocol: parsers are strings before 1.19, registry ids after
   * @param argumentTypes argument type names by id, for 1.19+
   * @return the packet body
   */
  static byte[] commands(List<Spec> nodes, int protocol, List<String> argumentTypes) {
    Map<String, Integer> index = new HashMap<>();
    for (int i = 0; i < nodes.size(); i++) {
      index.put(nodes.get(i).key(), i);
    }
    Buf buf = new Buf().writeVarInt(nodes.size());
    for (Spec node : nodes) {
      buf.writeByte(node.flags());
      buf.writeVarInt(node.children().size());
      for (String child : node.children()) {
        buf.writeVarInt(index.get(child));
      }
      if (node.redirect() != null) {
        buf.writeVarInt(index.get(node.redirect()));
      }
      if (node.name() != null) {
        buf.writeString(node.name());
      }
      if (node.parser() != null) {
        if (protocol >= 759) {
          int id = argumentTypes.indexOf(node.parser());
          if (id < 0) {
            throw new IllegalArgumentException("no id for " + node.parser());
          }
          buf.writeVarInt(id);
        } else {
          buf.writeString(node.parser());
        }
        buf.writeBytes(node.properties());
      }
    }
    return buf.writeVarInt(index.get("root")).toByteArray();
  }
}
