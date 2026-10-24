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

package com.velocitypowered.proxy.conformance.harness.vanilla;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.velocitypowered.proxy.conformance.harness.wire.Json;
import com.velocitypowered.proxy.conformance.harness.wire.PacketTable;
import com.velocitypowered.proxy.conformance.harness.wire.Protocols;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Resolves, downloads and caches everything Mojang (and minecraft-data) publish about a version:
 * the server jar, the data generator's reports, and the packet table.
 *
 * <p>Everything lands in the cache directory, keyed by the published sha1 where there is one.
 * Nothing here is ever committed.</p>
 */
public final class VanillaDistribution {

  private static final URI MANIFEST =
      URI.create("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json");
  private static final String MINECRAFT_DATA =
      "https://raw.githubusercontent.com/PrismarineJS/minecraft-data/master/data/";
  private static final int ATTEMPTS = 3;

  private final Path cache;
  private final JavaRuntimes java;
  private final HttpClient http = HttpClient.newBuilder()
      .followRedirects(HttpClient.Redirect.NORMAL)
      .connectTimeout(Duration.ofSeconds(30))
      .build();
  private final Map<String, JsonObject> jsonCache = new ConcurrentHashMap<>();

  /**
   * Creates a distribution backed by {@code cache}.
   *
   * @param cache where to keep jars, reports and metadata
   * @param java runtimes for running the data generator
   */
  public VanillaDistribution(Path cache, JavaRuntimes java) {
    this.cache = cache;
    this.java = java;
  }

  /**
   * Resolves a version id against Mojang's manifest.
   *
   * @param id the version id
   * @return the version, or empty if Mojang does not publish it (yet)
   * @throws IOException on a network error
   */
  public Optional<VanillaVersion> resolve(String id) throws IOException {
    JsonObject manifest = json(MANIFEST.toString(), false);
    for (JsonElement element : manifest.getAsJsonArray("versions")) {
      JsonObject entry = element.getAsJsonObject();
      if (!entry.get("id").getAsString().equals(id)) {
        continue;
      }
      JsonObject details = json(entry.get("url").getAsString(), true);
      JsonObject server = details.getAsJsonObject("downloads").getAsJsonObject("server");
      if (server == null) {
        return Optional.empty();
      }
      int javaMajor = details.has("javaVersion")
          ? details.getAsJsonObject("javaVersion").get("majorVersion").getAsInt() : 8;
      URI url = URI.create(server.get("url").getAsString());
      String sha1 = server.get("sha1").getAsString();
      Path jar = download(url, sha1, cache.resolve("jars").resolve(id + "-" + sha1 + ".jar"));
      int protocol = protocolOf(id, jar);
      return Optional.of(new VanillaVersion(id, protocol, javaMajor, url, sha1));
    }
    return Optional.empty();
  }

  /**
   * Returns the cached server jar for {@code version}, downloading it if needed.
   *
   * @param version the version
   * @return the jar
   * @throws IOException on a network error
   */
  public Path serverJar(VanillaVersion version) throws IOException {
    return download(version.serverUrl(), version.sha1(),
        cache.resolve("jars").resolve(version.id() + "-" + version.sha1() + ".jar"));
  }

