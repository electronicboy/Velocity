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

import com.velocitypowered.proxy.conformance.harness.report.Ledger;
import com.velocitypowered.proxy.conformance.harness.report.Reporter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Command-line entry point for the vanilla conformance harness.
 *
 * <pre>
 * java -jar conformance-harness.jar --proxy-jar velocity.jar --proxy-kind velocity \
 *     --versions 1.8.9,1.21.11,26.3 [--work build/work] [--report build/report] \
 *     [--ledger ledger.toml] [--cache ~/.cache/conformance] [--jdks /jdk17:/jdk25] \
 *     [--proxy-java /jdk25/bin/java] [--scenarios play/exercise,auth/online] [--gate]
 * </pre>
 *
 * <p>{@code --versions @file} reads one version per line.</p>
 */
public final class Main {

  private Main() {
  }

  /**
   * Runs the suite.
   *
   * @param args the command line
   * @throws IOException if reports cannot be written
   */
  public static void main(String[] args) throws IOException {
    Map<String, String> opts = new HashMap<>();
    Set<String> flags = new LinkedHashSet<>();
    for (int i = 0; i < args.length; i++) {
      if (!args[i].startsWith("--")) {
        usage("unexpected argument " + args[i]);
      }
      String key = args[i].substring(2);
      if (key.equals("gate")) {
        flags.add(key);
      } else if (i + 1 < args.length) {
        opts.put(key, args[++i]);
      } else {
        usage("missing value for --" + key);
      }
    }
    if (!opts.containsKey("proxy-jar") || !opts.containsKey("versions")) {
      usage("--proxy-jar and --versions are required");
    }

    Path work = Path.of(opts.getOrDefault("work", "conformance-work")).toAbsolutePath();
    Path report = Path.of(opts.getOrDefault("report", "conformance-report")).toAbsolutePath();
    Path cache = Path.of(opts.getOrDefault("cache", work.resolve("cache").toString()))
        .toAbsolutePath();
    Path ledgerFile = opts.containsKey("ledger") ? Path.of(opts.get("ledger")) : null;
    List<Path> jdks = new ArrayList<>();
    if (opts.containsKey("jdks")) {
      for (String jdk : opts.get("jdks").split(File.pathSeparator)) {
        if (!jdk.isBlank()) {
          jdks.add(Path.of(jdk));
        }
      }
    }
    Path proxyJava = Path.of(opts.getOrDefault("proxy-java",
        Path.of(System.getProperty("java.home"), "bin", "java").toString()));
    Set<String> scenarios = new LinkedHashSet<>();
    if (opts.containsKey("scenarios")) {
      scenarios.addAll(Arrays.asList(opts.get("scenarios").split(",")));
    }

    SuiteConfig config = new SuiteConfig(Path.of(opts.get("proxy-jar")).toAbsolutePath(),
        opts.getOrDefault("proxy-kind", "velocity"), proxyJava, versions(opts.get("versions")),
        work, report, ledgerFile, cache, jdks, scenarios, flags.contains("gate"));

    Ledger ledger = ledgerFile != null && Files.isRegularFile(ledgerFile)
        ? Ledger.load(ledgerFile) : Ledger.empty();
    Reporter reporter = new Reporter(report, config.proxyKind(), config.proxyJar().toString(),
        ledger);
    Suite suite = new Suite(config, ledger, reporter);
    for (String version : config.versions()) {
      suite.runVersion(version);
    }
    reporter.write();
    System.out.println("[suite] report: " + report.resolve("summary.md"));
    if (config.gate() && reporter.failsGate()) {
      System.out.println("[suite] gate failed: regressions, unexpected passes or errors");
      System.exit(1);
    }
    System.exit(0);
  }

  private static List<String> versions(String spec) throws IOException {
    List<String> out = new ArrayList<>();
    if (spec.startsWith("@")) {
      for (String line : Files.readAllLines(Path.of(spec.substring(1)), StandardCharsets.UTF_8)) {
        if (!line.isBlank() && !line.startsWith("#")) {
          out.add(line.strip());
        }
      }
    } else {
      for (String version : spec.split(",")) {
        if (!version.isBlank()) {
          out.add(version.strip());
        }
      }
    }
    return out;
  }

  private static void usage(String problem) {
    System.err.println(problem);
    System.err.println("usage: --proxy-jar JAR --versions V1,V2|@file [--proxy-kind velocity]"
        + " [--work DIR] [--report DIR] [--ledger FILE] [--cache DIR] [--jdks A:B]"
        + " [--proxy-java JAVA] [--scenarios A,B] [--gate]");
    System.exit(2);
  }
}
