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

import com.velocitypowered.proxy.conformance.harness.HarnessException;
import com.velocitypowered.proxy.conformance.harness.ManagedProcess;
import com.velocitypowered.proxy.conformance.harness.wire.Protocols;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A vanilla server configured for conformance runs: offline, no forwarding, a flat world with
 * nothing spawning on its own, and RCON enabled so the harness can provoke traffic.
 */
public final class VanillaServer implements AutoCloseable {

  private static final Duration STARTUP = Duration.ofMinutes(3);
  private static final String RCON_PASSWORD = "conformance";

  private final VanillaVersion version;
  private final ManagedProcess process;
  private final int port;
  private final Rcon rcon;

  private VanillaServer(VanillaVersion version, ManagedProcess process, int port, Rcon rcon) {
    this.version = version;
    this.process = process;
    this.port = port;
    this.rcon = rcon;
  }

  /**
   * Lays out and starts a server, returning once it accepts RCON commands.
   *
   * @param version the version
   * @param jar its server jar
   * @param java the Java executable
   * @param dir a fresh directory for the server
   * @param port the game port
   * @param rconPort the RCON port
   * @return the running server
   * @throws HarnessException if it does not come up in time
   */
  public static VanillaServer start(VanillaVersion version, Path jar, Path java, Path dir,
      int port, int rconPort) throws HarnessException {
    try {
      Files.createDirectories(dir);
      Files.writeString(dir.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8);
      Files.writeString(dir.resolve("server.properties"), properties(version, port, rconPort),
          StandardCharsets.UTF_8);

      ManagedProcess process = ManagedProcess.start("vanilla-" + version.id(),
          List.of(java.toString(), "-Xms512M", "-Xmx1536M", "-jar",
              jar.toAbsolutePath().toString(), "nogui"),
          dir, dir.resolve("console.log"));
      try {
        process.awaitLine(line -> line.contains("RCON running on"), STARTUP);
        process.awaitLine(line -> line.contains("Done ("), STARTUP);
        return new VanillaServer(version, process, port, connectRcon(rconPort));
      } catch (HarnessException | IOException e) {
        process.close();
        throw e instanceof HarnessException h ? h
            : new HarnessException("vanilla " + version.id() + " RCON failed", e);
      }
    } catch (IOException e) {
      throw new HarnessException("cannot lay out vanilla " + version.id(), e);
    }
  }

  private static Rcon connectRcon(int rconPort) throws IOException {
    // Older servers announce RCON a moment before the listener reliably accepts logins.
    IOException last = null;
    for (int attempt = 0; attempt < 10; attempt++) {
      try {
        return Rcon.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), rconPort),
            RCON_PASSWORD);
      } catch (IOException e) {
        last = e;
        try {
          Thread.sleep(1000);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IOException(interrupted);
        }
      }
    }
    throw last;
  }

  public int port() {
    return port;
  }

  public VanillaVersion version() {
    return version;
  }

  public ManagedProcess process() {
    return process;
  }

  /**
   * Runs a console command over RCON.
   *
   * @param command the command, without a slash
   * @return the reply text
   * @throws HarnessException if RCON fails
   */
  public String command(String command) throws HarnessException {
    try {
      return rcon.command(command);
    } catch (IOException e) {
      throw new HarnessException("RCON command failed: " + command, e);
    }
  }

  private static String properties(VanillaVersion version, int port, int rconPort) {
    Map<String, String> props = new LinkedHashMap<>();
    props.put("server-ip", "127.0.0.1");
    props.put("server-port", Integer.toString(port));
    props.put("online-mode", "false");
    // 26.3 started defaulting to an enabled whitelist.
    props.put("white-list", "false");
    props.put("enforce-whitelist", "false");
    props.put("enforce-secure-profile", "false");
    props.put("prevent-proxy-connections", "false");
    props.put("enable-rcon", "true");
    props.put("rcon.port", Integer.toString(rconPort));
    props.put("rcon.password", RCON_PASSWORD);
    props.put("broadcast-rcon-to-ops", "false");
    props.put("broadcast-console-to-ops", "false");
    props.put("level-type", version.atLeast(Protocols.V1_19) ? "minecraft:flat" : "FLAT");
    props.put("generate-structures", "false");
    props.put("spawn-animals", "false");
    props.put("spawn-monsters", "false");
    props.put("spawn-npcs", "false");
    props.put("spawn-protection", "0");
    props.put("difficulty", version.atLeast(Protocols.V1_13) ? "easy" : "1");
    props.put("view-distance", "3");
    props.put("simulation-distance", "3");
    props.put("allow-flight", "true");
    props.put("enable-command-block", "true");
    props.put("player-idle-timeout", "0");
    props.put("max-tick-time", "-1");
    props.put("sync-chunk-writes", "false");
    props.put("network-compression-threshold", "256");
    props.put("motd", "conformance " + version.id());
    return props.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
        .collect(Collectors.joining("\n", "", "\n"));
  }

  @Override
  public void close() {
    try {
      rcon.command("stop");
    } catch (IOException ignored) {
      // Fall through to killing it.
    }
    try {
      rcon.close();
    } catch (IOException ignored) {
      // Already gone.
    }
    process.stop(Duration.ofSeconds(30));
  }
}
