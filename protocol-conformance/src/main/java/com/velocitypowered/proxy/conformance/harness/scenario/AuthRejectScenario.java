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
import com.velocitypowered.proxy.conformance.harness.client.ClientOptions;
import com.velocitypowered.proxy.conformance.harness.client.ClientSession;
import java.io.IOException;
import java.time.Duration;

/**
 * A client that completes the encryption handshake but never tells the session service it is
 * joining. An online-mode proxy must refuse it during login, and must never open a backend
 * connection on its behalf.
 */
public final class AuthRejectScenario implements Scenario {

  @Override
  public String id() {
    return "auth/not-joined";
  }

  @Override
  public boolean onlineMode() {
    return true;
  }

  @Override
  public ScenarioOutput run(ScenarioContext ctx) throws HarnessException, InterruptedException {
    ScenarioOutput out = new ScenarioOutput();
    int sessions = ctx.tap().sessions().size();
    try (ClientSession client = ClientSession.login(ctx.proxyAddress(), ctx.version().protocol(),
        ctx.table(), new ClientOptions("ConfReject", null, true), Duration.ofSeconds(30))) {
      out.fail("CHECK login.unauthenticated", "an unauthenticated client got past login (now in "
          + client.phase() + ")");
    } catch (ClientSession.LoginRejectedException e) {
      out.tolerate("refused during login: " + e.getMessage());
    } catch (IOException e) {
      throw new HarnessException("cannot connect to the proxy", e);
    }
    Thread.sleep(500);
    if (ctx.tap().sessions().size() != sessions) {
      out.fail("CHECK login.unauthenticated", "the proxy opened a backend connection for an "
          + "unauthenticated client");
    }
    return out;
  }
}
