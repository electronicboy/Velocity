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

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.velocitypowered.proxy.conformance.harness.HarnessException;
import com.velocitypowered.proxy.conformance.harness.client.Serverbound;
import com.velocitypowered.proxy.conformance.harness.wire.Buf;
import com.velocitypowered.proxy.conformance.harness.wire.Direction;
import com.velocitypowered.proxy.conformance.harness.wire.FrameCodec;
import com.velocitypowered.proxy.conformance.harness.wire.Json;
import com.velocitypowered.proxy.conformance.harness.wire.Phase;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Optional;

/**
 * A server-list ping. The proxy answers this itself, so it is checked against the vanilla format
 * rather than against the backend: a well-formed JSON response that advertises the client's own
 * protocol, and a pong echoing the ping.
 */
public final class StatusScenario implements Scenario {

  @Override
  public String id() {
    return "status/ping";
  }

  @Override
  public boolean onlineMode() {
    return false;
  }

  @Override
  public ScenarioOutput run(ScenarioContext ctx) throws HarnessException {
    ScenarioOutput out = new ScenarioOutput();
    int protocol = ctx.version().protocol();
    Serverbound packets = new Serverbound(protocol);
    FrameCodec codec = new FrameCodec();
    try (Socket socket = new Socket()) {
      socket.connect(ctx.proxyAddress(), 5000);
      socket.setSoTimeout(10000);
      OutputStream output = socket.getOutputStream();
      InputStream input = new BufferedInputStream(socket.getInputStream());

      output.write(codec.encode(Buf.packet(0, packets.intention(
          ctx.proxyAddress().getHostString(), ctx.proxyAddress().getPort(), 1))));
      output.write(codec.encode(Buf.packet(0, new byte[0])));
      output.flush();

      Optional<byte[]> response = codec.decode(input);
      if (response.isEmpty()) {
        out.fail("CHECK status.response", "connection closed before a status response");
        return out;
      }
      Buf buf = new Buf(response.get());
      int id = buf.readVarInt();
      if (!ctx.table().is(Phase.STATUS, Direction.CLIENTBOUND, id, "minecraft:status_response")) {
        out.fail("CHECK status.response", "expected status_response, got packet " + id);
        return out;
      }
      String json = buf.readString();
      try {
        JsonObject status = Json.GSON.fromJson(json, JsonObject.class);
        JsonObject version = status.getAsJsonObject("version");
        if (version == null || !version.has("protocol") || !version.has("name")) {
          out.fail("CHECK status.version", "missing version object: " + json);
        } else if (version.get("protocol").getAsInt() != protocol) {
          out.fail("CHECK status.version", "advertises protocol " + version.get("protocol")
              + " to a supported " + protocol + " client");
        }
        if (!status.has("description")) {
          out.fail("CHECK status.description", "missing description: " + json);
        }
        if (!status.has("players")) {
          out.fail("CHECK status.players", "missing players: " + json);
        }
      } catch (JsonParseException | IllegalStateException | ClassCastException e) {
        out.fail("CHECK status.json", "not valid status JSON: " + e.getMessage());
      }

      long nonce = 0x0123456789ABCDEFL;
      output.write(codec.encode(Buf.packet(1, new Buf().writeLong(nonce).toByteArray())));
      output.flush();
      Optional<byte[]> pong = codec.decode(input);
      if (pong.isEmpty()) {
        out.fail("CHECK status.pong", "connection closed before the pong");
        return out;
      }
      Buf pongBuf = new Buf(pong.get());
      if (pongBuf.readVarInt() != 1 || pongBuf.readLong() != nonce) {
        out.fail("CHECK status.pong", "pong does not echo the ping");
      } else {
        out.addMatched(1);
      }
    } catch (IOException e) {
      out.fail("CHECK status.io", "status exchange failed: " + e.getMessage());
    }
    return out;
  }
}
