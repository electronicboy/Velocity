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

package com.velocitypowered.proxy.conformance.harness;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * A run's configuration, from the command line.
 *
 * @param proxyJar the proxy under test
 * @param proxyKind {@code velocity} or {@code waterfall}
 * @param proxyJava the Java executable for the proxy
 * @param versions the Minecraft versions to test
 * @param work scratch space
 * @param report where reports go
 * @param ledger the expectations file, or {@code null}
 * @param cache where downloads and generator output are kept
 * @param jdks JDK homes for vanilla servers
 * @param scenarios scenario ids to run; empty for all
 * @param gate whether to exit non-zero on regressions
 */
public record SuiteConfig(Path proxyJar, String proxyKind, Path proxyJava, List<String> versions,
                          Path work, Path report, Path ledger, Path cache, List<Path> jdks,
                          Set<String> scenarios, boolean gate) {
}
