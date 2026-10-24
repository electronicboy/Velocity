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

import com.velocitypowered.proxy.conformance.harness.auth.FakeSessionService;
import com.velocitypowered.proxy.conformance.harness.client.ClientOptions;
import com.velocitypowered.proxy.conformance.harness.client.ClientSession;
import com.velocitypowered.proxy.conformance.harness.client.LoginResult;
import com.velocitypowered.proxy.conformance.harness.wire.Protocols;
import java.util.List;

/**
 * Logs in through an online-mode proxy authenticated by the local session service: the full
 * encryption handshake, the {@code join}/{@code hasJoined} exchange, and the authenticated
 * profile handed to the client. The session is then checked for transparency through the
 * encrypted client connection.
 */
public final class OnlineLoginScenario extends SessionScenario {

  private static final String PLAYER = "ConfOnline";

  @Override
  public String id() {
    return "auth/online";
  }

  @Override
  public boolean onlineMode() {
    return true;
  }

  @Override
  protected ClientOptions options(ScenarioContext ctx) {
    return new ClientOptions(PLAYER, ctx.auth(), true);
  }

  @Override
  protected void checkLogin(ScenarioContext ctx, ClientSession client, ScenarioOutput out) {
    LoginResult login = client.loginResult();
    FakeSessionService.Profile profile = FakeSessionService.profileFor(PLAYER);
    if (!login.encrypted()) {
      out.fail("CHECK login.encryption", "an online-mode proxy never requested encryption");
    }
    if (!profile.id().equals(login.uuid())) {
      out.fail("CHECK login.profile", "login success carried " + login.uuid()
          + ", the session service vouched for " + profile.id());
    }
    if (!PLAYER.equals(login.name())) {
      out.fail("CHECK login.profile", "login success carried name " + login.name());
    }
    if (ctx.version().atLeast(Protocols.V1_19)
        && !login.properties().contains("textures=" + profile.textures())) {
      out.fail("CHECK login.properties", "authenticated textures property not passed on: "
          + login.properties());
    }
    List<String> queries = ctx.auth().hasJoinedQueries();
    if (queries.stream().noneMatch(q -> q.contains("username=" + PLAYER))) {
      out.fail("CHECK login.hasJoined", "the proxy never asked the session service about "
          + PLAYER + ": " + queries);
    }
  }

  @Override
  protected void script(ScenarioContext ctx, ClientSession client, ScenarioOutput out)
      throws InterruptedException {
    Thread.sleep(1500);
  }
}
