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
import com.velocitypowered.proxy.conformance.harness.wire.Direction;
import com.velocitypowered.proxy.conformance.harness.wire.PacketTable;
import com.velocitypowered.proxy.conformance.harness.wire.Protocols;

/**
 * What a rule may know about the comparison it is asked to judge.
 *
 * @param dir the direction being compared
 * @param table the version's packet table
 * @param protocol the version's protocol number
 * @param argumentTypes command argument type names by wire id (1.19+), or {@code null}
 */
public record RuleContext(Direction dir, PacketTable table, int protocol,
                          String[] argumentTypes) {

  public String name(TraceEvent e) {
    return table.describe(e.phase(), e.dir(), e.id());
  }

  public boolean atLeast(int level) {
    return Protocols.atLeast(protocol, level);
  }
}
