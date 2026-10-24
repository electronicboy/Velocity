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

package com.velocitypowered.proxy.conformance.harness.scenario;

import com.velocitypowered.proxy.conformance.harness.report.CellResult.Failure;
import java.util.ArrayList;
import java.util.List;

/**
 * What a scenario observed: failed checks, tolerated differences, and intact packets.
 */
public final class ScenarioOutput {

  private final List<Failure> failures = new ArrayList<>();
  private final List<String> tolerated = new ArrayList<>();
  private int matched;

  /**
   * Records a failed check.
   *
   * @param signature the ledger-matchable part
   * @param detail the rest
   */
  public void fail(String signature, String detail) {
    failures.add(new Failure(signature, detail));
  }

  public void tolerate(String note) {
    tolerated.add(note);
  }

  public void addMatched(int count) {
    matched += count;
  }

  public List<Failure> failures() {
    return failures;
  }

  public List<String> tolerated() {
    return tolerated;
  }

  public int matched() {
    return matched;
  }
}
