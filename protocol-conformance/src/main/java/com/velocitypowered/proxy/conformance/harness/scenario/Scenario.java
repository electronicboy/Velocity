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

import com.velocitypowered.proxy.conformance.harness.HarnessException;
import com.velocitypowered.proxy.conformance.harness.vanilla.VanillaVersion;

/**
 * A scripted session through the proxy, producing failures for the ledger to judge.
 */
public interface Scenario {

  /**
   * Returns the stable id used in the ledger and reports.
   *
   * @return the id
   */
  String id();

  /**
   * Returns whether the proxy must run in online mode for this scenario.
   *
   * @return whether online mode is needed
   */
  boolean onlineMode();

  /**
   * Returns whether the scenario means anything at {@code version}.
   *
   * @param version the version
   * @return whether it applies
   */
  default boolean appliesTo(VanillaVersion version) {
    return true;
  }

  /**
   * Returns whether the scenario compares traffic across the proxy. Ledger expectations about
   * traffic findings only apply to scenarios that do, so they are not reported as fixed in cells
   * that could never have produced them.
   *
   * @return whether traffic is compared
   */
  default boolean comparesTraffic() {
    return false;
  }

  /**
   * Runs the scenario.
   *
   * @param ctx everything the scenario may use
   * @return what it observed
   * @throws HarnessException on an infrastructure failure
   * @throws InterruptedException if interrupted
   */
  ScenarioOutput run(ScenarioContext ctx) throws HarnessException, InterruptedException;
}
