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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The result of one scenario at one version, judged against the ledger.
 *
 * @param version the version id
 * @param scenario the scenario id
 * @param outcome the verdict
 * @param unexpected failures the ledger does not know about
 * @param expected failures the ledger expects
 * @param stale expectations that no longer fail
 * @param tolerated differences explained by proxy rules, with their justification
 * @param matched packets that crossed the proxy intact
 * @param error the infrastructure error, for {@link Outcome#ERROR}
 * @param took wall-clock time
 */
public record CellResult(String version, String scenario, Outcome outcome,
                         List<Failure> unexpected, List<Failure> expected,
                         List<Ledger.Expectation> stale, List<String> tolerated, int matched,
                         String error, Duration took) {

  /**
   * One failed check.
   *
   * @param signature the stable, ledger-matchable part ({@code MISMATCH S->C PLAY name})
   * @param detail everything else
   */
  public record Failure(String signature, String detail) {

    @Override
    public String toString() {
      return signature + ": " + detail;
    }
  }

  /**
   * The verdict for a cell.
   */
  public enum Outcome {
    PASS(false),
    KNOWN_FAIL(false),
    REGRESSION(true),
    UNEXPECTED_PASS(true),
    ERROR(true),
    SKIPPED(false);

    private final boolean failsGate;

    Outcome(boolean failsGate) {
      this.failsGate = failsGate;
    }

    public boolean failsGate() {
      return failsGate;
    }
  }

  /**
   * Judges a cell's failures against the ledger.
   *
   * @param version the version id
   * @param scenario the scenario id
   * @param failures every failed check
   * @param expectations the ledger entries that apply to this cell
   * @param tolerated tolerated differences
   * @param matched intact packets
   * @param took wall-clock time
   * @return the result
   */
  public static CellResult judge(String version, String scenario, List<Failure> failures,
      List<Ledger.Expectation> expectations, List<String> tolerated, int matched, Duration took) {
    return judge(version, scenario, failures, expectations, tolerated, matched, took, false);
  }

  /**
   * Judges a cell's failures against the ledger.
   *
   * @param version the version id
   * @param scenario the scenario id
   * @param failures every failed check
   * @param expectations the ledger entries that apply to this cell
   * @param tolerated tolerated differences
   * @param matched intact packets
   * @param took wall-clock time
   * @param aborted whether the scenario was cut short, in which case expectations it never
   *     reached are not reported as fixed
   * @return the result
   */
  public static CellResult judge(String version, String scenario, List<Failure> failures,
      List<Ledger.Expectation> expectations, List<String> tolerated, int matched, Duration took,
      boolean aborted) {
    List<Failure> unexpected = new ArrayList<>();
    List<Failure> expected = new ArrayList<>();
    List<Ledger.Expectation> used = new ArrayList<>();
    for (Failure failure : failures) {
      Ledger.Expectation hit = expectations.stream()
          .filter(e -> e.matches(failure.signature(), failure.detail())).findFirst().orElse(null);
      if (hit == null) {
        unexpected.add(failure);
      } else {
        expected.add(failure);
        if (!used.contains(hit)) {
          used.add(hit);
        }
      }
    }
    List<Ledger.Expectation> stale = expectations.stream()
        .filter(e -> !aborted && !used.contains(e) && !e.flaky())
        .toList();
    Outcome outcome;
    if (!unexpected.isEmpty()) {
      outcome = Outcome.REGRESSION;
    } else if (!stale.isEmpty()) {
      outcome = Outcome.UNEXPECTED_PASS;
    } else if (!expected.isEmpty()) {
      outcome = Outcome.KNOWN_FAIL;
    } else {
      outcome = Outcome.PASS;
    }
    return new CellResult(version, scenario, outcome, unexpected, expected, stale, tolerated,
        matched, null, took);
  }

  /**
   * Builds an infrastructure-error result.
   *
   * @param version the version id
   * @param scenario the scenario id
   * @param error what went wrong
   * @param took wall-clock time
   * @return the result
   */
  public static CellResult error(String version, String scenario, String error, Duration took) {
    return new CellResult(version, scenario, Outcome.ERROR, List.of(), List.of(), List.of(),
        List.of(), 0, error, took);
  }

  /**
   * Builds a skipped result.
   *
   * @param version the version id
   * @param scenario the scenario id
   * @param why why it was skipped
   * @return the result
   */
  public static CellResult skipped(String version, String scenario, String why) {
    return new CellResult(version, scenario, Outcome.SKIPPED, List.of(), List.of(), List.of(),
        List.of(), 0, why, Duration.ZERO);
  }
}
