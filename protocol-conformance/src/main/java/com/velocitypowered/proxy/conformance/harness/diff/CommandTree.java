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

import com.velocitypowered.proxy.conformance.harness.wire.Buf;
import com.velocitypowered.proxy.conformance.harness.wire.Protocols;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * An independent decoder for the Brigadier command tree packet (1.13+), producing a canonical
 * form in which node order on the wire does not matter. A proxy that re-encodes the tree may
 * legally renumber nodes; it may not change what any node means.
 */
public final class CommandTree {

  private static final int TYPE_MASK = 0x03;
  private static final int TYPE_LITERAL = 1;
  private static final int TYPE_ARGUMENT = 2;
  private static final int FLAG_REDIRECT = 0x08;
  private static final int FLAG_SUGGESTIONS = 0x10;

  private record Node(int flags, int[] children, int redirect, String name, String parser,
                      String properties, String suggestions) {
  }

  private final Node[] nodes;
  private final int root;

  private CommandTree(Node[] nodes, int root) {
    this.nodes = nodes;
    this.root = root;
  }

  /**
   * Decodes a command tree packet body.
   *
   * @param payload the packet body
   * @param protocol the protocol number
   * @param argumentTypes argument type names by id (1.19+); ignored before 1.19
   * @return the tree
   * @throws IllegalArgumentException if the tree cannot be decoded (e.g. an unknown parser)
   */
  public static CommandTree decode(byte[] payload, int protocol, String[] argumentTypes) {
    Buf buf = new Buf(payload);
    Node[] nodes = new Node[buf.readVarInt()];
    for (int n = 0; n < nodes.length; n++) {
      int flags = buf.readUnsignedByte();
      int[] children = new int[buf.readVarInt()];
      for (int c = 0; c < children.length; c++) {
        children[c] = buf.readVarInt();
      }
      int redirect = (flags & FLAG_REDIRECT) != 0 ? buf.readVarInt() : -1;
      int type = flags & TYPE_MASK;
      String name = null;
      String parser = null;
      String properties = null;
      String suggestions = null;
      if (type == TYPE_LITERAL || type == TYPE_ARGUMENT) {
        name = buf.readString();
      }
      if (type == TYPE_ARGUMENT) {
        if (Protocols.atLeast(protocol, Protocols.V1_19)) {
          int id = buf.readVarInt();
          if (argumentTypes == null || id < 0 || id >= argumentTypes.length) {
            throw new IllegalArgumentException("unknown argument type id " + id);
          }
          parser = argumentTypes[id];
        } else {
          parser = buf.readString();
        }
        int start = buf.readerIndex();
        skipProperties(buf, parser, protocol);
        properties = HexFormat.of().formatHex(payload, start, buf.readerIndex());
        if ((flags & FLAG_SUGGESTIONS) != 0) {
          suggestions = buf.readString();
        }
      }
      nodes[n] = new Node(flags, children, redirect, name, parser, properties, suggestions);
    }
    int root = buf.readVarInt();
    if (buf.readableBytes() != 0) {
      throw new IllegalArgumentException(buf.readableBytes() + " trailing bytes");
    }
    return new CommandTree(nodes, root);
  }

  private static void skipProperties(Buf buf, String parser, int protocol) {
    switch (parser) {
      case "brigadier:bool" -> {
      }
      case "brigadier:float" -> numeric(buf, 4);
      case "brigadier:double" -> numeric(buf, 8);
      case "brigadier:integer" -> numeric(buf, 4);
      case "brigadier:long" -> numeric(buf, 8);
      case "brigadier:string" -> buf.readVarInt();
      case "minecraft:entity", "minecraft:score_holder" -> buf.readByte();
      case "minecraft:time" -> {
        if (Protocols.atLeast(protocol, 762)) {
          buf.readInt();
        }
      }
      case "minecraft:resource_or_tag", "minecraft:resource_or_tag_key", "minecraft:resource",
           "minecraft:resource_key", "minecraft:resource_selector" -> buf.readString();
      default -> {
        // Every other vanilla parser carries no properties.
      }
    }
  }

  private static void numeric(Buf buf, int width) {
    int flags = buf.readUnsignedByte();
    if ((flags & 0x01) != 0) {
      buf.skip(width);
    }
    if ((flags & 0x02) != 0) {
      buf.skip(width);
    }
  }

