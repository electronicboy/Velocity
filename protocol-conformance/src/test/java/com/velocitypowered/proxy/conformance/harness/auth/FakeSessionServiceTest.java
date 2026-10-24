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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.velocitypowered.proxy.conformance.harness.wire.Json;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Tests the loopback session service over real HTTP.
 */
@Timeout(30)
class FakeSessionServiceTest {

  private FakeSessionService service;
  private HttpClient http;

  @BeforeEach
  void start() throws IOException {
    service = FakeSessionService.start();
    http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  }

  @AfterEach
  void stop() {
    http.close();
    service.close();
  }

  private HttpResponse<String> hasJoined(String username, String serverId) throws Exception {
    URI uri = URI.create(service.hasJoinedUri() + "?username=" + username + "&serverId="
        + serverId);
    return http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> join(String accessToken, String selectedProfile, String serverId)
      throws Exception {
    JsonObject body = new JsonObject();
    body.addProperty("accessToken", accessToken);
    body.addProperty("selectedProfile", selectedProfile);
    body.addProperty("serverId", serverId);
    return http.send(HttpRequest.newBuilder(service.joinUri())
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(Json.GSON.toJson(body)))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static String undashed(UUID id) {
    return id.toString().replace("-", "");
  }

  @Test
  void hasJoinedIsNoContentBeforeJoin() throws Exception {
    HttpResponse<String> response = hasJoined("Alice", "-1a2b3c");
    assertEquals(204, response.statusCode());
    assertEquals("", response.body());
  }

  @Test
  void hasJoinedReturnsDeterministicProfileAfterJoin() throws Exception {
    service.clientJoin("Alice", "-1a2b3c");
    HttpResponse<String> response = hasJoined("Alice", "-1a2b3c");
    assertEquals(200, response.statusCode());

    FakeSessionService.Profile expected = FakeSessionService.profileFor("Alice");
    JsonObject profile = Json.GSON.fromJson(response.body(), JsonObject.class);
    assertEquals(undashed(expected.id()), profile.get("id").getAsString());
    assertEquals(32, profile.get("id").getAsString().length());
    assertEquals("Alice", profile.get("name").getAsString());
    JsonArray properties = profile.getAsJsonArray("properties");
    assertEquals(1, properties.size());
    JsonObject textures = properties.get(0).getAsJsonObject();
    assertEquals("textures", textures.get("name").getAsString());
    assertEquals(expected.textures(), textures.get("value").getAsString());
    assertEquals(expected.signature(), textures.get("signature").getAsString());
  }

  @Test
  void profilesAreDeterministicAndDistinct() {
    FakeSessionService.Profile alice = FakeSessionService.profileFor("Alice");
    assertEquals(alice, FakeSessionService.profileFor("Alice"));
    assertNotEquals(alice.id(), FakeSessionService.profileFor("Bob").id());
    assertEquals("Alice", alice.name());
  }

  @Test
  void hasJoinedIsNoContentForAnotherServerId() throws Exception {
    service.clientJoin("Alice", "-1a2b3c");
    assertEquals(204, hasJoined("Alice", "4d5e6f").statusCode());
    assertEquals(204, hasJoined("Bob", "-1a2b3c").statusCode());
  }

  @Test
  void joinWithAnotherPlayersProfileIsForbidden() throws Exception {
    String bob = undashed(FakeSessionService.profileFor("Bob").id());
    HttpResponse<String> response = join("conformance-token-Alice", bob, "-1a2b3c");
    assertEquals(403, response.statusCode());
    assertTrue(response.body().contains("ForbiddenOperationException"), response.body());
    assertEquals(204, hasJoined("Alice", "-1a2b3c").statusCode());
    assertEquals(204, hasJoined("Bob", "-1a2b3c").statusCode());
  }

  @Test
  void joinWithForeignTokenIsForbidden() throws Exception {
    String alice = undashed(FakeSessionService.profileFor("Alice").id());
    assertEquals(403, join("someone-elses-token", alice, "-1a2b3c").statusCode());
  }

  @Test
  void hasJoinedQueriesAreRecordedVerbatim() throws Exception {
    assertEquals(List.of(), service.hasJoinedQueries());
    hasJoined("Alice", "-1a2b3c");
    service.clientJoin("Alice", "-1a2b3c");
    hasJoined("Alice", "-1a2b3c");
    assertEquals(List.of("username=Alice&serverId=-1a2b3c", "username=Alice&serverId=-1a2b3c"),
        service.hasJoinedQueries());
  }
}