  /**
   * Returns the data generator's report directory for {@code version}, running it if needed.
   * Only versions with the server bundler (1.18+) are supported; the reports the harness uses
   * ({@code packets.json}, numeric argument type ids) do not exist before that anyway.
   *
   * @param version the version
   * @return the {@code reports} directory, if it could be produced
   * @throws IOException on a failure to run the generator
   */
  public Optional<Path> reports(VanillaVersion version) throws IOException {
    if (!version.atLeast(Protocols.V1_19)) {
      return Optional.empty();
    }
    Path out = cache.resolve("reports").resolve(version.id() + "-" + version.sha1());
    Path reports = out.resolve("reports");
    if (Files.isDirectory(reports)) {
      return Optional.of(reports);
    }

    Path sandbox = Files.createTempDirectory("conformance-datagen");
    Path staging = sandbox.resolve("generated");
    try {
      Process process = new ProcessBuilder(
          java.forMajor(version.javaMajor()).toString(),
          "-DbundlerMainClass=net.minecraft.data.Main",
          "-jar", serverJar(version).toAbsolutePath().toString(),
          "--reports", "--output", staging.toAbsolutePath().toString())
          .directory(sandbox.toFile())
          .redirectErrorStream(true)
          .redirectOutput(sandbox.resolve("datagen.log").toFile())
          .start();
      if (!process.waitFor(10, TimeUnit.MINUTES) || process.exitValue() != 0) {
        process.destroyForcibly();
        throw new IOException("data generator failed for " + version.id());
      }
      Files.createDirectories(reports);
      for (String name : new String[] {"packets.json", "registries.json", "commands.json"}) {
        Path file = staging.resolve("reports").resolve(name);
        if (Files.isRegularFile(file)) {
          Files.copy(file, reports.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        }
      }
      return Optional.of(reports);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException(e);
    } finally {
      deleteRecursively(sandbox);
    }
  }

  /**
   * Builds the packet table for {@code version}: Mojang's own {@code packets.json} when the data
   * generator emits one, minecraft-data otherwise.
   *
   * @param version the version
   * @return the table
   * @throws IOException on a network or generator failure
   */
  public PacketTable packetTable(VanillaVersion version) throws IOException {
    Optional<Path> reports = reports(version);
    if (reports.isPresent() && Files.isRegularFile(reports.get().resolve("packets.json"))) {
      return PacketTable.fromPacketsJson(Json.read(reports.get().resolve("packets.json")));
    }

    String key = minecraftDataKey(version);
    JsonObject paths = json(MINECRAFT_DATA + "dataPaths.json", true).getAsJsonObject("pc");
    String protocolPath = paths.getAsJsonObject(key).get("protocol").getAsString();
    JsonObject protocol = json(MINECRAFT_DATA + protocolPath + "/protocol.json", true);
    return PacketTable.fromMinecraftData(protocol, key);
  }

  /**
   * Returns the {@code command_argument_type} registry (name by wire id) for 1.19+.
   *
   * @param version the version
   * @return names indexed by id, or empty when ids are not numeric for this version
   * @throws IOException on a generator failure
   */
  public Optional<String[]> argumentTypes(VanillaVersion version) throws IOException {
    Optional<Path> reports = reports(version);
    if (reports.isEmpty() || !Files.isRegularFile(reports.get().resolve("registries.json"))) {
      return Optional.empty();
    }
    JsonObject entries = Json.read(reports.get().resolve("registries.json"))
        .getAsJsonObject("minecraft:command_argument_type").getAsJsonObject("entries");
    String[] byId = new String[entries.size()];
    for (Map.Entry<String, JsonElement> entry : entries.entrySet()) {
      byId[entry.getValue().getAsJsonObject().get("protocol_id").getAsInt()] = entry.getKey();
    }
    return Optional.of(byId);
  }

  private String minecraftDataKey(VanillaVersion version) throws IOException {
    JsonObject paths = json(MINECRAFT_DATA + "dataPaths.json", true).getAsJsonObject("pc");
    if (paths.has(version.id()) && paths.getAsJsonObject(version.id()).has("protocol")) {
      return version.id();
    }
    // No exact entry (1.8.9, 1.14.2, ...): take the newest release entry whose protocol is not
    // newer than ours. Packet ids move between protocol numbers, not between patch releases
    // sharing one.
    Map<String, Integer> protocols = minecraftDataProtocols();
    String best = null;
    int bestProtocol = -1;
    for (String key : paths.keySet()) {
      Integer protocol = protocols.get(key);
      if (protocol == null || !paths.getAsJsonObject(key).has("protocol")
          || !key.matches("\\d+\\.\\d+(\\.\\d+)?")) {
        continue;
      }
      if (protocol <= version.protocol() && protocol > bestProtocol) {
        best = key;
        bestProtocol = protocol;
      }
    }
    if (best == null) {
      throw new IOException("minecraft-data has no protocol for " + version);
    }
    if (bestProtocol != version.protocol()) {
      System.err.println("[packets] " + version.id() + ": using minecraft-data " + best
          + " (protocol " + bestProtocol + ") as the nearest table");
    }
    return best;
  }

  private Map<String, Integer> minecraftDataProtocols() throws IOException {
    Map<String, Integer> out = new ConcurrentHashMap<>();
    String url = MINECRAFT_DATA + "pc/common/protocolVersions.json";
    Path file = cacheFile(url);
    if (!Files.isRegularFile(file)) {
      fetch(url, file);
    }
    try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      for (JsonElement element : Json.GSON.fromJson(reader, JsonArray.class)) {
        JsonObject entry = element.getAsJsonObject();
        if (entry.has("usesNetty") && !entry.get("usesNetty").getAsBoolean()) {
          continue;
        }
        out.putIfAbsent(entry.get("minecraftVersion").getAsString(),
            entry.get("version").getAsInt());
      }
    }
    return out;
  }

