/*
 * Copyright (C) 2018-2025 Velocity Contributors
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

package com.velocitypowered.proxy.conformance;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.velocitypowered.api.network.ProtocolVersion;
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
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Extracts the registries Velocity validates against by running Mojang's data generator over every
 * vanilla server jar the proxy supports from 1.19 onwards.
 *
 * <p>Versions are taken from {@link ProtocolVersion#SUPPORTED_VERSIONS}, so a snapshot is only ever
 * pulled once a constant has been added for it — that is, while a version bump is in progress.
 * Nothing here goes looking for versions Velocity does not claim to support.</p>
 *
 * <p>The work is split in two so that Gradle can cache the expensive half. {@code resolve} is a
 * cheap network probe that records the server jar Mojang currently publishes for each version,
 * including its sha1. {@code extract} is then a pure function of that index and of this code, which
 * makes it safe to mark cacheable: it only re-runs when Mojang publishes something new or the
 * extractor itself changes.</p>
 */
public final class VanillaDataGenerator {

  private static final String MANIFEST =
      "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json";
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
  private static final int DOWNLOAD_ATTEMPTS = 3;

  private VanillaDataGenerator() {
  }

  /**
   * Entry point.
   *
   * @param args {@code resolve <index>} or {@code extract <index> <jar-cache> <output>}
   */
  public static void main(String[] args) throws Exception {
    if (args.length == 2 && "resolve".equals(args[0])) {
      resolve(Path.of(args[1]));
    } else if (args.length == 4 && "extract".equals(args[0])) {
      extract(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]));
    } else {
      System.err.println("usage: VanillaDataGenerator resolve <index>");
      System.err.println("       VanillaDataGenerator extract <index> <jar-cache> <output>");
      System.exit(2);
    }
  }

  /**
   * Records the server jar Mojang currently publishes for every supported version. Deliberately
   * cheap and never cached: this is the probe that notices upstream moving.
   */
  private static void resolve(Path index) throws Exception {
    JsonObject resolved = new JsonObject();

    try (HttpClient http = HttpClient.newHttpClient()) {
      JsonObject manifest = getJson(http, MANIFEST);

      for (ProtocolVersion version : ProtocolVersion.SUPPORTED_VERSIONS) {
        if (!version.noLessThan(ProtocolVersion.MINECRAFT_1_19)) {
          continue;
        }

        String name = version.getVersionIntroducedIn();
        String versionUrl = findVersionUrl(manifest, name);
        if (versionUrl == null) {
          System.out.println("skipping " + name + ": not in the version manifest");
          continue;
        }

        JsonObject server = getJson(http, versionUrl).getAsJsonObject("downloads")
            .getAsJsonObject("server");
        JsonObject entry = new JsonObject();
        entry.addProperty("url", server.get("url").getAsString());
        entry.addProperty("sha1", server.get("sha1").getAsString());
        resolved.add(name, entry);
      }
    }

    Files.createDirectories(index.getParent());
    Files.writeString(index, GSON.toJson(resolved) + System.lineSeparator(),
        StandardCharsets.UTF_8);
    System.out.println("resolved " + resolved.size() + " versions");
  }

  /**
   * Runs the data generator for every version in the index, reusing any already-downloaded jar
   * whose sha1 still matches.
   */
  private static void extract(Path index, Path jarCache, Path output) throws Exception {
    JsonObject resolved;
    try (Reader reader = Files.newBufferedReader(index, StandardCharsets.UTF_8)) {
      resolved = GSON.fromJson(reader, JsonObject.class);
    }

    Files.createDirectories(jarCache);
    Files.createDirectories(output);

    // Anything left from a previous run with a different index would be picked up by the tests.
    try (Stream<Path> stale = Files.list(output)) {
      for (Path path : stale.toList()) {
        Files.deleteIfExists(path);
      }
    }

    Path work = Files.createTempDirectory("velocity-conformance");
    try (HttpClient http = HttpClient.newHttpClient()) {
      for (Map.Entry<String, JsonElement> entry : resolved.entrySet()) {
        String name = entry.getKey();
        JsonObject details = entry.getValue().getAsJsonObject();
        Path jar = ensureJar(http, jarCache, name, details.get("url").getAsString(),
            details.get("sha1").getAsString());

        System.out.println("running the data generator for " + name);
        JsonObject extracted = runGenerator(work, name, jar);
        Files.writeString(output.resolve(name + ".json"),
            GSON.toJson(extracted) + System.lineSeparator(), StandardCharsets.UTF_8);
      }
    } finally {
      deleteRecursively(work);
    }
  }

  private static Path ensureJar(HttpClient http, Path jarCache, String name, String url,
      String sha1) throws Exception {
    // Keyed by sha1 so a republished jar is a different file rather than a stale hit.
    Path jar = jarCache.resolve(name + "-" + sha1 + ".jar");
    if (Files.isRegularFile(jar) && sha1.equals(sha1Of(jar))) {
      System.out.println("reusing cached " + name + " server jar");
      return jar;
    }

    System.out.println("downloading " + name + " server jar");
    Exception last = null;
    for (int attempt = 1; attempt <= DOWNLOAD_ATTEMPTS; attempt++) {
      try {
        HttpResponse<Path> response = http.send(HttpRequest.newBuilder(URI.create(url)).build(),
            HttpResponse.BodyHandlers.ofFile(jar));
        if (response.statusCode() == 200 && sha1.equals(sha1Of(jar))) {
          return jar;
        }
        last = new IOException(response.statusCode() == 200
            ? "sha1 mismatch for " + url : "unexpected status " + response.statusCode());
      } catch (IOException | InterruptedException e) {
        // These runs pull close to a gigabyte from Mojang's CDN; a dropped connection now and
        // again should not fail the job.
        last = e;
      }

      Files.deleteIfExists(jar);
      if (attempt < DOWNLOAD_ATTEMPTS) {
        System.out.println("  retrying (" + attempt + "/" + DOWNLOAD_ATTEMPTS + "): "
            + last.getMessage());
        Thread.sleep(Duration.ofSeconds(5L * attempt).toMillis());
      }
    }
    throw new IllegalStateException("Unable to download " + url, last);
  }

  private static JsonObject runGenerator(Path work, String name, Path jar) throws Exception {
    // The bundler unpacks libraries/, versions/ and logs/ relative to the working directory, so
    // keep it inside the scratch directory rather than wherever Gradle happened to start us.
    Path sandbox = work.resolve(name + "-sandbox");
    Files.createDirectories(sandbox);
    Path reports = work.resolve(name + "-reports");

    try {
      return runGenerator(sandbox, reports, name, jar);
    } finally {
      // The bundler unpacks a full library set per sandbox and these do not dedup, so drop each
      // version's scratch space as we go rather than letting nineteen of them accumulate.
      deleteRecursively(sandbox);
      deleteRecursively(reports);
    }
  }

  private static JsonObject runGenerator(Path sandbox, Path reports, String name, Path jar)
      throws Exception {
    Process process = new ProcessBuilder(
        ProcessHandle.current().info().command().orElse("java"),
        "-DbundlerMainClass=net.minecraft.data.Main",
        "-jar", jar.toAbsolutePath().toString(),
        "--reports", "--output", reports.toAbsolutePath().toString())
        .directory(sandbox.toFile())
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start();

    if (process.waitFor() != 0) {
      throw new IllegalStateException("the data generator failed for " + name);
    }

    JsonObject result = new JsonObject();
    JsonObject registries = readIfPresent(reports.resolve("reports/registries.json"));
    if (registries != null && registries.has("minecraft:command_argument_type")) {
      JsonObject argumentTypes = new JsonObject();
      JsonObject entries = registries.getAsJsonObject("minecraft:command_argument_type")
          .getAsJsonObject("entries");
      for (Map.Entry<String, JsonElement> entry : entries.entrySet()) {
        argumentTypes.addProperty(entry.getKey(),
            entry.getValue().getAsJsonObject().get("protocol_id").getAsInt());
      }
      result.add("command_argument_type", argumentTypes);
    }
    return result;
  }

  private static String sha1Of(Path path) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-1");
    try (InputStream in = Files.newInputStream(path)) {
      byte[] buffer = new byte[8192];
      int read;
      while ((read = in.read(buffer)) != -1) {
        digest.update(buffer, 0, read);
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private static JsonObject readIfPresent(Path path) throws IOException {
    if (!Files.isRegularFile(path)) {
      return null;
    }
    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      return GSON.fromJson(reader, JsonObject.class);
    }
  }

  private static String findVersionUrl(JsonObject manifest, String name) {
    for (JsonElement element : manifest.getAsJsonArray("versions")) {
      JsonObject version = element.getAsJsonObject();
      if (version.get("id").getAsString().equals(name)) {
        return version.get("url").getAsString();
      }
    }
    return null;
  }

  private static JsonObject getJson(HttpClient http, String url) throws Exception {
    HttpResponse<InputStream> response = http.send(
        HttpRequest.newBuilder(URI.create(url)).build(),
        HttpResponse.BodyHandlers.ofInputStream());
    try (Reader reader = new InputStreamReader(response.body(), StandardCharsets.UTF_8)) {
      return GSON.fromJson(reader, JsonObject.class);
    }
  }

  private static void deleteRecursively(Path root) throws IOException {
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
