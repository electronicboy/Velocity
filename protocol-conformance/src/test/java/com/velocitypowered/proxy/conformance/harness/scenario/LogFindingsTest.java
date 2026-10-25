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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests how console logs become findings.
 */
class LogFindingsTest {

  @Test
  void stackTraceIsOneFindingCarryingTheException() {
    List<String> lines = List.of(
        "[16:48:08 INFO]: Exception while handling packet for [connected player] Bob",
        "java.lang.IllegalStateException: Not connected to server!",
        "\tat com.example.Foo.bar(Foo.java:1) ~[proxy.jar:?]",
        "\tat com.example.Foo.baz(Foo.java:2) ~[proxy.jar:?]",
        "\tat com.example.Foo.qux(Foo.java:3) ~[proxy.jar:?]",
        "[16:48:09 INFO]: all good");
    List<String> findings = SessionScenario.logFindings(lines, SessionScenario.PROXY_TROUBLE);
    assertEquals(1, findings.size(), findings::toString);
    assertTrue(findings.getFirst().contains("Not connected to server"));
  }

  @Test
  void separateErrorsAreSeparateFindings() {
    List<String> lines = List.of(
        "[16:48:08 ERROR]: first problem",
        "[16:48:08 INFO]: in between",
        "[16:48:09 ERROR]: second problem");
    assertEquals(2, SessionScenario.logFindings(lines, SessionScenario.PROXY_TROUBLE).size());
  }

  @Test
  void quietLogHasNoFindings() {
    assertEquals(List.of(), SessionScenario.logFindings(
        List.of("[16:48:08 INFO]: Done (1.45s)!"), SessionScenario.PROXY_TROUBLE));
  }
}
