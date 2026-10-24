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

package com.velocitypowered.proxy.conformance.harness.diff;

import static com.velocitypowered.proxy.conformance.harness.diff.DiffFixtures.PLAY_CB_SYSTEM_CHAT;
import static com.velocitypowered.proxy.conformance.harness.diff.DiffFixtures.clientboundPlay;
import static com.velocitypowered.proxy.conformance.harness.diff.DiffFixtures.ctx;
import static com.velocitypowered.proxy.conformance.harness.diff.DiffFixtures.keepAlive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.proxy.conformance.harness.diff.Finding.Kind;
import com.velocitypowered.proxy.conformance.harness.trace.TraceEvent;
import com.velocitypowered.proxy.conformance.harness.wire.Direction;
import com.velocitypowered.proxy.conformance.harness.wire.Phase;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Tests trace alignment and finding classification on synthetic traces.
 */
class DifferTest {

  private static final RuleContext CTX = ctx(Direction.CLIENTBOUND, 774, null);
  private static final ProxyRules NONE = ProxyRules.forKind("none");

  private static final TraceEvent A = keepAlive(1);
  private static final TraceEvent B = clientboundPlay(PLAY_CB_SYSTEM_CHAT,
      0x08, 0x00, 0x05, 'h', 'e', 'l', 'l', 'o', 0x00);
  private static final TraceEvent C = keepAlive(3);
  private static final TraceEvent D = keepAlive(4);

  /** Rules that do not tolerate anything, but say so in a recognisable way. */
  private static final ProxyRules STRICT = () -> "strict";

  /** Rules that tolerate every kind of difference with a fixed justification. */
  private static final ProxyRules LENIENT = new ProxyRules() {
    @Override
    public String kind() {
      return "lenient";
    }

    @Override
    public Optional<String> tolerateMismatch(RuleContext ctx, TraceEvent source,
        TraceEvent sink) {
      return Optional.of("stub tolerates mismatch");
    }

    @Override
    public Optional<String> tolerateDrop(RuleContext ctx, TraceEvent source) {
      return Optional.of("stub tolerates drop");
    }

    @Override
    public Optional<String> tolerateInjection(RuleContext ctx, TraceEvent sink) {
      return Optional.of("stub tolerates injection");
    }

    @Override
    public Optional<String> tolerateReorder(RuleContext ctx, TraceEvent event) {
      return Optional.of("stub tolerates reorder");
    }
  };

  private static TraceEvent flipByte(TraceEvent event, int offset) {
    byte[] payload = event.payload().clone();
    payload[offset] ^= 0x01;
    return new TraceEvent(event.nanos(), event.dir(), event.phase(), event.id(), payload);
  }

  private static Finding only(Differ.Result result) {
    assertEquals(1, result.findings().size(), () -> "findings: " + result.findings());
    return result.findings().get(0);
  }

  @Test
  void identicalTracesHaveNoFindings() {
    List<TraceEvent> trace = List.of(A, B, C, D);
    for (ProxyRules rules : List.of(NONE, STRICT, LENIENT)) {
      Differ.Result result = Differ.compare(CTX, trace, List.copyOf(trace), rules);
      assertEquals(List.of(), result.findings(), rules.kind());
      assertEquals(4, result.matched(), rules.kind());
    }
    assertEquals(0, Differ.compare(CTX, List.of(), List.of(), NONE).matched());
  }

  @Test
  void flippedByteIsMismatchAtFirstDifferingOffset() {
    for (ProxyRules rules : List.of(NONE, STRICT)) {
      Differ.Result result = Differ.compare(CTX, List.of(A, B, C), List.of(A, flipByte(B, 5), C),
          rules);
      Finding finding = only(result);
      assertEquals(Kind.MISMATCH, finding.kind());
      assertEquals("minecraft:system_chat", finding.packet());
      assertEquals(Phase.PLAY, finding.phase());
      assertEquals(Direction.CLIENTBOUND, finding.dir());
      assertEquals(1, finding.sourceIndex());
      assertEquals(1, finding.sinkIndex());
      assertTrue(finding.detail().startsWith("payload differs at byte 5 (sent 9 bytes, delivered 9)"),
          finding.detail());
      assertEquals(2, result.matched());
      assertEquals(List.of(finding), result.failures());
    }
  }

  @Test
  void mismatchDetailIncludesRuleExplanation() {
    ProxyRules explaining = new ProxyRules() {
      @Override
      public String kind() {
        return "explaining";
      }

      @Override
      public Optional<String> explainMismatch(RuleContext ctx, TraceEvent source,
          TraceEvent sink) {
        return Optional.of("chat was re-encoded");
      }
    };
    Finding finding = only(Differ.compare(CTX, List.of(A, B), List.of(A, flipByte(B, 0)),
        explaining));
    assertEquals(Kind.MISMATCH, finding.kind());
    assertTrue(finding.detail().startsWith("chat was re-encoded; payload differs at byte 0 "),
        finding.detail());
  }

