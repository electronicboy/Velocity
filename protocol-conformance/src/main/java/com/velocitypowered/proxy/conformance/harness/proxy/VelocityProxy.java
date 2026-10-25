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

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.toml.TomlParser;
import com.electronwill.nightconfig.toml.TomlWriter;
import com.velocitypowered.proxy.conformance.harness.HarnessException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Velocity, configured from the default {@code velocity.toml} inside the jar under test so that
 * the harness keeps working across config versions: only the keys the run needs are overridden.
 */
final class VelocityProxy extends ProxyUnderTest {

  VelocityProxy(Path jar, Path java) {
    super(jar, java);
  }

  @Override
  protected void configure(Path dir, Settings settings) throws HarnessException {
    CommentedConfig config;
    try (ZipFile zip = new ZipFile(jar.toFile())) {
      ZipEntry entry = zip.getEntry("default-velocity.toml");
      if (entry == null) {
        throw new HarnessException(jar + " has no default-velocity.toml; is it Velocity?");
      }
      try (InputStream in = zip.getInputStream(entry)) {
        config = new TomlParser().parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
      }
    } catch (IOException e) {
      throw new HarnessException("cannot read " + jar, e);
    }

    config.set("bind", "127.0.0.1:" + settings.port());
    config.set("online-mode", settings.onlineMode());
    // Profile keys are signed by Mojang; a local session service cannot produce them.
    config.set("force-key-authentication", false);
    // No forwarding is what lets a stock vanilla server sit behind the proxy at all.
    config.set("player-info-forwarding-mode", "none");
    config.set("prevent-client-proxy-connections", false);

    Config servers = config.createSubConfig();
    settings.backends().forEach((name, address) ->
        servers.set(List.of(name), address.getHostString() + ":" + address.getPort()));
    servers.set("try", List.of(settings.primary()));
    config.set("servers", servers);
    config.set("forced-hosts", config.createSubConfig());

    // Every scenario reconnects from loopback in quick succession.
    config.set("advanced.login-ratelimit", 0);
    config.set("advanced.log-player-connections", true);

    try {
      Files.createDirectories(dir);
      Files.writeString(dir.resolve("velocity.toml"), new TomlWriter().writeToString(config),
          StandardCharsets.UTF_8);
      // Test runs must not report to bStats.
      Path metrics = dir.resolve("plugins").resolve("bStats");
      Files.createDirectories(metrics);
      Files.writeString(metrics.resolve("config.txt"), """
          enabled=false
          server-uuid=00000000-0000-0000-0000-000000000000
          log-errors=false
          log-sent-data=false
          log-response-status-text=false
          """, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new HarnessException("cannot write velocity.toml", e);
    }
  }

  @Override
  protected List<String> jvmArgs(Settings settings) {
    return List.of(
        "-Dmojang.sessionserver=" + settings.hasJoined(),
        // Decode failures are logged with the offending packet, which a finding can point at.
        "-Dvelocity.packet-decode-logging=true");
  }

  @Override
  protected boolean isReadyLine(String line) {
    return line.contains("Done (") || line.contains("Listening on");
  }
}
