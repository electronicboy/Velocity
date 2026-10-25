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

package com.velocitypowered.proxy.conformance.harness.report;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.toml.TomlParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The committed expectations: which failures are known, and which packets cannot be provoked.
 *
 * <p>An expectation names a failure <em>signature</em> ({@code MISMATCH S->C PLAY
 * minecraft:commands}, or a check id such as {@code CHECK login.profile}) rather than a whole
 * cell, so a new, unrelated regression in a cell with a known failure is still caught.</p>
 */
public final class Ledger {

  /**
   * A known failure.
   *
   * @param proxy the proxy kind it applies to, or {@code *}
   * @param scenario scenario ids, comma-separated, each optionally a {@code prefix*}
   * @param versions the versions it applies to
   * @param signature a prefix of the failure signature
   * @param detail a substring the failure detail must contain, or empty
   * @param flaky whether the failure is timing-dependent, so its absence is not a fix
   * @param reason why it fails
   * @param issue the tracking issue, or {@code 0}
   */
  public record Expectation(String proxy, String scenario, VersionRange versions,
                            String signature, String detail, boolean flaky, String reason,
                            int issue) {

    /**
     * Returns whether this expectation applies to a cell.
     *
     * @param proxyKind the proxy kind
     * @param scenarioId the scenario
     * @param version the version id
     * @return whether it applies
     */
    public boolean appliesTo(String proxyKind, String scenarioId, String version) {
      return (proxy.equals("*") || proxy.equals(proxyKind))
          && scenarioMatches(scenarioId)
          && versions.contains(version);
    }

    /**
     * Scenarios are a comma-separated list of ids, each optionally ending in {@code *} to match
     * a prefix ({@code switch/*}).
     */
    private boolean scenarioMatches(String scenarioId) {
      for (String pattern : scenario.split(",")) {
        String p = pattern.strip();
        if (p.equals("*") || p.equals(scenarioId)
            || (p.endsWith("*") && scenarioId.startsWith(p.substring(0, p.length() - 1)))) {
          return true;
        }
      }
      return false;
    }

    /**
     * Returns whether a failure is this expectation.
     *
     * @param failureSignature the failure's signature
     * @param failureDetail the failure's detail
     * @return whether it matches
     */
    public boolean matches(String failureSignature, String failureDetail) {
      return failureSignature.startsWith(signature)
          && (detail.isEmpty() || failureDetail.contains(detail));
    }
  }

  /**
   * A packet the harness cannot provoke from a vanilla server and a scripted client.
   *
   * @param packet the Mojang packet name
   * @param versions the versions it applies to
   * @param reason why it is unreachable
   */
  public record Unreachable(String packet, VersionRange versions, String reason) {
  }

  private final List<Expectation> expectations;
  private final List<Unreachable> unreachable;

  private Ledger(List<Expectation> expectations, List<Unreachable> unreachable) {
    this.expectations = expectations;
    this.unreachable = unreachable;
  }

  public static Ledger empty() {
    return new Ledger(List.of(), List.of());
  }

  /**
   * Loads a ledger file.
   *
   * @param path the TOML file
   * @return the ledger
   * @throws IOException on a read error
   */
  public static Ledger load(Path path) throws IOException {
    return parse(Files.readString(path, StandardCharsets.UTF_8));
  }

  /**
   * Parses ledger TOML.
   *
   * @param toml the text
   * @return the ledger
   */
  public static Ledger parse(String toml) {
    Config config = new TomlParser().parse(toml);
    List<Expectation> expectations = new ArrayList<>();
    List<Config> expects = config.getOrElse("expect", List.of());
    for (Config e : expects) {
      expectations.add(new Expectation(
          e.getOrElse("proxy", "*"),
          e.getOrElse("scenario", "*"),
          VersionRange.parse(e.getOrElse("versions", "*")),
          required(e, "signature"),
          e.getOrElse("detail", ""),
          e.getOrElse("flaky", false),
          required(e, "reason"),
          e.<Number>getOrElse("issue", 0).intValue()));
    }
    List<Unreachable> unreachable = new ArrayList<>();
    List<Config> unreachables = config.getOrElse("unreachable", List.of());
    for (Config u : unreachables) {
      unreachable.add(new Unreachable(required(u, "packet"),
          VersionRange.parse(u.getOrElse("versions", "*")), required(u, "reason")));
    }
    return new Ledger(List.copyOf(expectations), List.copyOf(unreachable));
  }

  private static String required(Config config, String key) {
    String value = config.get(key);
    if (value == null) {
      throw new IllegalArgumentException("ledger entry missing '" + key + "': " + config);
    }
    return value;
  }

  /**
   * Returns the expectations that apply to a cell.
   *
   * @param proxyKind the proxy kind
   * @param scenario the scenario id
   * @param version the version id
   * @return the expectations
   */
  public List<Expectation> expectations(String proxyKind, String scenario, String version) {
    return expectations.stream().filter(e -> e.appliesTo(proxyKind, scenario, version)).toList();
  }

  /**
   * Returns why a packet is unreachable at a version, if it is.
   *
   * @param packet the Mojang packet name
   * @param version the version id
   * @return the reason
   */
  public Optional<String> unreachable(String packet, String version) {
    return unreachable.stream()
        .filter(u -> u.packet().equals(packet) && u.versions().contains(version))
        .map(Unreachable::reason)
        .findFirst();
  }
}
