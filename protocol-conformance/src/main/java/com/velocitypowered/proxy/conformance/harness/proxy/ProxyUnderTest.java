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
import com.velocitypowered.proxy.conformance.harness.ManagedProcess;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * A proxy jar run as a black box: configured through its own config file, started as a child
 * process, and talked to over loopback only.
 */
public abstract class ProxyUnderTest implements AutoCloseable {

  /**
   * How the proxy should be configured for a run.
   *
   * @param port the port to listen on
   * @param backend the address of the single backend (the tap relay)
   * @param onlineMode whether to authenticate players
   * @param hasJoined the session service's {@code hasJoined} endpoint
   */
  public record Settings(int port, InetSocketAddress backend, boolean onlineMode, URI hasJoined) {
  }

  protected final Path jar;
  protected final Path java;
  private ManagedProcess process;

  protected ProxyUnderTest(Path jar, Path java) {
    this.jar = jar;
    this.java = java;
  }

  /**
   * Creates the adapter for a proxy kind.
   *
   * @param kind {@code velocity} or {@code waterfall}
   * @param jar the proxy jar
   * @param java the Java executable to run it with
   * @return the adapter
   */
  public static ProxyUnderTest create(String kind, Path jar, Path java) {
    return switch (kind) {
      case "velocity" -> new VelocityProxy(jar, java);
      case "waterfall", "bungeecord" -> new WaterfallProxy(jar, java);
      default -> throw new IllegalArgumentException("unknown proxy kind " + kind);
    };
  }

  /**
   * Writes the proxy's configuration into {@code dir}.
   *
   * @param dir the proxy's working directory
   * @param settings the run settings
   * @throws HarnessException if the configuration cannot be written
   */
  protected abstract void configure(Path dir, Settings settings) throws HarnessException;

  /**
   * Returns extra JVM arguments.
   *
   * @param settings the run settings
   * @return the arguments
   */
  protected abstract List<String> jvmArgs(Settings settings);

  /**
   * Returns whether a console line means the proxy is accepting connections.
   *
   * @param line a console line
   * @return whether it is ready
   */
  protected abstract boolean isReadyLine(String line);

  /**
   * Configures and starts the proxy, returning once it is listening.
   *
   * @param dir a fresh working directory
   * @param settings the run settings
   * @throws HarnessException if it does not come up
   */
  public void start(Path dir, Settings settings) throws HarnessException {
    configure(dir, settings);
    List<String> command = new java.util.ArrayList<>();
    command.add(java.toString());
    command.add("-Xmx512M");
    command.addAll(jvmArgs(settings));
    // Extra flags for debugging the proxy under test, e.g. a log4j configuration.
    String extra = System.getProperty("conformance.proxyJvmArgs", "");
    for (String arg : extra.split(" ")) {
      if (!arg.isBlank()) {
        command.add(arg);
      }
    }
    command.add("-jar");
    command.add(jar.toAbsolutePath().toString());
    process = ManagedProcess.start("proxy", command, dir, dir.resolve("console.log"));
    process.awaitLine(this::isReadyLine, Duration.ofMinutes(2));
  }

  public ManagedProcess process() {
    return process;
  }

  @Override
  public void close() {
    if (process != null) {
      process.close();
    }
  }
}
