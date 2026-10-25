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

import com.velocitypowered.proxy.conformance.harness.ManagedProcess;
import com.velocitypowered.proxy.conformance.harness.auth.FakeSessionService;
import com.velocitypowered.proxy.conformance.harness.diff.ProxyRules;
import com.velocitypowered.proxy.conformance.harness.report.Coverage;
import com.velocitypowered.proxy.conformance.harness.tap.TapRelay;
import com.velocitypowered.proxy.conformance.harness.vanilla.VanillaServer;
import com.velocitypowered.proxy.conformance.harness.vanilla.VanillaVersion;
import com.velocitypowered.proxy.conformance.harness.wire.PacketTable;
import java.net.InetSocketAddress;
import java.nio.file.Path;

/**
 * Everything a scenario may use.
 *
 * @param version the version under test
 * @param table its packet table
 * @param argumentTypes its command argument type names by id (1.19+), or {@code null}
 * @param server the vanilla backend
 * @param tap the relay between proxy and backend
 * @param auth the session service
 * @param proxyAddress where the proxy listens
 * @param proxy the proxy process, for log checks
 * @param rules the proxy's known transformations
 * @param traceDir where to write this cell's traces
 * @param coverage the version's coverage accumulator
 * @param serverB the second vanilla backend, or {@code null} unless a switch scenario needs it
 * @param tapB the relay in front of the second backend, or {@code null}
 */
public record ScenarioContext(VanillaVersion version, PacketTable table, String[] argumentTypes,
                              VanillaServer server, TapRelay tap, FakeSessionService auth,
                              InetSocketAddress proxyAddress, ManagedProcess proxy,
                              ProxyRules rules, Path traceDir, Coverage coverage,
                              VanillaServer serverB, TapRelay tapB) {
}
