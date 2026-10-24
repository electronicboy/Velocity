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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.proxy.conformance.harness.report.CellResult.Failure;
import com.velocitypowered.proxy.conformance.harness.report.CellResult.Outcome;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Tests ledger parsing and how a cell's failures are judged against it.
 */
class LedgerTest {

  private static final String TOML = """
      [[expect]]
      proxy = "velocity"
      scenario = "play/exercise"
      versions = ">=1.19"
      signature = "MISMATCH S->C PLAY minecraft:commands"
      detail = "sent minecraft:resource_or_tag delivered minecraft:resource"
      reason = "resource_or_tag is re-encoded as resource"
      issue = 1234

      [[expect]]
      proxy = "velocity"
      signature = "REORDERED C->S PLAY minecraft:chat"
      flaky = true
      reason = "chat waits for an async event"

      [[expect]]
      proxy = "waterfall"
      versions = "1.8..1.12.2"
      signature = "DROPPED"
      reason = "waterfall consumes something"

      [[unreachable]]
      packet = "minecraft:debug_sample"
      versions = ">=1.20.5"
      reason = "only sent to subscribed operators"
      """;

  private static final Ledger LEDGER = Ledger.parse(TOML);

  private static final Failure COMMANDS = new Failure("MISMATCH S->C PLAY minecraft:commands",
      "/locate changed (sent minecraft:resource_or_tag delivered minecraft:resource); "
          + "payload differs at byte 120");
  private static final Failure OTHER_COMMANDS = new Failure(
      "MISMATCH S->C PLAY minecraft:commands", "/give changed (sent brigadier:integer[03] "
          + "delivered brigadier:integer[01])");
  private static final Failure DROPPED_KEEP_ALIVE = new Failure(
      "DROPPED S->C PLAY minecraft:keep_alive", "sent but never delivered: 8 bytes [...]");

  private static List<Ledger.Expectation> velocityExercise() {
    return LEDGER.expectations("velocity", "play/exercise", "1.21.4");
  }

  private static CellResult judge(List<Failure> failures, List<Ledger.Expectation> expectations) {
    return CellResult.judge("1.21.4", "play/exercise", failures, expectations, List.of(), 10,
        Duration.ofSeconds(1));
  }

  @Test
  void parsesExpectationFields() {
    List<Ledger.Expectation> expectations = velocityExercise();
    assertEquals(2, expectations.size());

    Ledger.Expectation commands = expectations.get(0);
    assertEquals("velocity", commands.proxy());
    assertEquals("play/exercise", commands.scenario());
    assertEquals(">=1.19", commands.versions().toString());
    assertEquals("MISMATCH S->C PLAY minecraft:commands", commands.signature());
    assertEquals("sent minecraft:resource_or_tag delivered minecraft:resource",
        commands.detail());
    assertFalse(commands.flaky());
    assertEquals("resource_or_tag is re-encoded as resource", commands.reason());
    assertEquals(1234, commands.issue());

    Ledger.Expectation chat = expectations.get(1);
    assertEquals("*", chat.scenario());
    assertEquals("*", chat.versions().toString());
    assertEquals("", chat.detail());
    assertTrue(chat.flaky());
    assertEquals(0, chat.issue());
  }

  @Test
  void expectationsAreSelectedByProxyScenarioAndVersion() {
    assertEquals(1, LEDGER.expectations("velocity", "status", "1.21.4").size());
    assertEquals(1, LEDGER.expectations("velocity", "play/exercise", "1.18.2").size());
    assertEquals(List.of("DROPPED"), LEDGER.expectations("waterfall", "status", "1.12.2")
        .stream().map(Ledger.Expectation::signature).toList());
    assertEquals(List.of(), LEDGER.expectations("waterfall", "status", "1.20.4"));
    assertEquals(List.of(), LEDGER.expectations("bungeecord", "play/exercise", "1.21.4"));
  }

  @Test
  void unreachableIsSelectedByPacketAndVersion() {
    assertEquals(Optional.of("only sent to subscribed operators"),
        LEDGER.unreachable("minecraft:debug_sample", "1.21"));
    assertEquals(Optional.empty(), LEDGER.unreachable("minecraft:debug_sample", "1.20.4"));
    assertEquals(Optional.empty(), LEDGER.unreachable("minecraft:keep_alive", "1.21"));
  }

