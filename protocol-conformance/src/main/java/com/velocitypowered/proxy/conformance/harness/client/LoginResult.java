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

package com.velocitypowered.proxy.conformance.harness.client;

import java.util.List;
import java.util.UUID;

/**
 * What the proxy told the client during login, decoded by the harness from the vanilla layout.
 *
 * @param uuid the profile id in login success
 * @param name the profile name in login success
 * @param properties the profile properties (1.19+), as {@code name=value} strings
 * @param encrypted whether the proxy requested encryption
 * @param compressionThreshold the threshold set during login, or {@code -1}
 */
public record LoginResult(UUID uuid, String name, List<String> properties, boolean encrypted,
                          int compressionThreshold) {
}
