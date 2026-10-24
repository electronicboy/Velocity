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

package com.velocitypowered.proxy.conformance.harness.proxy;

import com.velocitypowered.proxy.conformance.harness.HarnessException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * BungeeCord or Waterfall, configured with a generated {@code config.yml}; anything not set here
 * takes BungeeCord's defaults.
 */
final class WaterfallProxy extends ProxyUnderTest {

  WaterfallProxy(Path jar, Path java) {
    super(jar, java);
  }

  @Override
  protected void configure(Path dir, Settings settings) throws HarnessException {
    String backend = settings.backend().getHostString() + ":" + settings.backend().getPort();
    String config = """
        online_mode: %s
        ip_forward: false
        prevent_proxy_connections: false
        enforce_secure_profile: false
        connection_throttle: -1
        network_compression_threshold: 256
        log_pings: false
        listeners:
        - host: 127.0.0.1:%d
          query_enabled: false
          motd: conformance
          max_players: 10
          priorities:
          - conformance
          force_default_server: true
          ping_passthrough: false
          tab_list: GLOBAL_PING
          forced_hosts: {}
        servers:
          conformance:
            address: %s
            restricted: false
            motd: conformance
        permissions:
          default:
          - bungeecord.command.server
          - bungeecord.command.list
        groups: {}
        """.formatted(settings.onlineMode(), settings.port(), backend);
    try {
      Files.createDirectories(dir);
      Files.writeString(dir.resolve("config.yml"), config, StandardCharsets.UTF_8);
      // Waterfall's own settings file; keep defaults but make sure it exists so first start does
      // not race the harness.
      Files.writeString(dir.resolve("waterfall.yml"), "", StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new HarnessException("cannot write config.yml", e);
    }
  }

  @Override
  protected List<String> jvmArgs(Settings settings) {
    return List.of(
        "-Dwaterfall.auth.url=" + settings.hasJoined() + "?username=%s&serverId=%s%s",
        "-DIReallyKnowWhatIAmDoingISwear=true");
  }

  @Override
  protected boolean isReadyLine(String line) {
    return line.contains("Listening on");
  }
}
