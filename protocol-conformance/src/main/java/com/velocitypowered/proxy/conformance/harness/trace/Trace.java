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

package com.velocitypowered.proxy.conformance.harness.trace;

import com.velocitypowered.proxy.conformance.harness.wire.Direction;
import com.velocitypowered.proxy.conformance.harness.wire.PacketTable;
import com.velocitypowered.proxy.conformance.harness.wire.Phase;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The packets observed at one capture point, in order. Thread-safe for a single writer per
 * direction and concurrent readers.
 */
public final class Trace {

  private final String label;
  private final List<TraceEvent> events = new ArrayList<>();
  private final long origin = System.nanoTime();

  public Trace(String label) {
    this.label = label;
  }

  public String label() {
    return label;
  }

  public synchronized void add(TraceEvent event) {
    events.add(event);
  }

  /**
   * Records a packet now.
   *
   * @param dir its direction
   * @param phase the phase it was read in
   * @param id its id
   * @param payload its body
   */
  public synchronized void add(Direction dir, Phase phase, int id, byte[] payload) {
    events.add(new TraceEvent(System.nanoTime(), dir, phase, id, payload));
  }

  public synchronized List<TraceEvent> events() {
    return List.copyOf(events);
  }

  /**
   * Returns the events travelling in {@code dir}.
   *
   * @param dir the direction
   * @return the events, in order
   */
  public synchronized List<TraceEvent> events(Direction dir) {
    return events.stream().filter(e -> e.dir() == dir).toList();
  }

  /**
   * Returns the events in {@code phase} and {@code dir}.
   *
   * @param dir the direction
   * @param phase the phase
   * @return the matching events, in order
   */
  public synchronized List<TraceEvent> events(Direction dir, Phase phase) {
    return events.stream().filter(e -> e.dir() == dir && e.phase() == phase).toList();
  }

  /**
   * Writes the trace in the one-record-per-line format: time, side, phase, id, name, length, hash.
   *
   * @param path where to write
   * @param table names packets
   * @param header a free-form header line
   * @throws IOException on a write error
   */
  public void write(Path path, PacketTable table, String header) throws IOException {
    List<TraceEvent> snapshot = events();
    try (Writer out = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
      out.write("# trace v1 " + label + " " + header + "\n");
      for (TraceEvent e : snapshot) {
        out.write(line(e, table, null));
        out.write('\n');
      }
    }
  }

  /**
   * Formats one event as a trace line.
   *
   * @param e the event
   * @param table names packets
   * @param annotation appended after the record, or {@code null}
   * @return the line
   */
  public String line(TraceEvent e, PacketTable table, String annotation) {
    String name = table.describe(e.phase(), e.dir(), e.id());
    String base = String.format("[%9.3f] %s %-13s 0x%02X %-40s len=%-7d %s",
        (e.nanos() - origin) / 1e9, e.dir().arrow(), e.phase().name(), e.id(), name,
        e.payload().length, e.hash());
    return annotation == null ? base : base + "   <-- " + annotation;
  }
}
