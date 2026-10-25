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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Aligns what one side sent with what the other side received and classifies every difference.
 *
 * <p>Alignment is a patience diff: packets unique on both sides anchor it, and the gaps between
 * anchors are walked greedily with a bounded lookahead, where the nearest re-synchronisation
 * point wins and anything skipped is unmatched. A pair that cannot be re-synchronised is treated
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
    Alignment alignment = new Alignment();
    align(source, 0, source.size(), sink, 0, sink.size(), alignment);
    List<Integer> unmatchedSource = alignment.unmatchedSource;
    List<Integer> unmatchedSink = alignment.unmatchedSink;
    List<int[]> substitutions = alignment.substitutions;
    final int matched = alignment.matched;
    unmatchedSource.sort(null);
    unmatchedSink.sort(null);

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

    // Same type on both sides but not adjacent enough to have been paired (the proxy re-encoded
    // it asynchronously, say). Pair what a rule explains first, then the rest in order of
    // appearance, so that several packets of one type are not cross-matched.
    for (boolean toleratedOnly : new boolean[] {true, false}) {
      for (int si = 0; si < unmatchedSource.size(); si++) {
        TraceEvent s = source.get(unmatchedSource.get(si));
        for (int ki = 0; ki < unmatchedSink.size(); ki++) {
          TraceEvent k = sink.get(unmatchedSink.get(ki));
          if (s.phase() != k.phase() || s.id() != k.id()) {
            continue;
          }
          Optional<String> why = rules.tolerateMismatch(ctx, s, k);
          if (toleratedOnly && why.isEmpty()) {
            continue;
          }
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

  private static final class Alignment {
    final List<Integer> unmatchedSource = new ArrayList<>();
    final List<Integer> unmatchedSink = new ArrayList<>();
    final List<int[]> substitutions = new ArrayList<>();
    int matched;
  }

  /**
   * Patience alignment: packets whose content occurs exactly once on each side anchor the
   * alignment (longest increasing run of such pairs), and the gaps between anchors are aligned
   * recursively, falling back to a greedy walk when a gap has no unique packets. Anchoring on
   * unique content stops a repeated packet (a title reset, a keep-alive) far ahead from dragging
   * the alignment off course.
   */
  private static void align(List<TraceEvent> source, int sourceFrom, int sourceTo, List<TraceEvent> sink,
      int sinkFrom, int sinkTo, Alignment out) {
    while (sourceFrom < sourceTo && sinkFrom < sinkTo && same(source.get(sourceFrom), sink.get(sinkFrom))) {
      out.matched++;
      sourceFrom++;
      sinkFrom++;
    }
    while (sourceFrom < sourceTo && sinkFrom < sinkTo && same(source.get(sourceTo - 1), sink.get(sinkTo - 1))) {
      out.matched++;
      sourceTo--;
      sinkTo--;
    }
    if (sourceFrom == sourceTo || sinkFrom == sinkTo) {
      for (int i = sourceFrom; i < sourceTo; i++) {
        out.unmatchedSource.add(i);
      }
      for (int j = sinkFrom; j < sinkTo; j++) {
        out.unmatchedSink.add(j);
      }
      return;
    }

    Map<String, int[]> counts = new HashMap<>();
    for (int i = sourceFrom; i < sourceTo; i++) {
      int[] c = counts.computeIfAbsent(key(source.get(i)), k -> new int[] {0, 0, -1, -1});
      c[0]++;
      c[2] = i;
    }
    for (int j = sinkFrom; j < sinkTo; j++) {
      int[] c = counts.get(key(sink.get(j)));
      if (c != null) {
        c[1]++;
        c[3] = j;
      }
    }
    List<int[]> pairs = new ArrayList<>();
    for (int[] c : counts.values()) {
      if (c[0] == 1 && c[1] == 1 && same(source.get(c[2]), sink.get(c[3]))) {
        pairs.add(new int[] {c[2], c[3]});
      }
    }
    pairs.sort((a, b) -> Integer.compare(a[0], b[0]));
    List<int[]> anchors = longestIncreasing(pairs);
    if (anchors.isEmpty()) {
      greedy(source, sourceFrom, sourceTo, sink, sinkFrom, sinkTo, out);
      return;
    }
    int s = sourceFrom;
    int k = sinkFrom;
    for (int[] anchor : anchors) {
      align(source, s, anchor[0], sink, k, anchor[1], out);
      out.matched++;
      s = anchor[0] + 1;
      k = anchor[1] + 1;
    }
    align(source, s, sourceTo, sink, k, sinkTo, out);
  }

  private static List<int[]> longestIncreasing(List<int[]> pairs) {
    // Patience sorting over the sink indices, pairs already ordered by source index.
    List<int[]> tails = new ArrayList<>();
    int[] previous = new int[pairs.size()];
    List<Integer> tailIndex = new ArrayList<>();
    for (int p = 0; p < pairs.size(); p++) {
      int value = pairs.get(p)[1];
      int lo = 0;
      int hi = tails.size();
      while (lo < hi) {
        int mid = (lo + hi) >>> 1;
        if (tails.get(mid)[1] < value) {
          lo = mid + 1;
        } else {
          hi = mid;
        }
      }
      previous[p] = lo > 0 ? tailIndex.get(lo - 1) : -1;
      if (lo == tails.size()) {
        tails.add(pairs.get(p));
        tailIndex.add(p);
      } else {
        tails.set(lo, pairs.get(p));
        tailIndex.set(lo, p);
      }
    }
    List<int[]> out = new ArrayList<>();
    for (int p = tailIndex.isEmpty() ? -1 : tailIndex.getLast(); p >= 0; p = previous[p]) {
      out.add(pairs.get(p));
    }
    Collections.reverse(out);
    return out;
  }

  private static void greedy(List<TraceEvent> source, int sourceFrom, int sourceTo, List<TraceEvent> sink,
      int sinkFrom, int sinkTo, Alignment out) {
    int i = sourceFrom;
    int j = sinkFrom;
    while (i < sourceTo && j < sinkTo) {
      TraceEvent s = source.get(i);
      TraceEvent k = sink.get(j);
      if (same(s, k)) {
        out.matched++;
        i++;
        j++;
        continue;
      }
      int sinkAhead = find(sink, j + 1, sinkTo, s);
      int sourceAhead = find(source, i + 1, sourceTo, k);
      if (sinkAhead >= 0 && (sourceAhead < 0 || sinkAhead - j <= sourceAhead - i)) {
        for (int x = j; x < sinkAhead; x++) {
          out.unmatchedSink.add(x);
        }
        j = sinkAhead;
      } else if (sourceAhead >= 0) {
        for (int x = i; x < sourceAhead; x++) {
          out.unmatchedSource.add(x);
        }
        i = sourceAhead;
      } else {
        out.substitutions.add(new int[] {i, j});
        i++;
        j++;
      }
    }
    for (; i < sourceTo; i++) {
      out.unmatchedSource.add(i);
    }
    for (; j < sinkTo; j++) {
      out.unmatchedSink.add(j);
    }
  }

  private static String key(TraceEvent e) {
    return e.phase() + ":" + e.id() + ":" + Arrays.hashCode(e.payload()) + ":"
        + e.payload().length;
  }

  private static int order(Finding f) {
    return f.sourceIndex() >= 0 ? f.sourceIndex() * 2 : f.sinkIndex() * 2 + 1;
  }

  private static boolean same(TraceEvent a, TraceEvent b) {
    return a.phase() == b.phase() && a.sameContent(b);
  }

  private static int find(List<TraceEvent> events, int from, int to, TraceEvent target) {
    int end = Math.min(to, from + WINDOW);
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
