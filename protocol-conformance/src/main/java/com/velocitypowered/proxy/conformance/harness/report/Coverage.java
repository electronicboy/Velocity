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

import com.velocitypowered.proxy.conformance.harness.trace.TraceEvent;
import com.velocitypowered.proxy.conformance.harness.wire.Direction;
import com.velocitypowered.proxy.conformance.harness.wire.PacketTable;
import com.velocitypowered.proxy.conformance.harness.wire.Phase;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Which vanilla packet types crossed the proxy during a version's scenarios, against every type
 * the version defines. This is the honest answer to "were all packets checked".
 */
public final class Coverage {

  private static final Phase[] PHASES = {Phase.CONFIGURATION, Phase.PLAY};

  private final String version;
  private final PacketTable table;
  private final Map<Phase, Map<Direction, TreeSet<String>>> seen = new EnumMap<>(Phase.class);

  public Coverage(String version, PacketTable table) {
    this.version = version;
    this.table = table;
  }

  public String version() {
    return version;
  }

  /**
   * Records packets that crossed the proxy and were compared.
   *
   * @param events the packets
   */
  public synchronized void record(Collection<TraceEvent> events) {
    for (TraceEvent e : events) {
      seen.computeIfAbsent(e.phase(), k -> new EnumMap<>(Direction.class))
          .computeIfAbsent(e.dir(), k -> new TreeSet<>())
          .add(table.describe(e.phase(), e.dir(), e.id()));
    }
  }

  /**
   * One phase and direction's coverage.
   *
   * @param phase the phase
   * @param dir the direction
   * @param total how many packet types the version defines
   * @param covered the types exercised
   * @param unreachable types the ledger marks unreachable, with reasons
   * @param missing the rest
   */
  public record Row(Phase phase, Direction dir, int total, List<String> covered,
                    Map<String, String> unreachable, List<String> missing) {
  }

  /**
   * Summarises coverage.
   *
   * @param ledger supplies unreachable packets
   * @return one row per phase and direction
   */
  public synchronized List<Row> rows(Ledger ledger) {
    List<Row> rows = new ArrayList<>();
    for (Phase phase : PHASES) {
      for (Direction dir : Direction.values()) {
        TreeSet<String> all = new TreeSet<>(table.names(phase, dir));
        if (all.isEmpty()) {
          continue;
        }
        TreeSet<String> covered = new TreeSet<>(
            seen.getOrDefault(phase, Map.of()).getOrDefault(dir, new TreeSet<>()));
        covered.retainAll(all);
        Map<String, String> unreachable = new TreeMap<>();
        List<String> missing = new ArrayList<>();
        for (String name : all) {
          if (covered.contains(name)) {
            continue;
          }
          ledger.unreachable(name, version).ifPresentOrElse(
              reason -> unreachable.put(name, reason), () -> missing.add(name));
        }
        rows.add(new Row(phase, dir, all.size(), List.copyOf(covered), unreachable, missing));
      }
    }
    return rows;
  }
}
