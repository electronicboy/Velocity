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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Java installations available for running vanilla servers. A server runs on the smallest
 * installation at least as new as Mojang asks for. Provide Java 8: old releases boot on newer JDKs,
 * but 1.8.9's networking resets every connection at login on 17.
 */
public final class JavaRuntimes {

  private static final Pattern VERSION = Pattern.compile("JAVA_VERSION=\"(\\d+)(?:\\.(\\d+))?");

  private record Install(int major, Path home) {
  }

  private final List<Install> runtimes = new ArrayList<>();

  /**
   * Indexes the given installations by reading their {@code release} files.
   *
   * @param homes JDK or JRE home directories
   */
  public JavaRuntimes(List<Path> homes) {
    for (Path home : homes) {
      int major = majorOf(home);
      if (major > 0) {
        runtimes.add(new Install(major, home));
      }
    }
    if (runtimes.isEmpty()) {
      Path current = Path.of(System.getProperty("java.home"));
      runtimes.add(new Install(Runtime.version().feature(), current));
    }
    runtimes.sort(Comparator.comparingInt(Install::major));
  }

  /**
   * Returns the {@code java} executable to use for a server needing {@code major}.
   *
   * @param major the minimum Java major version
   * @return the executable
   */
  public Path forMajor(int major) {
    for (Install runtime : runtimes) {
      if (runtime.major() >= major) {
        return executable(runtime.home());
      }
    }
    Install newest = runtimes.getLast();
    System.err.println("[java] no Java " + major + "+ configured; trying Java " + newest.major());
    return executable(newest.home());
  }

  private static Path executable(Path home) {
    return home.resolve("bin").resolve("java");
  }

  private static int majorOf(Path home) {
    try {
      String release = Files.readString(home.resolve("release"), StandardCharsets.UTF_8);
      Matcher m = VERSION.matcher(release);
      if (!m.find()) {
        return -1;
      }
      int first = Integer.parseInt(m.group(1));
      // Java 8 reports itself as 1.8.
      return first == 1 && m.group(2) != null ? Integer.parseInt(m.group(2)) : first;
    } catch (IOException | NumberFormatException e) {
      return -1;
    }
  }
}
