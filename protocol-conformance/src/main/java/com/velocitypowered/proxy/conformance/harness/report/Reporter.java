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

package com.velocitypowered.proxy.conformance.harness.report;

import com.velocitypowered.proxy.conformance.harness.report.CellResult.Outcome;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes the run's results: a Markdown summary (also suitable for a CI job summary), JUnit XML
 * for CI test reporting, and a coverage report. Rewritten after every version, so an aborted run
 * still leaves a useful report behind.
 */
public final class Reporter {

  private final Path dir;
  private final String proxyKind;
  private final String proxyJar;
  private final Ledger ledger;
  private final List<CellResult> results = new ArrayList<>();
  private final Map<String, Coverage> coverage = new LinkedHashMap<>();

  /**
   * Creates a reporter.
   *
   * @param dir the report directory
   * @param proxyKind the proxy kind
   * @param proxyJar the jar under test, for the header
   * @param ledger the expectations
   */
  public Reporter(Path dir, String proxyKind, String proxyJar, Ledger ledger) {
    this.dir = dir;
    this.proxyKind = proxyKind;
    this.proxyJar = proxyJar;
    this.ledger = ledger;
  }

  public synchronized void add(CellResult result) {
    results.add(result);
  }

  public synchronized void add(Coverage versionCoverage) {
    coverage.put(versionCoverage.version(), versionCoverage);
  }

  public synchronized List<CellResult> results() {
    return List.copyOf(results);
  }

  /**
   * Returns whether any cell fails the gate.
   *
   * @return whether the run should fail
   */
  public synchronized boolean failsGate() {
    return results.stream().anyMatch(r -> r.outcome().failsGate());
  }

  /**
   * Writes every report file.
   *
   * @throws IOException on a write error
   */
  public synchronized void write() throws IOException {
    Files.createDirectories(dir);
    Files.writeString(dir.resolve("summary.md"), summary(), StandardCharsets.UTF_8);
    Files.writeString(dir.resolve("coverage.md"), coverage(), StandardCharsets.UTF_8);
    Files.writeString(dir.resolve("junit.xml"), junit(), StandardCharsets.UTF_8);
  }

  private String summary() {
    StringBuilder sb = new StringBuilder();
    sb.append("# Vanilla conformance: ").append(proxyKind).append("\n\n");
    sb.append("Proxy under test: `").append(proxyJar).append("`\n\n");

    Map<Outcome, Integer> counts = new EnumMap<>(Outcome.class);
    for (CellResult r : results) {
      counts.merge(r.outcome(), 1, Integer::sum);
    }
    sb.append("| outcome | cells |\n|---|---|\n");
    counts.forEach((k, v) -> sb.append("| ").append(k).append(" | ").append(v).append(" |\n"));
    sb.append('\n');

    sb.append("| version | scenario | outcome | intact | tolerated | failures |\n");
    sb.append("|---|---|---|---|---|---|\n");
    for (CellResult r : results) {
      sb.append("| ").append(r.version()).append(" | ").append(r.scenario()).append(" | ")
          .append(icon(r.outcome())).append(' ').append(r.outcome()).append(" | ")
          .append(r.matched()).append(" | ").append(r.tolerated().size()).append(" | ")
          .append(r.unexpected().size() + r.expected().size()).append(" |\n");
    }
    sb.append('\n');

    for (CellResult r : results) {
      if (r.outcome() == Outcome.PASS) {
        continue;
      }
      sb.append("## ").append(r.version()).append(" / ").append(r.scenario()).append(" — ")
          .append(r.outcome()).append("\n\n");
      if (r.error() != null) {
        sb.append("```\n").append(r.error()).append("\n```\n\n");
      }
      appendList(sb, "Unexpected failures", r.unexpected());
      appendList(sb, "Known failures", r.expected());
      if (!r.stale().isEmpty()) {
        sb.append("**Expectations that no longer fail** (move them out of the ledger):\n\n");
        for (Ledger.Expectation e : r.stale()) {
          sb.append("- `").append(e.signature()).append("` — ").append(e.reason()).append('\n');
        }
        sb.append('\n');
      }
    }

    sb.append("## Coverage\n\n| version | phase | direction | covered | unreachable | missing |\n");
    sb.append("|---|---|---|---|---|---|\n");
    for (Coverage c : coverage.values()) {
      for (Coverage.Row row : c.rows(ledger)) {
        sb.append("| ").append(c.version()).append(" | ").append(row.phase()).append(" | ")
            .append(row.dir().id()).append(" | ").append(row.covered().size()).append('/')
            .append(row.total()).append(" | ").append(row.unreachable().size()).append(" | ")
            .append(row.missing().size()).append(" |\n");
      }
    }
    sb.append("\nPer-packet detail is in `coverage.md`.\n");
    return sb.toString();
  }

