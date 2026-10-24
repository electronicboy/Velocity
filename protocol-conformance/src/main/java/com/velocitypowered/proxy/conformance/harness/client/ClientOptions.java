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

import com.velocitypowered.proxy.conformance.harness.auth.FakeSessionService;

/**
 * How the scripted client behaves.
 *
 * @param name the player name
 * @param auth the session service to {@code join} through, or {@code null} to skip the client
 *     half of authentication (an online-mode proxy must then reject the login)
 * @param echoKnownPacks whether to claim every known pack the server offers, as a vanilla client
 *     of the same version does; {@code false} forces the server to send full registry data
 */
public record ClientOptions(String name, FakeSessionService auth, boolean echoKnownPacks) {
}
