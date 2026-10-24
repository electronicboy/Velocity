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

package com.velocitypowered.proxy.conformance.harness.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.velocitypowered.proxy.conformance.harness.wire.Json;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A loopback stand-in for Mojang's session server, implementing the two endpoints a login uses:
 * the client's {@code POST /session/minecraft/join} and the server's
 * {@code GET /session/minecraft/hasJoined}.
 *
 * <p>Profiles are derived deterministically from the player name and carry a {@code textures}
 * property, so the harness can check that the proxy hands the authenticated profile to the
 * client intact.</p>
 */
public final class FakeSessionService implements AutoCloseable {

  private static final String JOIN = "/session/minecraft/join";
  private static final String HAS_JOINED = "/session/minecraft/hasJoined";

  private final HttpServer server;
  private final Map<String, String> joined = new ConcurrentHashMap<>();
  private final List<String> hasJoinedQueries = new CopyOnWriteArrayList<>();

  private FakeSessionService(HttpServer server) {
    this.server = server;
  }

  /**
   * Starts the service on an ephemeral loopback port.
   *
   * @return the running service
   * @throws IOException if the port cannot be bound
   */
  public static FakeSessionService start() throws IOException {
    HttpServer server = HttpServer.create(
        new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    FakeSessionService service = new FakeSessionService(server);
    server.createContext(JOIN, service::handleJoin);
    server.createContext(HAS_JOINED, service::handleHasJoined);
    server.start();
    return service;
  }

  public URI baseUri() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
  }

  public URI hasJoinedUri() {
    return baseUri().resolve(HAS_JOINED);
  }

  public URI joinUri() {
    return baseUri().resolve(JOIN);
  }

  /**
   * Returns the {@code hasJoined} query strings received so far, for assertions about what the
   * proxy asked.
   *
   * @return the raw queries
   */
  public List<String> hasJoinedQueries() {
    return List.copyOf(hasJoinedQueries);
  }

  /**
   * The profile the service vouches for.
   *
   * @param id the profile UUID
   * @param name the player name
   * @param textures the {@code textures} property value
   * @param signature the property's signature
   */
  public record Profile(UUID id, String name, String textures, String signature) {
  }

  /**
   * Returns the deterministic profile for {@code name}.
   *
   * @param name the player name
   * @return the profile
   */
  public static Profile profileFor(String name) {
    UUID id = UUID.nameUUIDFromBytes(("conformance:" + name).getBytes(StandardCharsets.UTF_8));
    JsonObject textures = new JsonObject();
    textures.addProperty("profileId", id.toString().replace("-", ""));
    textures.addProperty("profileName", name);
    textures.add("textures", new JsonObject());
    String value = Base64.getEncoder().encodeToString(
        Json.GSON.toJson(textures).getBytes(StandardCharsets.UTF_8));
    String signature = Base64.getEncoder().encodeToString(
        ("unsigned:" + name).getBytes(StandardCharsets.UTF_8));
    return new Profile(id, name, value, signature);
  }

  /**
   * Performs the client half of authentication over HTTP, exactly as a launcher-backed client
   * would.
   *
   * @param name the player name
   * @param serverId the server id hash
   * @throws IOException if the request fails or is refused
   * @throws InterruptedException if interrupted
   */
  public void clientJoin(String name, String serverId) throws IOException, InterruptedException {
    JsonObject body = new JsonObject();
    body.addProperty("accessToken", "conformance-token-" + name);
    body.addProperty("selectedProfile", profileFor(name).id().toString().replace("-", ""));
    body.addProperty("serverId", serverId);
    try (HttpClient http = HttpClient.newHttpClient()) {
      HttpResponse<String> response = http.send(HttpRequest.newBuilder(joinUri())
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(Json.GSON.toJson(body)))
              .build(),
          HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 204) {
        throw new IOException("join refused: " + response.statusCode() + " " + response.body());
      }
    }
  }

  private void handleJoin(HttpExchange exchange) throws IOException {
    try (exchange) {
      if (!"POST".equals(exchange.getRequestMethod())) {
        exchange.sendResponseHeaders(405, -1);
        return;
      }
      JsonObject body;
      try (InputStream in = exchange.getRequestBody()) {
        body = Json.GSON.fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8),
            JsonObject.class);
      }
      String selected = body.get("selectedProfile").getAsString();
      String serverId = body.get("serverId").getAsString();
      // Find the name whose deterministic id this is; the harness only ever uses a few names, so
      // the client registers it in the token.
      String token = body.get("accessToken").getAsString();
      String name = token.startsWith("conformance-token-")
          ? token.substring("conformance-token-".length()) : null;
      if (name == null || !profileFor(name).id().toString().replace("-", "").equals(selected)) {
        reply(exchange, 403, "{\"error\":\"ForbiddenOperationException\"}");
        return;
      }
      joined.put(name, serverId);
      exchange.sendResponseHeaders(204, -1);
    }
  }

  private void handleHasJoined(HttpExchange exchange) throws IOException {
    try (exchange) {
      String query = exchange.getRequestURI().getRawQuery();
      hasJoinedQueries.add(query == null ? "" : query);
      Map<String, String> params = parseQuery(query);
      String name = params.get("username");
      String serverId = params.get("serverId");
      if (name == null || serverId == null || !serverId.equals(joined.get(name))) {
        exchange.sendResponseHeaders(204, -1);
        return;
      }

      Profile profile = profileFor(name);
      JsonObject response = new JsonObject();
      response.addProperty("id", profile.id().toString().replace("-", ""));
      response.addProperty("name", profile.name());
      final JsonArray properties = new JsonArray();
      JsonObject textures = new JsonObject();
      textures.addProperty("name", "textures");
      textures.addProperty("value", profile.textures());
      textures.addProperty("signature", profile.signature());
      properties.add(textures);
      response.add("properties", properties);
      reply(exchange, 200, Json.GSON.toJson(response));
    }
  }

  private static void reply(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private static Map<String, String> parseQuery(String query) {
    Map<String, String> params = new HashMap<>();
    if (query == null) {
      return params;
    }
    for (String pair : query.split("&")) {
      int eq = pair.indexOf('=');
      if (eq > 0) {
        params.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
            URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
      }
    }
    return params;
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