  /**
   * Returns the canonical form of each child of the root, keyed by name.
   *
   * @return canonical subtrees
   */
  public Map<String, String> rootChildren() {
    final Map<String, String> paths = pathsByIndex();
    Map<String, String> out = new TreeMap<>();
    for (int child : nodes[root].children()) {
      out.put(nodes[child].name(), canonical(child, paths, new TreeSet<>()));
    }
    return out;
  }

  /**
   * Checks that {@code sink} is {@code source} plus root literals from {@code allowedExtras}.
   *
   * @param source the tree the backend sent
   * @param sink the tree the client received
   * @param allowedExtras root literals the proxy may add
   * @return {@code null} if so, else what differs
   */
  public static String compareWithExtras(CommandTree source, CommandTree sink,
      Set<String> allowedExtras) {
    Map<String, String> a = source.rootChildren();
    Map<String, String> b = sink.rootChildren();
    List<String> problems = new ArrayList<>();
    for (Map.Entry<String, String> entry : a.entrySet()) {
      String delivered = b.get(entry.getKey());
      if (delivered == null) {
        problems.add("/" + entry.getKey() + " missing");
      } else if (!delivered.equals(entry.getValue())) {
        problems.add("/" + entry.getKey() + " changed (" + divergence(entry.getValue(), delivered)
            + ")");
      }
    }
    for (String name : b.keySet()) {
      if (!a.containsKey(name) && !allowedExtras.contains(name)) {
        problems.add("/" + name + " added");
      }
    }
    return problems.isEmpty() ? null : String.join(", ", problems);
  }

  /**
   * Describes where two canonical forms first differ, token by token.
   */
  private static String divergence(String sent, String delivered) {
    int i = 0;
    while (i < sent.length() && i < delivered.length() && sent.charAt(i) == delivered.charAt(i)) {
      i++;
    }
    // Back up to the start of the token, so the report names whole parsers or literals.
    int start = i;
    while (start > 0 && " ([".indexOf(sent.charAt(start - 1)) < 0) {
      start--;
    }
    return "sent " + token(sent, start) + " delivered " + token(delivered, start);
  }

  private static String token(String text, int start) {
    int end = start;
    while (end < text.length() && " ()[]".indexOf(text.charAt(end)) < 0) {
      end++;
    }
    return start >= text.length() ? "(end)" : text.substring(start, end);
  }

  private Map<String, String> pathsByIndex() {
    // Redirect targets are referred to by their path from the root, which survives renumbering.
    List<int[]> queue = new ArrayList<>();
    String[] byIndex = new String[nodes.length];
    byIndex[root] = "";
    queue.add(new int[] {root});
    for (int q = 0; q < queue.size(); q++) {
      int index = queue.get(q)[0];
      for (int child : nodes[index].children()) {
        if (byIndex[child] == null) {
          byIndex[child] = byIndex[index] + "/" + nodes[child].name();
          queue.add(new int[] {child});
        }
      }
    }
    Map<String, String> paths = new TreeMap<>();
    for (int n = 0; n < byIndex.length; n++) {
      paths.put(Integer.toString(n), byIndex[n] == null ? "?" + n : byIndex[n]);
    }
    return paths;
  }

  private String canonical(int index, Map<String, String> paths, Set<Integer> visiting) {
    Node node = nodes[index];
    StringBuilder sb = new StringBuilder();
    sb.append('(').append(node.flags()).append(' ').append(node.name());
    if (node.parser() != null) {
      sb.append(' ').append(node.parser()).append('[').append(node.properties()).append(']');
    }
    if (node.suggestions() != null) {
      sb.append(" suggest=").append(node.suggestions());
    }
    if (node.redirect() >= 0) {
      sb.append(" ->").append(paths.get(Integer.toString(node.redirect())));
    }
    if (!visiting.add(index)) {
      return sb.append(" ...)").toString();
    }
    Map<String, String> children = new TreeMap<>();
    for (int child : node.children()) {
      children.put(nodes[child].name(), canonical(child, paths, visiting));
    }
    visiting.remove(index);
    for (String child : children.values()) {
      sb.append(' ').append(child);
    }
    return sb.append(')').toString();
  }
}
