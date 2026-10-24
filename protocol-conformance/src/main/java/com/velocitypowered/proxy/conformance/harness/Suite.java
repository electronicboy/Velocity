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

package com.velocitypowered.proxy.conformance.harness;

import com.velocitypowered.proxy.conformance.harness.auth.FakeSessionService;
import com.velocitypowered.proxy.conformance.harness.diff.ProxyRules;
import com.velocitypowered.proxy.conformance.harness.proxy.ProxyUnderTest;
import com.velocitypowered.proxy.conformance.harness.report.CellResult;
import com.velocitypowered.proxy.conformance.harness.report.Coverage;
import com.velocitypowered.proxy.conformance.harness.report.Ledger;
import com.velocitypowered.proxy.conformance.harness.report.Reporter;
import com.velocitypowered.proxy.conformance.harness.scenario.AuthRejectScenario;
import com.velocitypowered.proxy.conformance.harness.scenario.ExerciseScenario;
import com.velocitypowered.proxy.conformance.harness.scenario.FullRegistriesScenario;
import com.velocitypowered.proxy.conformance.harness.scenario.OnlineLoginScenario;
import com.velocitypowered.proxy.conformance.harness.scenario.Scenario;
import com.velocitypowered.proxy.conformance.harness.scenario.ScenarioContext;
import com.velocitypowered.proxy.conformance.harness.scenario.ScenarioOutput;
import com.velocitypowered.proxy.conformance.harness.scenario.StatusScenario;
import com.velocitypowered.proxy.conformance.harness.tap.TapRelay;
import com.velocitypowered.proxy.conformance.harness.vanilla.JavaRuntimes;
import com.velocitypowered.proxy.conformance.harness.vanilla.VanillaDistribution;
import com.velocitypowered.proxy.conformance.harness.vanilla.VanillaServer;
import com.velocitypowered.proxy.conformance.harness.vanilla.VanillaVersion;
import com.velocitypowered.proxy.conformance.harness.wire.PacketTable;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Runs every scenario against every requested version: one vanilla server and tap relay per
 * version, one proxy process per proxy configuration (offline, then online).
 */
public final class Suite {

  /**
   * The scenarios, in run order. Offline-mode scenarios share one proxy process and online-mode
   * ones another.
   */
  public static final List<Scenario> SCENARIOS = List.of(
      new StatusScenario(),
      new ExerciseScenario(),
      new FullRegistriesScenario(),
      new OnlineLoginScenario(),
      new AuthRejectScenario());

  private final SuiteConfig config;
  private final VanillaDistribution distribution;
  private final JavaRuntimes java;
  private final Ledger ledger;
  private final Reporter reporter;
  private final ProxyRules rules;

  /**
   * Creates a suite.
   *
   * @param config the run configuration
   * @param ledger the expectations
   * @param reporter where results go
   */
  public Suite(SuiteConfig config, Ledger ledger, Reporter reporter) {
    this.config = config;
    this.java = new JavaRuntimes(config.jdks());
    this.distribution = new VanillaDistribution(config.cache(), java);
    this.ledger = ledger;
    this.reporter = reporter;
    this.rules = ProxyRules.forKind(config.proxyKind());
  }

