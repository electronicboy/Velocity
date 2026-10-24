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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.velocitypowered.proxy.conformance.harness.wire.Buf;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Tests that NBT canonicalisation ignores compound key order and nothing else.
 */
class NbtTest {

  private static final int BYTE = 1;
  private static final int INT = 3;
  private static final int STRING = 8;
  private static final int LIST = 9;
  private static final int COMPOUND = 10;

  private static void utf(Buf buf, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    buf.writeShort(bytes.length).writeBytes(bytes);
  }

  private static Consumer<Buf> entry(int type, String name, Consumer<Buf> payload) {
    return buf -> {
      buf.writeByte(type);
      utf(buf, name);
      payload.accept(buf);
    };
  }

  private static Consumer<Buf> compound(List<Consumer<Buf>> entries) {
    return buf -> {
      entries.forEach(e -> e.accept(buf));
      buf.writeByte(0);
    };
  }

  private static Consumer<Buf> intList(int... values) {
    return buf -> {
      buf.writeByte(INT).writeInt(values.length);
      for (int value : values) {
        buf.writeInt(value);
      }
    };
  }

  /**
   * {a: 1b, s: "hi", i: 5, c: {x: 1, y: 2}, l: [1, 2]}, with keys in the given or reversed
   * order.
   */
  private static Consumer<Buf> sample(boolean reversed, int first) {
    List<Consumer<Buf>> inner = List.of(
        entry(INT, "x", b -> b.writeInt(1)),
        entry(INT, "y", b -> b.writeInt(2)));
    List<Consumer<Buf>> outer = List.of(
        entry(BYTE, "a", b -> b.writeByte(first)),
        entry(STRING, "s", b -> utf(b, "hi")),
        entry(INT, "i", b -> b.writeInt(5)),
        entry(COMPOUND, "c", compound(reversed ? inner.reversed() : inner)),
        entry(LIST, "l", intList(1, 2)));
    return compound(reversed ? outer.reversed() : outer);
  }

  private static byte[] namedRoot(String name, Consumer<Buf> body) {
    Buf buf = new Buf().writeByte(COMPOUND);
    utf(buf, name);
    body.accept(buf);
    return buf.toByteArray();
  }

  private static byte[] unnamedRoot(Consumer<Buf> body) {
    Buf buf = new Buf().writeByte(COMPOUND);
    body.accept(buf);
    return buf.toByteArray();
  }

  private static String canonical(byte[] bytes, boolean named) {
    Buf buf = new Buf(bytes);
    String canonical = Nbt.readCanonical(buf, named);
    assertEquals(0, buf.readableBytes(), "the whole tag is consumed");
    return canonical;
  }

  @Test
  void keyOrderIsInsignificant() {
    String forward = canonical(namedRoot("", sample(false, 1)), true);
    String reversed = canonical(namedRoot("", sample(true, 1)), true);
    assertEquals(forward, reversed);
    assertEquals("{a=1b, c={x=1i, y=2i}, i=5i, l=[1i,2i,], s=\"hi\"}", forward);
  }

  @Test
  void differentValuesAreNotEqual() {
    assertNotEquals(canonical(namedRoot("", sample(false, 1)), true),
        canonical(namedRoot("", sample(true, 2)), true));

    Consumer<Buf> listForward = compound(List.of(entry(LIST, "l", intList(1, 2))));
    Consumer<Buf> listBackward = compound(List.of(entry(LIST, "l", intList(2, 1))));
    assertNotEquals(canonical(unnamedRoot(listForward), false),
        canonical(unnamedRoot(listBackward), false), "list order is significant");
  }

  @Test
  void namedAndUnnamedRootsCanonicaliseAlike() {
    String unnamed = canonical(unnamedRoot(sample(false, 1)), false);
    assertEquals(unnamed, canonical(namedRoot("", sample(true, 1)), true));
    assertEquals(unnamed, canonical(namedRoot("root", sample(false, 1)), true));
  }

  @Test
  void endTagIsEnd() {
    assertEquals("end", canonical(new byte[] {0}, true));
    assertEquals("end", canonical(new byte[] {0}, false));
  }
}
