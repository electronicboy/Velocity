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

import com.velocitypowered.proxy.conformance.harness.client.ClientOptions;
import com.velocitypowered.proxy.conformance.harness.client.ClientSession;
import com.velocitypowered.proxy.conformance.harness.vanilla.VanillaVersion;
import com.velocitypowered.proxy.conformance.harness.wire.Protocols;

/**
 * Joins claiming to know no data packs, so the backend sends every registry in full rather than
 * by reference. This is the largest configuration-phase payload vanilla produces and the path a
 * client of a different minor version takes.
 */
public final class FullRegistriesScenario extends SessionScenario {

  @Override
  public String id() {
    return "config/full-registries";
  }

  @Override
  public boolean onlineMode() {
    return false;
  }

  @Override
  public boolean appliesTo(VanillaVersion version) {
    // Known packs, and therefore the choice, arrived in 1.20.5.
    return version.atLeast(Protocols.V1_20_5);
  }

  @Override
  protected ClientOptions options(ScenarioContext ctx) {
    return new ClientOptions("ConfRegistry", null, false);
  }

  @Override
  protected void script(ScenarioContext ctx, ClientSession client, ScenarioOutput out)
      throws InterruptedException {
    Thread.sleep(1500);
  }
}
