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

/**
 * Moments in a server transition, as the client experiences them, at which a scenario may act.
 */
public enum TransitionPoint {
  /** Start configuration received, before acknowledging it (still in play). */
  START_CONFIGURATION,
  /** Configuration entered (after the login or configuration acknowledgement). */
  CONFIGURATION_ENTERED,
  /** The first configuration packet of this configuration phase received. */
  FIRST_CONFIGURATION_PACKET,
  /** Finish configuration received, before acknowledging it. */
  FINISH_CONFIGURATION,
  /** Play entered (after acknowledging finish configuration). */
  PLAY_ENTERED,
  /** A join game packet received. */
  JOIN_GAME;

  /**
   * A scenario's reaction to transition points, run on the client's reader thread so anything it
   * sends is ordered exactly against the client's own packets.
   */
  @FunctionalInterface
  public interface Listener {

    /**
     * Called at a transition point.
     *
     * @param point the moment
     * @param session the client
     * @throws java.io.IOException if sending fails
     */
    void on(TransitionPoint point, ClientSession session) throws java.io.IOException;
  }
}
