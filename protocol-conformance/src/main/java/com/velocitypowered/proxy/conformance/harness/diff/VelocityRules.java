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
import com.velocitypowered.proxy.conformance.harness.wire.Phase;
import com.velocitypowered.proxy.conformance.harness.wire.Protocols;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Velocity's documented transformations of vanilla traffic. Each tolerance cites the code that
 * causes it (paths relative to {@code proxy/src/main/java/com/velocitypowered/proxy}).
 */
final class VelocityRules extends CommonRules {

  // Built-in commands (command/builtin). `shutdown` is console-only and `velocity:callback` is
  // the click-callback command injected for 1.21.6+ clients (BackendPlaySessionHandler).
  private static final Set<String> COMMANDS = Set.of("velocity", "server", "glist", "send",
      "shutdown", "callback", "velocity:callback");

  @Override
  public String kind() {
    return "velocity";
  }

  @Override
  Set<String> proxyCommands() {
    return COMMANDS;
  }

  @Override
  String brandName() {
    return "Velocity";
  }

  @Override
  public Optional<String> tolerateMismatch(RuleContext ctx, TraceEvent source, TraceEvent sink) {
    Optional<String> common = super.tolerateMismatch(ctx, source, sink);
    if (common.isPresent()) {
      return common;
    }
    if (source.dir() == Direction.CLIENTBOUND && source.phase() == Phase.PLAY
        && "minecraft:server_data".equals(ctx.name(source))
        && source.payload().length > 0 && sink.payload().length > 0
        && source.payload()[source.payload().length - 1]
            == sink.payload()[sink.payload().length - 1]) {
      return Optional.of("Velocity answers server_data with its own ping response (MOTD and "
          + "favicon via ProxyPingEvent) and keeps the backend's secure-chat flag "
          + "(connection/backend/BackendPlaySessionHandler#handle(ServerDataPacket))");
    }
    if (source.dir() == Direction.CLIENTBOUND && source.phase() == Phase.PLAY
        && "minecraft:login".equals(ctx.name(source)) && onlyOnlineModeDiffers(source, sink)) {
      return Optional.of("Velocity sets the join game online-mode flag from the player's own "
          + "authentication, not the offline backend's "
          + "(connection/backend/TransitionSessionHandler: packet.setOnlineMode)");
    }
    if (source.dir() == Direction.CLIENTBOUND
        && "minecraft:disconnect".equals(ctx.name(source))
        && textOf(sink.payload()).containsAll(textOf(source.payload()))) {
      return Optional.of("Velocity wraps a backend kick in its own message, keeping the "
          + "backend's reason (KickedFromServerEvent, velocity.error.kicked-from-server)");
    }
    if (source.dir() == Direction.CLIENTBOUND && !ctx.atLeast(Protocols.V1_13)
        && "minecraft:command_suggestions".equals(ctx.name(source))
        && sameSuggestionsInAnyOrder(source.payload(), sink.payload())) {
      return Optional.of("Velocity merges its own commands into pre-1.13 tab-complete responses "
          + "and sorts the result; the backend's suggestions all arrive "
          + "(connection/client/ClientPlaySessionHandler)");
    }
    return Optional.empty();
  }

  /**
   * True when the only difference is the online-mode flag, the second-to-last byte of join game
   * from 26.x (followed by enforcesSecureChat), switching from offline to online.
   */
  private static boolean onlyOnlineModeDiffers(TraceEvent source, TraceEvent sink) {
    byte[] a = source.payload();
    byte[] b = sink.payload();
    if (a.length != b.length || a.length < 2) {
      return false;
    }
    int flag = a.length - 2;
    return a[flag] == 0 && b[flag] == 1 && Arrays.equals(a, 0, flag, b, 0, flag)
        && a[a.length - 1] == b[b.length - 1];
  }

  /**
   * Extracts the printable runs of a chat component, whether JSON or NBT encoded.
   */
  private static Set<String> textOf(byte[] payload) {
    Set<String> runs = new HashSet<>();
    StringBuilder run = new StringBuilder();
    for (byte b : payload) {
      // Word characters plus the punctuation of translation keys; JSON and NBT structure split.
      if (Character.isLetterOrDigit(b) || b == '.' || b == '_' || b == '-' || b == ' ') {
        run.append((char) b);
      } else {
        if (run.toString().strip().length() >= 6) {
          runs.add(run.toString().strip());
        }
        run.setLength(0);
      }
    }
    if (run.toString().strip().length() >= 6) {
      runs.add(run.toString().strip());
    }
    // Structural keys appear in any component and say nothing about the reason.
    runs.removeIf(r -> r.matches("[a-z_]+"));
    return runs;
  }

  @Override
  public Optional<String> tolerateSwitchInjection(RuleContext ctx, TraceEvent sink,
      boolean newEpoch) {
    if (sink.dir() != Direction.CLIENTBOUND || sink.phase() != Phase.PLAY) {
      return Optional.empty();
    }
    String name = ctx.name(sink);
    if (!newEpoch && (name.equals("minecraft:player_info_remove")
        || (name.equals("minecraft:player_info_update") && isLegacyTabRemoval(ctx, sink)))) {
      return Optional.of("Velocity removes the old server's tab list entries when switching "
          + "(ClientPlaySessionHandler#handleBackendJoinGame: tabList.clearAll)");
    }
    if (newEpoch && name.equals("minecraft:respawn")
        && !Protocols.hasConfiguration(ctx.protocol())) {
      return Optional.of("Velocity follows the new server's join game with respawns so the "
          + "client resets its world (ClientPlaySessionHandler#doFastClientServerSwitch / "
          + "#doSafeClientServerSwitch)");
    }
    return Optional.empty();
  }