  @Test
  void removedPacketIsDropped() {
    Differ.Result result = Differ.compare(CTX, List.of(A, B, C), List.of(A, C), NONE);
    Finding finding = only(result);
    assertEquals(Kind.DROPPED, finding.kind());
    assertEquals("minecraft:system_chat", finding.packet());
    assertEquals(1, finding.sourceIndex());
    assertEquals(-1, finding.sinkIndex());
    assertTrue(finding.detail().startsWith("sent but never delivered: 9 bytes ["),
        finding.detail());
    assertEquals(2, result.matched());
  }

  @Test
  void trailingPacketThatNeverArrivedIsDropped() {
    Finding finding = only(Differ.compare(CTX, List.of(A, B), List.of(A), NONE));
    assertEquals(Kind.DROPPED, finding.kind());
    assertEquals(1, finding.sourceIndex());
  }

  @Test
  void extraPacketIsInjected() {
    Differ.Result result = Differ.compare(CTX, List.of(A, C), List.of(A, B, C), NONE);
    Finding finding = only(result);
    assertEquals(Kind.INJECTED, finding.kind());
    assertEquals("minecraft:system_chat", finding.packet());
    assertEquals(-1, finding.sourceIndex());
    assertEquals(1, finding.sinkIndex());
    assertTrue(finding.detail().startsWith("delivered but never sent: 9 bytes ["),
        finding.detail());
    assertEquals(2, result.matched());
  }

  @Test
  void swappedNeighboursAreReordered() {
    Differ.Result result = Differ.compare(CTX, List.of(A, B, C, D), List.of(A, C, B, D), NONE);
    Finding finding = only(result);
    assertEquals(Kind.REORDERED, finding.kind());
    assertEquals("arrived intact but out of order", finding.detail());
    // Whichever of the pair is reported, it is reported at both of its positions.
    assertTrue(Set.of(1, 2).contains(finding.sourceIndex()), finding.toString());
    assertEquals(3 - finding.sourceIndex(), finding.sinkIndex());
    assertEquals(3, result.matched());
  }

  @Test
  void packetsInDifferentPhasesNeverMatch() {
    TraceEvent configA = new TraceEvent(0, A.dir(), Phase.CONFIGURATION, A.id(), A.payload());
    Differ.Result result = Differ.compare(CTX, List.of(A), List.of(configA), NONE);
    assertEquals(2, result.findings().size(), () -> "findings: " + result.findings());
    assertEquals(Kind.DROPPED, result.findings().get(0).kind());
    assertEquals(Kind.INJECTED, result.findings().get(1).kind());
  }

  @Test
  void lenientRulesTurnEveryDifferenceIntoTolerated() {
    List<List<List<TraceEvent>>> cases = List.of(
        List.of(List.of(A, B, C), List.of(A, flipByte(B, 5), C)),
        List.of(List.of(A, B, C), List.of(A, C)),
        List.of(List.of(A, C), List.of(A, B, C)),
        List.of(List.of(A, B, C, D), List.of(A, C, B, D)));
    List<String> justifications = List.of("stub tolerates mismatch", "stub tolerates drop",
        "stub tolerates injection", "stub tolerates reorder");
    for (int i = 0; i < cases.size(); i++) {
      Differ.Result result = Differ.compare(CTX, cases.get(i).get(0), cases.get(i).get(1),
          LENIENT);
      Finding finding = only(result);
      assertEquals(Kind.TOLERATED, finding.kind(), justifications.get(i));
      assertEquals(justifications.get(i), finding.detail());
      assertEquals(List.of(), result.failures());
    }
  }

  @Test
  void substitutedPacketOfAnotherTypeIsDropAndInjection() {
    TraceEvent other = keepAlive(5);
    Differ.Result result = Differ.compare(CTX, List.of(A, B, C, D), List.of(A, other, C, D),
        NONE);
    assertEquals(2, result.findings().size(), () -> "findings: " + result.findings());
    Finding dropped = result.findings().get(0);
    assertEquals(Kind.DROPPED, dropped.kind());
    assertEquals("minecraft:system_chat", dropped.packet());
    assertEquals(1, dropped.sourceIndex());
    Finding injected = result.findings().get(1);
    assertEquals(Kind.INJECTED, injected.kind());
    assertEquals("minecraft:keep_alive", injected.packet());
    assertEquals(1, injected.sinkIndex());
    assertEquals(3, result.matched());
  }
}