  private static void appendList(StringBuilder sb, String title,
      List<CellResult.Failure> failures) {
    if (failures.isEmpty()) {
      return;
    }
    sb.append("**").append(title).append("**\n\n");
    int shown = 0;
    for (CellResult.Failure f : failures) {
      if (shown++ == 50) {
        sb.append("- … ").append(failures.size() - 50).append(" more (see traces)\n");
        break;
      }
      sb.append("- `").append(f.signature()).append("` ").append(f.detail()).append('\n');
    }
    sb.append('\n');
  }

  private static String icon(Outcome outcome) {
    return switch (outcome) {
      case PASS -> "✅";
      case KNOWN_FAIL -> "🟡";
      case REGRESSION -> "❌";
      case UNEXPECTED_PASS -> "🎉";
      case ERROR -> "⚠️";
      case SKIPPED -> "⏭️";
    };
  }

  private String coverage() {
    StringBuilder sb = new StringBuilder("# Packet coverage\n\n");
    for (Coverage c : coverage.values()) {
      sb.append("## ").append(c.version()).append("\n\n");
      for (Coverage.Row row : c.rows(ledger)) {
        sb.append("### ").append(row.phase()).append(' ').append(row.dir().id()).append(" — ")
            .append(row.covered().size()).append('/').append(row.total()).append("\n\n");
        if (!row.missing().isEmpty()) {
          sb.append("Not exercised: ").append(String.join(", ", row.missing())).append("\n\n");
        }
        if (!row.unreachable().isEmpty()) {
          sb.append("Unreachable:\n\n");
          row.unreachable().forEach((k, v) -> sb.append("- ").append(k).append(" — ").append(v)
              .append('\n'));
          sb.append('\n');
        }
      }
    }
    return sb.toString();
  }

  private String junit() {
    StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    Map<String, List<CellResult>> byVersion = new LinkedHashMap<>();
    for (CellResult r : results) {
      byVersion.computeIfAbsent(r.version(), k -> new ArrayList<>()).add(r);
    }
    sb.append("<testsuites name=\"vanilla-conformance-").append(xml(proxyKind)).append("\">\n");
    for (Map.Entry<String, List<CellResult>> entry : byVersion.entrySet()) {
      List<CellResult> cells = entry.getValue();
      long failures = cells.stream().filter(c -> c.outcome() == Outcome.REGRESSION
          || c.outcome() == Outcome.UNEXPECTED_PASS).count();
      long errors = cells.stream().filter(c -> c.outcome() == Outcome.ERROR).count();
      long skipped = cells.stream().filter(c -> c.outcome() == Outcome.SKIPPED).count();
      sb.append("  <testsuite name=\"").append(xml(entry.getKey())).append("\" tests=\"")
          .append(cells.size()).append("\" failures=\"").append(failures).append("\" errors=\"")
          .append(errors).append("\" skipped=\"").append(skipped).append("\">\n");
      for (CellResult c : cells) {
        sb.append("    <testcase classname=\"").append(xml(entry.getKey())).append("\" name=\"")
            .append(xml(c.scenario())).append("\" time=\"")
            .append(c.took().toMillis() / 1000.0).append("\">");
        switch (c.outcome()) {
          case REGRESSION -> sb.append("<failure message=\"").append(c.unexpected().size())
              .append(" unexpected failures\">").append(xml(join(c.unexpected())))
              .append("</failure>");
          case UNEXPECTED_PASS -> sb.append("<failure message=\"known failures no longer fail\">")
              .append(xml(c.stale().toString())).append("</failure>");
          case ERROR -> sb.append("<error message=\"infrastructure error\">")
              .append(xml(String.valueOf(c.error()))).append("</error>");
          case SKIPPED -> sb.append("<skipped message=\"").append(xml(String.valueOf(c.error())))
              .append("\"/>");
          default -> {
          }
        }
        sb.append("</testcase>\n");
      }
      sb.append("  </testsuite>\n");
    }
    sb.append("</testsuites>\n");
    return sb.toString();
  }

  private static String join(List<CellResult.Failure> failures) {
    StringBuilder sb = new StringBuilder();
    for (CellResult.Failure f : failures) {
      sb.append(f).append('\n');
    }
    return sb.toString();
  }

  private static String xml(String text) {
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;");
  }
}
