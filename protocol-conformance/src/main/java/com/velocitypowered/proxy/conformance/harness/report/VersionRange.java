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

import java.util.ArrayList;
import java.util.List;

/**
 * A set of Minecraft versions written as in the ledger: {@code *}, {@code 1.20.4},
 * {@code >=1.20.2}, {@code <1.13}, {@code 1.8..1.12.2}, or a comma-separated list of those.
 *
 * <p>Versions are compared by their numeric components, so {@code 1.9 < 1.20.2 < 26.1}; a
 * pre-release suffix ({@code 26.4-snapshot-5}) sorts before its release and after everything
 * older.</p>
 */
public final class VersionRange {

  private final List<String[]> clauses = new ArrayList<>();
  private final String text;

  private VersionRange(String text) {
    this.text = text;
  }

  /**
   * Parses a range.
   *
   * @param text the range
   * @return the parsed range
   */
  public static VersionRange parse(String text) {
    VersionRange range = new VersionRange(text);
    for (String raw : text.split(",")) {
      String clause = raw.trim();
      if (clause.isEmpty()) {
        continue;
      }
      if (clause.equals("*")) {
        range.clauses.add(new String[] {"*"});
      } else if (clause.contains("..")) {
        String[] parts = clause.split("\\.\\.", 2);
        range.clauses.add(new String[] {"..", parts[0].trim(), parts[1].trim()});
      } else if (clause.startsWith(">=") || clause.startsWith("<=")) {
        range.clauses.add(new String[] {clause.substring(0, 2), clause.substring(2).trim()});
      } else if (clause.startsWith(">") || clause.startsWith("<")) {
        range.clauses.add(new String[] {clause.substring(0, 1), clause.substring(1).trim()});
      } else {
        range.clauses.add(new String[] {"=", clause});
      }
    }
    return range;
  }

  /**
   * Returns whether {@code version} is in the range.
   *
   * @param version a version id
   * @return whether it matches
   */
  public boolean contains(String version) {
    for (String[] clause : clauses) {
      boolean match = switch (clause[0]) {
        case "*" -> true;
        case "=" -> compare(version, clause[1]) == 0;
        case ">=" -> compare(version, clause[1]) >= 0;
        case "<=" -> compare(version, clause[1]) <= 0;
        case ">" -> compare(version, clause[1]) > 0;
        case "<" -> compare(version, clause[1]) < 0;
        case ".." -> compare(version, clause[1]) >= 0 && compare(version, clause[2]) <= 0;
        default -> false;
      };
      if (match) {
        return true;
      }
    }
    return false;
  }

  /**
   * Compares two version ids.
   *
   * @param a one version
   * @param b another
   * @return negative, zero or positive
   */
  public static int compare(String a, String b) {
    String[] aa = a.split("-", 2);
    String[] bb = b.split("-", 2);
    String[] an = aa[0].split("\\.");
    String[] bn = bb[0].split("\\.");
    for (int i = 0; i < Math.max(an.length, bn.length); i++) {
      int x = i < an.length ? number(an[i]) : 0;
      int y = i < bn.length ? number(bn[i]) : 0;
      if (x != y) {
        return Integer.compare(x, y);
      }
    }
    boolean firstPre = aa.length > 1;
    boolean secondPre = bb.length > 1;
    if (firstPre != secondPre) {
      return firstPre ? -1 : 1;
    }
    return firstPre ? comparePreRelease(aa[1], bb[1]) : 0;
  }

  /**
   * Orders pre-release suffixes the way Mojang releases them: snapshots, then pre-releases, then
   * release candidates, each numbered naturally ({@code snapshot-5 < snapshot-10}).
   */
  private static int comparePreRelease(String a, String b) {
    int rank = Integer.compare(preReleaseRank(a), preReleaseRank(b));
    if (rank != 0) {
      return rank;
    }
    int number = Integer.compare(number(a.replaceAll("\\D+", " ").trim().replaceAll(" .*", "")),
        number(b.replaceAll("\\D+", " ").trim().replaceAll(" .*", "")));
    return number != 0 ? number : a.compareTo(b);
  }

  private static int preReleaseRank(String suffix) {
    if (suffix.startsWith("rc")) {
      return 2;
    }
    return suffix.startsWith("pre") ? 1 : 0;
  }

  private static int number(String part) {
    try {
      return Integer.parseInt(part);
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  @Override
  public String toString() {
    return text;
  }
}