  /**
   * Before 1.19.3 removal is the player info packet's action 4; 1.7 has no actions, and an entry
   * with the online flag cleared removes it.
   */
  private static boolean isLegacyTabRemoval(RuleContext ctx, TraceEvent sink) {
    byte[] payload = sink.payload();
    if (ctx.atLeast(Protocols.V1_19_3) || payload.length == 0) {
      return false;
    }
    if (ctx.atLeast(Protocols.V1_8)) {
      return payload[0] == 4;
    }
    try {
      Buf buf = new Buf(payload);
      buf.readString();
      return !buf.readBoolean() && buf.readableBytes() == 2;
    } catch (RuntimeException e) {
      return false;
    }
  }

  @Override
  public Optional<String> tolerateSwitchMismatch(RuleContext ctx, TraceEvent source,
      TraceEvent sink, boolean newEpoch) {
    // Before 1.16 the join game of a switch is sent in the other dimension (0 <-> -1), the int
    // after entity id and game mode, and a respawn then moves the client to the real one.
    if (!newEpoch || ctx.atLeast(Protocols.V1_16)
        || !ctx.name(source).equals("minecraft:login")) {
      return Optional.empty();
    }
    byte[] a = source.payload();
    byte[] b = sink.payload();
    if (a.length != b.length || a.length < 9) {
      return Optional.empty();
    }
    int dimension = 5;
    if (Arrays.equals(a, 0, dimension, b, 0, dimension)
        && Arrays.equals(a, dimension + 4, a.length, b, dimension + 4, b.length)) {
      return Optional.of("Velocity sends the new server's join game in the other dimension and "
          + "respawns the client into the real one (ClientPlaySessionHandler"
          + "#doFastClientServerSwitch)");
    }
    return Optional.empty();
  }

  private static boolean sameSuggestionsInAnyOrder(byte[] source, byte[] sink) {
    try {
      return readSuggestions(source).equals(readSuggestions(sink));
    } catch (RuntimeException e) {
      return false;
    }
  }

  private static List<String> readSuggestions(byte[] payload) {
    Buf buf = new Buf(payload);
    List<String> out = new ArrayList<>();
    int count = buf.readVarInt();
    for (int i = 0; i < count; i++) {
      out.add(buf.readString());
    }
    if (buf.readableBytes() != 0) {
      throw new IllegalArgumentException("trailing bytes");
    }
    Collections.sort(out);
    return out;
  }

  @Override
  public Optional<String> tolerateInjection(RuleContext ctx, TraceEvent sink) {
    String name = ctx.name(sink);
    byte[] payload = sink.payload();
    if (sink.dir() == Direction.CLIENTBOUND && sink.phase() == Phase.PLAY) {
      if ("minecraft:tab_list".equals(name) && isTwoIdenticalHalves(payload)
          && payload.length <= 40) {
        return Optional.of("Velocity clears the tab list header and footer when joining a "
            + "server (connection/backend/TransitionSessionHandler)");
      }
      if (("minecraft:clear_titles".equals(name) || "minecraft:set_titles".equals(name))
          && payload.length == 1) {
        return Optional.of("Velocity resets titles left over from a previous server "
            + "(connection/client/ClientPlaySessionHandler#handleBackendJoinGame)");
      }
    }
    if (sink.dir() == Direction.SERVERBOUND && sink.phase() == Phase.PLAY
        && "minecraft:custom_payload".equals(name)) {
      String channel = channelOf(payload);
      if ("minecraft:register".equals(channel) || "REGISTER".equals(channel)) {
        return Optional.of("Velocity re-announces the client's plugin channels to each backend "
            + "it joins (connection/client/ClientPlaySessionHandler#handleBackendJoinGame)");
      }
    }
    return Optional.empty();
  }

  @Override
  public Optional<String> tolerateReorder(RuleContext ctx, TraceEvent event) {
    if (event.dir() != Direction.SERVERBOUND || event.phase() != Phase.CONFIGURATION) {
      return Optional.empty();
    }
    String name = ctx.name(event);
    // Whichever of the pair the alignment reports, the cause is the same: the brand is held back
    // and the settings are replayed from what the client sent before the backend existed.
    boolean brand = "minecraft:custom_payload".equals(name)
        && "minecraft:brand".equals(channelOf(event.payload()));
    if (brand || "minecraft:client_information".equals(name)) {
      return Optional.of("Velocity holds the client's brand until the backend finishes "
          + "configuration and replays its stored settings on connect, so the two can swap "
          + "(connection/client/ClientConfigSessionHandler)");
    }
    return Optional.empty();
  }

  private static boolean isTwoIdenticalHalves(byte[] payload) {
    if (payload.length == 0 || payload.length % 2 != 0) {
      return false;
    }
    int half = payload.length / 2;
    return Arrays.equals(payload, 0, half, payload, half, payload.length);
  }
}