  @Test
  void entryWithoutRequiredFieldIsRejected() {
    assertThrows(IllegalArgumentException.class,
        () -> Ledger.parse("[[expect]]\nsignature = \"DROPPED\"\n"));
    assertThrows(IllegalArgumentException.class,
        () -> Ledger.parse("[[expect]]\nreason = \"no signature\"\n"));
    assertThrows(IllegalArgumentException.class,
        () -> Ledger.parse("[[unreachable]]\nreason = \"no packet\"\n"));
  }

  @Test
  void emptyLedgerExpectsNothing() {
    assertEquals(List.of(), Ledger.empty().expectations("velocity", "play/exercise", "1.21.4"));
    assertEquals(List.of(), Ledger.parse("").expectations("velocity", "play/exercise", "1.21"));
  }

  @Test
  void committedLedgerParses() throws IOException {
    // Gradle runs tests from the project directory.
    Ledger committed = Ledger.load(Path.of("ledger.toml"));
    assertFalse(committed.expectations("velocity", "play/exercise", "1.21.4").isEmpty());
  }

  @Test
  void noFailuresIsPass() {
    CellResult result = judge(List.of(), List.of());
    assertEquals(Outcome.PASS, result.outcome());
    assertEquals(List.of(), result.unexpected());
    assertEquals(List.of(), result.expected());
    assertEquals(List.of(), result.stale());
    assertEquals(10, result.matched());
    assertNull(result.error());
  }

  @Test
  void unmatchedFlakyExpectationIsStillPass() {
    List<Ledger.Expectation> chatOnly = LEDGER.expectations("velocity", "status", "1.21.4");
    assertTrue(chatOnly.get(0).flaky());
    CellResult result = judge(List.of(), chatOnly);
    assertEquals(Outcome.PASS, result.outcome());
    assertEquals(List.of(), result.stale());
  }

  @Test
  void onlyExpectedFailuresIsKnownFail() {
    CellResult result = judge(List.of(COMMANDS), velocityExercise());
    assertEquals(Outcome.KNOWN_FAIL, result.outcome());
    assertEquals(List.of(COMMANDS), result.expected());
    assertEquals(List.of(), result.unexpected());
    assertEquals(List.of(), result.stale());
    assertFalse(result.outcome().failsGate());
  }

  @Test
  void unexpectedFailureAlongsideExpectedOnesIsRegression() {
    CellResult result = judge(List.of(COMMANDS, DROPPED_KEEP_ALIVE), velocityExercise());
    assertEquals(Outcome.REGRESSION, result.outcome());
    assertEquals(List.of(DROPPED_KEEP_ALIVE), result.unexpected());
    assertEquals(List.of(COMMANDS), result.expected());
    assertTrue(result.outcome().failsGate());
  }

  @Test
  void unmatchedExpectationIsUnexpectedPass() {
    CellResult result = judge(List.of(), velocityExercise());
    assertEquals(Outcome.UNEXPECTED_PASS, result.outcome());
    assertEquals(1, result.stale().size());
    assertEquals("MISMATCH S->C PLAY minecraft:commands", result.stale().get(0).signature());
    assertTrue(result.outcome().failsGate());
  }

  @Test
  void detailMustBeContainedInTheFailureDetail() {
    CellResult result = judge(List.of(OTHER_COMMANDS), velocityExercise());
    assertEquals(Outcome.REGRESSION, result.outcome());
    assertEquals(List.of(OTHER_COMMANDS), result.unexpected());
    assertEquals(List.of(), result.expected());
    assertEquals(1, result.stale().size());
  }

  @Test
  void signatureIsMatchedAsPrefix() {
    Ledger.Expectation dropped = LEDGER.expectations("waterfall", "status", "1.12.2").get(0);
    assertTrue(dropped.matches("DROPPED S->C PLAY minecraft:keep_alive", "anything"));
    assertFalse(dropped.matches("MISMATCH S->C PLAY minecraft:keep_alive", "anything"));
  }
}
