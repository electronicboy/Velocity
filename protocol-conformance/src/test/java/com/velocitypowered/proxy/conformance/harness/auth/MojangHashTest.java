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

package com.velocitypowered.proxy.conformance.harness.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Tests the session server hash against the published reference values.
 */
class MojangHashTest {

  @Test
  void matchesPublishedReferenceValues() {
    assertEquals("4ed1f46bbe04bc756bcb17c0c7ce3e4632f06a48", MojangHash.of("Notch"));
    assertEquals("-7c9d5b0044c130109a5d7b5fb5c317c02b4e28c1", MojangHash.of("jeb_"));
    // Leading zero nibble dropped: 39 hex digits.
    assertEquals("88e16a1019277b15d58faf0541e11910eb756f6", MojangHash.of("simon"));
  }

  @Test
  void serverIdHashesBaseSecretAndKeyInOrder() {
    byte[] secret = "No".getBytes(StandardCharsets.ISO_8859_1);
    byte[] key = "tch".getBytes(StandardCharsets.ISO_8859_1);
    assertEquals(MojangHash.of("Notch"), MojangHash.serverId("", secret, key));
    assertEquals(MojangHash.of("jeb_"), MojangHash.serverId("j", "eb".getBytes(
        StandardCharsets.ISO_8859_1), "_".getBytes(StandardCharsets.ISO_8859_1)));
  }
}
