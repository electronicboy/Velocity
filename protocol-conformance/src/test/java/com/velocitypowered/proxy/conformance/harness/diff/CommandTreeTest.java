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

import static com.velocitypowered.proxy.conformance.harness.diff.DiffFixtures.argument;
import static com.velocitypowered.proxy.conformance.harness.diff.DiffFixtures.commands;
import static com.velocitypowered.proxy.conformance.harness.diff.DiffFixtures.identifier;
import static com.velocitypowered.proxy.conformance.harness.diff.DiffFixtures.intRange;
import static com.velocitypowered.proxy.conformance.harness.diff.DiffFixtures.literal;
import static com.velocitypowered.proxy.conformance.harness.diff.DiffFixtures.redirect;
import static com.velocitypowered.proxy.conformance.harness.diff.DiffFixtures.root;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.proxy.conformance.harness.diff.DiffFixtures.Spec;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Tests the independent command tree decoder and its order-insensitive comparison.
 */
class CommandTreeTest {

  /** A 1.19+ argument type registry, in wire id order (integer is id 3). */
  static final List<String> ARGUMENT_TYPES = List.of("brigadier:bool", "brigadier:float",
      "brigadier:double", "brigadier:integer", "brigadier:long", "brigadier:string",
      "minecraft:entity", "minecraft:resource_or_tag", "minecraft:resource");
  private static final String[] ARGUMENT_TYPE_ARRAY = ARGUMENT_TYPES.toArray(String[]::new);

  private static final Spec N = argument("n", "n", "brigadier:integer", intRange(0, 10));
  private static final Spec A = literal("a", true, "n");
  private static final Spec B = redirect("b", "root");

  private static final String CANONICAL_A = "(5 a (6 n brigadier:integer[03000000000000000a]))";
  private static final String CANONICAL_B = "(9 b ->)";

  /** root -> a (executable) -> n (integer 0..10); root -> b (redirect to root). */
  static List<Spec> base() {
    return List.of(root("a", "b"), A, N, B);
  }

  /** The same tree with every node renumbered and the root's children in the other order. */
  static List<Spec> renumbered() {
    return List.of(N, B, A, root("b", "a"));
  }

  /** The base tree plus a root literal "velocity", the root listed last. */
  static List<Spec> withVelocity() {
    return List.of(literal("velocity", true), A, N, B, root("velocity", "a", "b"));
  }

  /** The base tree plus /locate taking a biome argument parsed by {@code parser}. */
  static List<Spec> withLocate(String parser) {
    return List.of(root("a", "b", "locate"), A, N, B, literal("locate", false, "biome"),
        argument("biome", "biome", parser, identifier("minecraft:worldgen/biome")));
  }

  private static CommandTree decode(List<Spec> nodes, int protocol) {
    return CommandTree.decode(commands(nodes, protocol, ARGUMENT_TYPES), protocol,
        ARGUMENT_TYPE_ARRAY);
  }

  @Test
  void decodesStringParsersBefore119() {
    CommandTree tree = CommandTree.decode(commands(base(), 758, null), 758, null);
    assertEquals(Map.of("a", CANONICAL_A, "b", CANONICAL_B), tree.rootChildren());
  }

  @Test
  void decodesRegistryParsersFrom119() {
    byte[] payload = commands(base(), 774, ARGUMENT_TYPES);
    CommandTree tree = CommandTree.decode(payload, 774, ARGUMENT_TYPE_ARRAY);
    assertEquals(Map.of("a", CANONICAL_A, "b", CANONICAL_B), tree.rootChildren());

    assertThrows(IllegalArgumentException.class, () -> CommandTree.decode(payload, 774, null));
    String[] truncated = Arrays.copyOf(ARGUMENT_TYPE_ARRAY, 3);
    assertThrows(IllegalArgumentException.class,
        () -> CommandTree.decode(payload, 774, truncated));
  }

  @Test
  void trailingBytesAreRejected() {
    byte[] payload = commands(base(), 758, null);
    byte[] longer = Arrays.copyOf(payload, payload.length + 1);
    assertThrows(IllegalArgumentException.class, () -> CommandTree.decode(longer, 758, null));
  }

  @Test
  void renumberedTreeComparesEqual() {
    for (int protocol : new int[] {758, 774}) {
      CommandTree a = decode(base(), protocol);
      CommandTree b = decode(renumbered(), protocol);
      assertEquals(a.rootChildren(), b.rootChildren(), "protocol " + protocol);
      assertNull(CommandTree.compareWithExtras(a, b, Set.of()), "protocol " + protocol);
      assertNull(CommandTree.compareWithExtras(b, a, Set.of()), "protocol " + protocol);
    }
  }

  @Test
  void extraRootLiteralIsAcceptedOnlyWhenAllowed() {
    for (int protocol : new int[] {758, 774}) {
      CommandTree sent = decode(base(), protocol);
      CommandTree delivered = decode(withVelocity(), protocol);
      assertEquals("/velocity added", CommandTree.compareWithExtras(sent, delivered, Set.of()));
      assertNull(CommandTree.compareWithExtras(sent, delivered, Set.of("velocity")));
      assertEquals("/velocity missing",
          CommandTree.compareWithExtras(delivered, sent, Set.of("velocity")));
    }
  }

  @Test
  void changedParserIsReportedByName() {
    for (int protocol : new int[] {758, 774}) {
      CommandTree sent = decode(withLocate("minecraft:resource_or_tag"), protocol);
      CommandTree delivered = decode(withLocate("minecraft:resource"), protocol);
      String problem = CommandTree.compareWithExtras(sent, delivered, Set.of());
      assertEquals("/locate changed (sent minecraft:resource_or_tag delivered "
          + "minecraft:resource)", problem);
    }
  }

  @Test
  void changedArgumentBoundsAreReported() {
    List<Spec> wider = List.of(root("a", "b"), A,
        argument("n", "n", "brigadier:integer", intRange(0, 11)), B);
    String problem = CommandTree.compareWithExtras(decode(base(), 774), decode(wider, 774),
        Set.of("velocity"));
    assertTrue(problem.startsWith("/a changed ("), problem);
  }
}