  /**
   * Runs every scenario for one version and records the results.
   *
   * @param id the version id
   * @throws IOException if the report cannot be written
   */
  public void runVersion(String id) throws IOException {
    Instant started = Instant.now();
    List<Scenario> scenarios = SCENARIOS.stream().filter(selected()).toList();
    Optional<VanillaVersion> resolved;
    PacketTable table;
    String[] argumentTypes;
    try {
      resolved = distribution.resolve(id);
      if (resolved.isEmpty()) {
        for (Scenario s : scenarios) {
          reporter.add(CellResult.skipped(id, s.id(), "Mojang does not publish a server for "
              + id + " (yet)"));
        }
        reporter.write();
        return;
      }
      table = distribution.packetTable(resolved.get());
      argumentTypes = distribution.argumentTypes(resolved.get()).orElse(null);
    } catch (IOException | RuntimeException e) {
      for (Scenario s : scenarios) {
        reporter.add(CellResult.error(id, s.id(), "cannot prepare " + id + ": " + trace(e),
            Duration.between(started, Instant.now())));
      }
      reporter.write();
      return;
    }

    VanillaVersion version = resolved.get();
    System.out.println("[suite] " + version + " — packets from " + table.source());
    Path dir = config.work().resolve(id);
    Coverage coverage = new Coverage(id, table);
    try (FakeSessionService auth = FakeSessionService.start()) {
      Path jar = distribution.serverJar(version);
      try (VanillaServer server = VanillaServer.start(version, jar,
          java.forMajor(version.javaMajor()), dir.resolve("vanilla"), freePort(), freePort());
          TapRelay tap = new TapRelay(new InetSocketAddress(InetAddress.getLoopbackAddress(),
              server.port()), table, version.protocol())) {
        for (boolean online : new boolean[] {false, true}) {
          List<Scenario> group = scenarios.stream()
              .filter(s -> s.onlineMode() == online)
              .filter(s -> s.appliesTo(version))
              .toList();
          if (!group.isEmpty()) {
            runGroup(version, table, argumentTypes, server, tap, auth, online, group, coverage,
                dir);
          }
        }
        for (Scenario s : scenarios) {
          if (!s.appliesTo(version)) {
            reporter.add(CellResult.skipped(id, s.id(), "not applicable to " + id));
          }
        }
      }
    } catch (HarnessException | IOException | RuntimeException e) {
      for (Scenario s : scenarios) {
        if (reporter.results().stream().noneMatch(r -> r.version().equals(id)
            && r.scenario().equals(s.id()))) {
          reporter.add(CellResult.error(id, s.id(), trace(e),
              Duration.between(started, Instant.now())));
        }
      }
    }
    reporter.add(coverage);
    reporter.write();
  }

  private void runGroup(VanillaVersion version, PacketTable table, String[] argumentTypes,
      VanillaServer server, TapRelay tap, FakeSessionService auth, boolean online,
      List<Scenario> group, Coverage coverage, Path dir) throws HarnessException {
    Path proxyDir = dir.resolve(online ? "proxy-online" : "proxy-offline");
    int port = freePort();
    try (ProxyUnderTest proxy = ProxyUnderTest.create(config.proxyKind(), config.proxyJar(),
        config.proxyJava())) {
      proxy.start(proxyDir, new ProxyUnderTest.Settings(port,
          new InetSocketAddress(InetAddress.getLoopbackAddress(), tap.port()), online,
          auth.hasJoinedUri()));
      InetSocketAddress address = new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
      for (Scenario scenario : group) {
        Instant started = Instant.now();
        System.out.println("[suite] " + version.id() + " " + scenario.id());
        ScenarioContext ctx = new ScenarioContext(version, table, argumentTypes, server, tap, auth,
            address, proxy.process(), rules,
            dir.resolve("traces").resolve(scenario.id().replace('/', '_')), coverage);
        CellResult result;
        try {
          ScenarioOutput out = scenario.run(ctx);
          result = CellResult.judge(version.id(), scenario.id(), out.failures(),
              ledger.expectations(config.proxyKind(), scenario.id(), version.id()),
              out.tolerated(), out.matched(), Duration.between(started, Instant.now()));
        } catch (HarnessException | RuntimeException e) {
          result = CellResult.error(version.id(), scenario.id(), trace(e),
              Duration.between(started, Instant.now()));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new HarnessException("interrupted", e);
        }
        System.out.println("[suite] " + version.id() + " " + scenario.id() + " -> "
            + result.outcome() + " (" + result.matched() + " intact, "
            + result.unexpected().size() + " unexpected, " + result.expected().size()
            + " known)");
        result.unexpected().stream().limit(20).forEach(f -> System.out.println("    " + f));
        reporter.add(result);
      }
    }
  }

  private Predicate<Scenario> selected() {
    return s -> config.scenarios().isEmpty() || config.scenarios().contains(s.id());
  }

  private static int freePort() throws HarnessException {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    } catch (IOException e) {
      throw new HarnessException("cannot allocate a port", e);
    }
  }

  private static String trace(Throwable t) {
    StringWriter sw = new StringWriter();
    t.printStackTrace(new PrintWriter(sw));
    return sw.toString();
  }
}