  private int protocolOf(String id, Path jar) throws IOException {
    try (ZipFile zip = new ZipFile(jar.toFile())) {
      ZipEntry entry = zip.getEntry("version.json");
      if (entry != null) {
        try (Reader reader = new InputStreamReader(zip.getInputStream(entry),
            StandardCharsets.UTF_8)) {
          JsonObject version = Json.GSON.fromJson(reader, JsonObject.class);
          if (version.has("protocol_version")) {
            return version.get("protocol_version").getAsInt();
          }
        }
      }
    }
    Integer protocol = minecraftDataProtocols().get(id);
    if (protocol == null) {
      throw new IOException("cannot determine the protocol number of " + id);
    }
    return protocol;
  }

  private JsonObject json(String url, boolean cacheable) throws IOException {
    JsonObject cached = jsonCache.get(url);
    if (cached != null) {
      return cached;
    }
    Path file = cacheFile(url);
    if (!cacheable || !Files.isRegularFile(file)) {
      fetch(url, file);
    }
    JsonObject parsed = Json.read(file);
    jsonCache.put(url, parsed);
    return parsed;
  }

  private Path cacheFile(String url) {
    String name = url.replaceFirst("^https?://", "").replaceAll("[^A-Za-z0-9._-]", "_");
    return cache.resolve("meta").resolve(name);
  }

  private void fetch(String url, Path file) throws IOException {
    Files.createDirectories(file.getParent());
    Path partial = file.resolveSibling(file.getFileName() + ".part");
    IOException last = null;
    for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
      try {
        HttpResponse<Path> response = http.send(HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMinutes(5)).build(), HttpResponse.BodyHandlers.ofFile(partial));
        if (response.statusCode() == 200) {
          Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING);
          return;
        }
        last = new IOException("HTTP " + response.statusCode() + " for " + url);
      } catch (IOException e) {
        last = e;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException(e);
      }
      sleep(attempt);
    }
    throw last;
  }

  private Path download(URI url, String sha1, Path target) throws IOException {
    if (Files.isRegularFile(target) && sha1.equals(sha1Of(target))) {
      return target;
    }
    System.out.println("[vanilla] downloading " + target.getFileName());
    for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
      fetch(url.toString(), target);
      if (sha1.equals(sha1Of(target))) {
        return target;
      }
      Files.deleteIfExists(target);
      sleep(attempt);
    }
    throw new IOException("sha1 mismatch for " + url);
  }

  private static void sleep(int attempt) {
    try {
      Thread.sleep(2000L * attempt);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static String sha1Of(Path path) throws IOException {
    try (InputStream in = Files.newInputStream(path)) {
      MessageDigest digest = MessageDigest.getInstance("SHA-1");
      byte[] buffer = new byte[65536];
      int n;
      while ((n = in.read(buffer)) != -1) {
        digest.update(buffer, 0, n);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static void deleteRecursively(Path root) throws IOException {
    if (!Files.exists(root)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(root)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    }
  }
}
