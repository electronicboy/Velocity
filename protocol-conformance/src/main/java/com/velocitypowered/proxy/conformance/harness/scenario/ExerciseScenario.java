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
import com.velocitypowered.proxy.conformance.harness.client.Serverbound;
import com.velocitypowered.proxy.conformance.harness.wire.Protocols;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * The main transparency scenario: join through an offline-mode proxy, provoke as many packet
 * types as the vanilla server will emit, send a spread of serverbound packets, and check that
 * every packet crossed the proxy intact.
 */
public final class ExerciseScenario extends SessionScenario {

  private static final String PLAYER = "ConfPlay";

  @Override
  public String id() {
    return "play/exercise";
  }

  @Override
  public boolean onlineMode() {
    return false;
  }

  @Override
  protected ClientOptions options(ScenarioContext ctx) {
    return new ClientOptions(PLAYER, null, true);
  }

  @Override
  protected void script(ScenarioContext ctx, ClientSession client, ScenarioOutput out)
      throws HarnessException, InterruptedException, IOException {
    // Let the join burst (chunks, inventory, recipes, advancements) finish first.
    Thread.sleep(2000);

    for (String command : RconScript.commands(ctx.version(), PLAYER)) {
      if (!client.isConnected()) {
        return;
      }
      String reply = ctx.server().command(command);
      if (reply.toLowerCase(java.util.Locale.ROOT).matches(
          "(?s).*(unknown|incorrect|invalid|error|expected|no entity|not found).*")) {
        System.out.println("[rcon] " + ctx.version().id() + " rejected '" + command + "': "
            + reply.strip());
      }
      Thread.sleep(100);
    }
    // Respawn round trip.
    Thread.sleep(1500);
    if (!client.isConnected()) {
      return;
    }

    Serverbound packets = client.packets();
    final int protocol = ctx.version().protocol();
    client.send("minecraft:chat", packets.chat("conformance chat"));
    if (packets.hasChatCommandPacket()) {
      client.send("minecraft:chat_command", packets.chatCommand("list"));
    } else {
      client.send("minecraft:chat", packets.chat("/list"));
    }
    client.send("minecraft:command_suggestion", packets.commandSuggestion(7, "/gamemode "));
    client.send("minecraft:client_information", packets.clientInformation());
    if (Protocols.atLeast(protocol, Protocols.V1_8)) {
      client.send("minecraft:swing", packets.swing());
    }
    client.send("minecraft:set_carried_item", packets.setCarriedItem(1));
    client.send("minecraft:set_carried_item", packets.setCarriedItem(0));
    if (Protocols.atLeast(protocol, Protocols.V1_9)) {
      client.send("minecraft:use_item", packets.useItem(client.nextSequence()));
    }
    client.send("minecraft:custom_payload", packets.customPayload(
        Protocols.atLeast(protocol, Protocols.V1_13) ? "conformance:test" : "CONF|test",
        "payload".getBytes(StandardCharsets.UTF_8)));
    if (client.has("minecraft:client_tick_end")) {
      for (int i = 0; i < 3; i++) {
        client.send("minecraft:client_tick_end", new byte[0]);
      }
    }
    // Give replies (chat echo, suggestions, list output) time to come back.
    Thread.sleep(1000);
  }
}
