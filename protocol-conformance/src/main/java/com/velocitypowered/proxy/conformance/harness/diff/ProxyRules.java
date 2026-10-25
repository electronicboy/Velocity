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

import com.velocitypowered.proxy.conformance.harness.trace.TraceEvent;
import java.util.Optional;

/**
 * The transformations a particular proxy is known to make to vanilla traffic. Every method
 * returns the justification when it tolerates a difference, naming the proxy behaviour that
 * causes it; an unexplained tolerance is a bug in the rules.
 */
public interface ProxyRules {

  /**
   * Returns the proxy's name for reports.
   *
   * @return the name
   */
  String kind();

  /**
   * Decides whether a same-type packet whose bytes changed in transit is expected.
   *
   * @param ctx the comparison context
   * @param source what was sent
   * @param sink what arrived
   * @return the justification, if tolerated
   */
  default Optional<String> tolerateMismatch(RuleContext ctx, TraceEvent source, TraceEvent sink) {
    return Optional.empty();
  }

  /**
   * Explains a mismatch the rules recognise but cannot tolerate, for the finding detail.
   *
   * @param ctx the comparison context
   * @param source what was sent
   * @param sink what arrived
   * @return the explanation, if any
   */
  default Optional<String> explainMismatch(RuleContext ctx, TraceEvent source, TraceEvent sink) {
    return Optional.empty();
  }

  /**
   * Decides whether a packet that never arrived was consumed on purpose.
   *
   * @param ctx the comparison context
   * @param source what was sent
   * @return the justification, if tolerated
   */
  default Optional<String> tolerateDrop(RuleContext ctx, TraceEvent source) {
    return Optional.empty();
  }

  /**
   * Decides whether a packet that was never sent was originated on purpose.
   *
   * @param ctx the comparison context
   * @param sink what arrived
   * @return the justification, if tolerated
   */
  default Optional<String> tolerateInjection(RuleContext ctx, TraceEvent sink) {
    return Optional.empty();
  }

  /**
   * Decides whether a packet delivered out of order was deferred on purpose.
   *
   * @param ctx the comparison context
   * @param event the packet
   * @return the justification, if tolerated
   */
  default Optional<String> tolerateReorder(RuleContext ctx, TraceEvent event) {
    return Optional.empty();
  }

  /**
   * Decides whether a packet the proxy originated around a server switch is expected.
   *
   * @param ctx the comparison context
   * @param sink what arrived
   * @param newEpoch whether it belongs to the session being switched to (otherwise to the one
   *     being left)
   * @return the justification, if tolerated
   */
  default Optional<String> tolerateSwitchInjection(RuleContext ctx, TraceEvent sink,
      boolean newEpoch) {
    return Optional.empty();
  }

  /**
   * Decides whether a packet the proxy rewrote around a server switch is expected.
   *
   * @param ctx the comparison context
   * @param source what the backend sent
   * @param sink what arrived
   * @param newEpoch whether it belongs to the session being switched to
   * @return the justification, if tolerated
   */
  default Optional<String> tolerateSwitchMismatch(RuleContext ctx, TraceEvent source,
      TraceEvent sink, boolean newEpoch) {
    return Optional.empty();
  }

  /**
   * Returns the rules for a proxy kind.
   *
   * @param kind {@code velocity}, {@code waterfall} or {@code none}
   * @return the rules
   */
  static ProxyRules forKind(String kind) {
    return switch (kind) {
      case "velocity" -> new VelocityRules();
      case "waterfall", "bungeecord" -> new BungeeRules();
      case "none" -> () -> "none";
      default -> throw new IllegalArgumentException("unknown proxy kind " + kind);
    };
  }
}
