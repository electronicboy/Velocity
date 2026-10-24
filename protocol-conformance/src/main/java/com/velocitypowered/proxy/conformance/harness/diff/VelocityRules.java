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
import com.velocitypowered.proxy.conformance.harness.wire.Direction;
import com.velocitypowered.proxy.conformance.harness.wire.Phase;
import java.util.Arrays;
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
    return Optional.empty();
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
    if (event.dir() == Direction.SERVERBOUND && event.phase() == Phase.CONFIGURATION
        && "minecraft:custom_payload".equals(ctx.name(event))
        && "minecraft:brand".equals(channelOf(event.payload()))) {
      return Optional.of("Velocity holds the client's brand until the backend finishes "
          + "configuration, then replays it "
          + "(connection/client/ClientConfigSessionHandler#handleBackendFinishUpdate)");
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
