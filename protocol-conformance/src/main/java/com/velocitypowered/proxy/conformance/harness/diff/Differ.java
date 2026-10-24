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

import com.velocitypowered.proxy.conformance.harness.diff.Finding.Kind;
import com.velocitypowered.proxy.conformance.harness.trace.TraceEvent;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Aligns what one side sent with what the other side received and classifies every difference.
 *
 * <p>Alignment is greedy with a bounded lookahead: identical packets (same phase, id and bytes)
 * pair up in order; when the heads differ, the nearest re-synchronisation point within the window
 * wins, and anything skipped is unmatched. A head pair that cannot be re-synchronised is treated
 * as a substitution, which becomes a {@link Kind#MISMATCH} when both are the same packet type.
 * Unmatched packets with identical content on both sides are {@link Kind#REORDERED}.</p>
 */
public final class Differ {

  private static final int WINDOW = 96;

  private Differ() {
  }

  /**
   * The outcome of one comparison.
   *
   * @param findings every difference, tolerated ones included
   * @param matched how many packets arrived intact and in order
   */
  public record Result(List<Finding> findings, int matched) {

    public List<Finding> failures() {
      return findings.stream().filter(f -> f.kind().isFailure()).toList();
    }
  }

  /**
   * Compares two captures of the same direction.
   *
   * @param ctx the comparison context
   * @param source what the sender emitted
   * @param sink what the receiver got
   * @param rules the proxy's known transformations
   * @return the findings
   */
  public static Result compare(RuleContext ctx, List<TraceEvent> source, List<TraceEvent> sink,
      ProxyRules rules) {
    List<Integer> unmatchedSource = new ArrayList<>();
    List<Integer> unmatchedSink = new ArrayList<>();
    List<int[]> substitutions = new ArrayList<>();
    int matched = 0;

    int i = 0;
    int j = 0;
    while (i < source.size() && j < sink.size()) {
      TraceEvent s = source.get(i);
      TraceEvent k = sink.get(j);
      if (same(s, k)) {
        matched++;
        i++;
        j++;
        continue;
      }
      int sinkAhead = find(sink, j + 1, s);
      int sourceAhead = find(source, i + 1, k);
      if (sinkAhead >= 0 && (sourceAhead < 0 || sinkAhead - j <= sourceAhead - i)) {
        // The receiver got extra packets before this one.
        for (int x = j; x < sinkAhead; x++) {
          unmatchedSink.add(x);
        }
        j = sinkAhead;
      } else if (sourceAhead >= 0) {
        // The sender's packets before this one never arrived (here, at least).
        for (int x = i; x < sourceAhead; x++) {
          unmatchedSource.add(x);
        }
        i = sourceAhead;
      } else {
        substitutions.add(new int[] {i, j});
        i++;
        j++;
      }
    }
    for (; i < source.size(); i++) {
      unmatchedSource.add(i);
    }
    for (; j < sink.size(); j++) {
      unmatchedSink.add(j);
    }

    List<Finding> findings = new ArrayList<>();
    for (int[] pair : substitutions) {
      TraceEvent s = source.get(pair[0]);
      TraceEvent k = sink.get(pair[1]);
      if (s.phase() == k.phase() && s.id() == k.id()) {
        Optional<String> why = rules.tolerateMismatch(ctx, s, k);
        findings.add(new Finding(why.isPresent() ? Kind.TOLERATED : Kind.MISMATCH, ctx.dir(),
            s.phase(), ctx.name(s), pair[0], pair[1], why.orElseGet(() -> rules
                .explainMismatch(ctx, s, k).map(e -> e + "; " + describe(s, k))
                .orElseGet(() -> describe(s, k)))));
      } else {
        unmatchedSource.add(pair[0]);
        unmatchedSink.add(pair[1]);
      }
    }

    // Identical content on both sides, just not where it was expected: a reordering.
    for (int si = 0; si < unmatchedSource.size(); si++) {
      TraceEvent s = source.get(unmatchedSource.get(si));
      for (int ki = 0; ki < unmatchedSink.size(); ki++) {
        TraceEvent k = sink.get(unmatchedSink.get(ki));
        if (same(s, k)) {
          Optional<String> why = rules.tolerateReorder(ctx, s);
          findings.add(new Finding(why.isPresent() ? Kind.TOLERATED : Kind.REORDERED, ctx.dir(),
              s.phase(), ctx.name(s), unmatchedSource.get(si), unmatchedSink.get(ki),
              why.orElse("arrived intact but out of order")));
          unmatchedSource.remove(si--);
          unmatchedSink.remove(ki);
          break;
        }
      }
    }

    // Same type on both sides but not adjacent enough to have been paired: a mismatch, which a
    // rule may still explain (the proxy re-encoding a packet asynchronously, say).
    for (int si = 0; si < unmatchedSource.size(); si++) {
      TraceEvent s = source.get(unmatchedSource.get(si));
      for (int ki = 0; ki < unmatchedSink.size(); ki++) {
        TraceEvent k = sink.get(unmatchedSink.get(ki));
        if (s.phase() == k.phase() && s.id() == k.id()) {
          Optional<String> why = rules.tolerateMismatch(ctx, s, k);
          findings.add(new Finding(why.isPresent() ? Kind.TOLERATED : Kind.MISMATCH, ctx.dir(),
              s.phase(), ctx.name(s), unmatchedSource.get(si), unmatchedSink.get(ki),
              why.orElseGet(() -> rules.explainMismatch(ctx, s, k)
                  .map(e -> e + "; " + describe(s, k)).orElseGet(() -> describe(s, k)))));
          unmatchedSource.remove(si--);
          unmatchedSink.remove(ki);
          break;
        }
      }
    }

    for (int index : unmatchedSource) {
      TraceEvent s = source.get(index);
      Optional<String> why = rules.tolerateDrop(ctx, s);
      findings.add(new Finding(why.isPresent() ? Kind.TOLERATED : Kind.DROPPED, ctx.dir(),
          s.phase(), ctx.name(s), index, -1,
          why.orElse("sent but never delivered: " + preview(s))));
    }
    for (int index : unmatchedSink) {
      TraceEvent k = sink.get(index);
      Optional<String> why = rules.tolerateInjection(ctx, k);
      findings.add(new Finding(why.isPresent() ? Kind.TOLERATED : Kind.INJECTED, ctx.dir(),
          k.phase(), ctx.name(k), -1, index,
          why.orElse("delivered but never sent: " + preview(k))));
    }
    findings.sort((a, b) -> Integer.compare(order(a), order(b)));
    return new Result(findings, matched);
  }

  private static int order(Finding f) {
    return f.sourceIndex() >= 0 ? f.sourceIndex() * 2 : f.sinkIndex() * 2 + 1;
  }

  private static boolean same(TraceEvent a, TraceEvent b) {
    return a.phase() == b.phase() && a.sameContent(b);
  }

  private static int find(List<TraceEvent> events, int from, TraceEvent target) {
    int end = Math.min(events.size(), from + WINDOW);
    for (int x = from; x < end; x++) {
      if (same(events.get(x), target)) {
        return x;
      }
    }
    return -1;
  }

  /**
   * Describes how two payloads differ: lengths, the first differing offset, and hex context.
   *
   * @param source what was sent
   * @param sink what arrived
   * @return the description
   */
  public static String describe(TraceEvent source, TraceEvent sink) {
    byte[] a = source.payload();
    byte[] b = sink.payload();
    int offset = 0;
    while (offset < a.length && offset < b.length && a[offset] == b[offset]) {
      offset++;
    }
    int from = Math.max(0, offset - 8);
    return String.format("payload differs at byte %d (sent %d bytes, delivered %d): sent[%d..]=%s"
            + " delivered[%d..]=%s", offset, a.length, b.length, from, hex(a, from), from,
        hex(b, from));
  }

  /**
   * Summarises a packet for a finding: its length and the start of its payload.
   *
   * @param e the packet
   * @return the summary
   */
  public static String preview(TraceEvent e) {
    byte[] p = e.payload();
    return p.length + " bytes [" + hex(p, 0) + (p.length > 32 ? "…" : "") + "]";
  }

  private static String hex(byte[] bytes, int from) {
    int to = Math.min(bytes.length, from + 32);
    return from >= to ? "(end)" : HexFormat.of().formatHex(bytes, from, to);
  }
}
