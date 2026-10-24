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

/**
 * An infrastructure failure: a process that did not start, a port that could not be bound, a
 * download that failed. Always reported as {@code error}, never as a protocol finding.
 */
public class HarnessException extends Exception {

  public HarnessException(String message) {
    super(message);
  }

  public HarnessException(String message, Throwable cause) {
    super(message, cause);
  }
}
