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

import com.velocitypowered.proxy.conformance.harness.trace.TraceEvent;
import com.velocitypowered.proxy.conformance.harness.wire.Buf;
import com.velocitypowered.proxy.conformance.harness.wire.Direction;
import com.velocitypowered.proxy.conformance.harness.wire.Protocols;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;

/**
 * Comparators shared by every proxy that rewrites the brand and merges its own commands into the
 * backend's command tree.
 */
abstract class CommonRules implements ProxyRules {

  /**
   * Returns the root literals the proxy is allowed to add to the command tree.
   *
   * @return the command names
   */
  abstract Set<String> proxyCommands();

  /**
   * Returns the proxy name it appends to the brand.
   *
   * @return the name
   */
  abstract String brandName();

  Optional<String> brandRewrite(RuleContext ctx, TraceEvent source, TraceEvent sink) {
    if (source.dir() != Direction.CLIENTBOUND || !"minecraft:custom_payload".equals(
        ctx.name(source))) {
      return Optional.empty();
    }
    Buf a = new Buf(source.payload());
    Buf b = new Buf(sink.payload());
    String channel = a.readString();
    if (!channel.equals(b.readString())
        || !(channel.equals("minecraft:brand") || channel.equals("MC|Brand"))) {
      return Optional.empty();
    }
    String original = readBrand(ctx, a);
    String rewritten = readBrand(ctx, b);
    if (rewritten.contains(original) && rewritten.contains(brandName())) {
      return Optional.of(brandName() + " appends itself to the backend brand (\"" + original
          + "\" -> \"" + rewritten + "\")");
    }
    return Optional.empty();
  }

  private static String readBrand(RuleContext ctx, Buf buf) {
    if (ctx.atLeast(Protocols.V1_8)) {
      return buf.readString();
    }
    return new String(buf.readBytes(buf.readUnsignedShort()), StandardCharsets.UTF_8);
  }

  Optional<String> commandMerge(RuleContext ctx, TraceEvent source, TraceEvent sink) {
    if (source.dir() != Direction.CLIENTBOUND || !"minecraft:commands".equals(ctx.name(source))) {
      return Optional.empty();
    }
    try {
      CommandTree a = CommandTree.decode(source.payload(), ctx.protocol(), ctx.argumentTypes());
      CommandTree b = CommandTree.decode(sink.payload(), ctx.protocol(), ctx.argumentTypes());
      String problem = CommandTree.compareWithExtras(a, b, proxyCommands());
      if (problem == null) {
        return Optional.of(brandName() + " re-encodes the command tree and merges in its own"
            + " commands; every backend node survives unchanged");
      }
      return Optional.empty();
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  /**
   * Reads the channel of a custom payload body.
   *
   * @param payload the packet body
   * @return the channel, or {@code null} if unreadable
   */
  static String channelOf(byte[] payload) {
    try {
      return new Buf(payload).readString();
    } catch (RuntimeException e) {
      return null;
    }
  }

  @Override
  public Optional<String> explainMismatch(RuleContext ctx, TraceEvent source, TraceEvent sink) {
    if ("minecraft:commands".equals(ctx.name(source))) {
      return Optional.ofNullable(commandProblem(ctx, source, sink));
    }
    return Optional.empty();
  }

  /**
   * Explains why a command tree comparison failed, for the finding detail.
   *
   * @param ctx the comparison context
   * @param source what the backend sent
   * @param sink what the client received
   * @return the explanation
   */
  String commandProblem(RuleContext ctx, TraceEvent source, TraceEvent sink) {
    try {
      CommandTree a = CommandTree.decode(source.payload(), ctx.protocol(), ctx.argumentTypes());
      CommandTree b = CommandTree.decode(sink.payload(), ctx.protocol(), ctx.argumentTypes());
      return CommandTree.compareWithExtras(a, b, proxyCommands());
    } catch (RuntimeException e) {
      return "undecodable command tree: " + e.getMessage();
    }
  }

  /**
   * Tolerates join game and respawn packets whose registry NBT was re-encoded with compound keys
   * in a different order (1.16 to 1.20.1, where these packets carried NBT). Everything outside the
   * NBT must still be byte-identical.
   */
  Optional<String> nbtReorder(RuleContext ctx, TraceEvent source, TraceEvent sink) {
    if (source.dir() != Direction.CLIENTBOUND || !ctx.atLeast(Protocols.V1_16)
        || ctx.atLeast(Protocols.V1_20_2)) {
      return Optional.empty();
    }
    String name = ctx.name(source);
    try {
      String a;
      String b;
      if ("minecraft:login".equals(name)) {
        a = joinGameCanonical(ctx, new Buf(source.payload()));
        b = joinGameCanonical(ctx, new Buf(sink.payload()));
      } else if ("minecraft:respawn".equals(name) && !ctx.atLeast(Protocols.V1_19)
          && ctx.atLeast(751)) {
        a = respawnCanonical(new Buf(source.payload()));
        b = respawnCanonical(new Buf(sink.payload()));
      } else {
        return Optional.empty();
      }
      if (a.equals(b)) {
        return Optional.of(brandName() + " re-encodes " + name + "; its registry NBT is equal "
            + "once compound key order (insignificant: vanilla compounds are hash maps) is "
            + "ignored, and every other byte is identical");
      }
    } catch (RuntimeException e) {
      return Optional.empty();
    }
    return Optional.empty();
  }

  private static String joinGameCanonical(RuleContext ctx, Buf buf) {
    StringBuilder sb = new StringBuilder();
    sb.append(buf.readInt()).append('|');
    if (ctx.atLeast(751)) {
      sb.append(buf.readBoolean()).append('|');
    }
    sb.append(buf.readByte()).append('|').append(buf.readByte()).append('|');
    int worlds = buf.readVarInt();
    for (int i = 0; i < worlds; i++) {
      sb.append(buf.readString()).append(',');
    }
    sb.append('|').append(Nbt.readCanonical(buf, true)).append('|');
    if (ctx.atLeast(751) && !ctx.atLeast(Protocols.V1_19)) {
      sb.append(Nbt.readCanonical(buf, true)).append('|');
    }
    return sb.append(HexFormat.of().formatHex(buf.readRemaining())).toString();
  }

  private static String respawnCanonical(Buf buf) {
    return Nbt.readCanonical(buf, true) + "|"
        + HexFormat.of().formatHex(buf.readRemaining());
  }

  @Override
  public Optional<String> tolerateMismatch(RuleContext ctx, TraceEvent source, TraceEvent sink) {
    Optional<String> brand = brandRewrite(ctx, source, sink);
    if (brand.isPresent()) {
      return brand;
    }
    Optional<String> nbt = nbtReorder(ctx, source, sink);
    if (nbt.isPresent()) {
      return nbt;
    }
    return commandMerge(ctx, source, sink);
  }
}
