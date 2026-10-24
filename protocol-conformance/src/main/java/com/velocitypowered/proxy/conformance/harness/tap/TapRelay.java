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

package com.velocitypowered.proxy.conformance.harness.tap;

import com.velocitypowered.proxy.conformance.harness.trace.Trace;
import com.velocitypowered.proxy.conformance.harness.wire.Buf;
import com.velocitypowered.proxy.conformance.harness.wire.Direction;
import com.velocitypowered.proxy.conformance.harness.wire.FrameCodec;
import com.velocitypowered.proxy.conformance.harness.wire.PacketTable;
import com.velocitypowered.proxy.conformance.harness.wire.Phase;
import com.velocitypowered.proxy.conformance.harness.wire.PhaseTracker;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A byte-transparent TCP relay placed between the proxy and the vanilla backend. Each frame is
 * read whole, recorded, and then forwarded unchanged, so the backend sees exactly what the proxy
 * sent and the harness sees it too.
 */
public final class TapRelay implements AutoCloseable {

  private final ServerSocket server;
  private final InetSocketAddress backend;
  private final PacketTable table;
  private final int protocol;
  private final List<Session> sessions = new CopyOnWriteArrayList<>();
  private final List<Socket> sockets = new CopyOnWriteArrayList<>();
  private volatile boolean closed;

  /**
   * Starts a relay on an ephemeral loopback port.
   *
   * @param backend the vanilla server
   * @param table the version's packet table
   * @param protocol the version's protocol number
   * @throws IOException if the port cannot be bound
   */
  public TapRelay(InetSocketAddress backend, PacketTable table, int protocol) throws IOException {
    this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
    this.backend = backend;
    this.table = table;
    this.protocol = protocol;
    Thread acceptor = new Thread(this::acceptLoop, "tap-accept");
    acceptor.setDaemon(true);
    acceptor.start();
  }

  public int port() {
    return server.getLocalPort();
  }

  /**
   * One proxied backend connection.
   *
   * @param index the order in which it was accepted
   * @param trace both directions as seen between proxy and backend
   * @param tracker the connection's phase
   */
  public record Session(int index, Trace trace, PhaseTracker tracker) {
  }

  public List<Session> sessions() {
    return List.copyOf(sessions);
  }

  /**
   * Waits for the {@code index}th backend connection.
   *
   * @param index zero-based
   * @param timeout how long to wait
   * @return the session, if it arrived in time
   * @throws InterruptedException if interrupted
   */
  public Optional<Session> awaitSession(int index, Duration timeout) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (sessions.size() > index) {
        return Optional.of(sessions.get(index));
      }
      Thread.sleep(20);
    }
    return Optional.empty();
  }

  private void acceptLoop() {
    while (!closed) {
      try {
        Socket upstream = server.accept();
        upstream.setTcpNoDelay(true);
        Socket downstream = new Socket();
        downstream.setTcpNoDelay(true);
        downstream.connect(backend, 5000);
        sockets.add(upstream);
        sockets.add(downstream);

        PhaseTracker tracker = new PhaseTracker(table, protocol);
        Session session = new Session(sessions.size(),
            new Trace("backend#" + sessions.size()), tracker);
        sessions.add(session);
        start(session, upstream, downstream, Direction.SERVERBOUND);
        start(session, downstream, upstream, Direction.CLIENTBOUND);
      } catch (IOException e) {
        if (!closed) {
          System.err.println("[tap] accept failed: " + e);
        }
      }
    }
  }

  private void start(Session session, Socket from, Socket to, Direction dir) {
    Thread pump = new Thread(() -> pump(session, from, to, dir),
        "tap-" + session.index() + "-" + dir.id());
    pump.setDaemon(true);
    pump.start();
  }

  private void pump(Session session, Socket from, Socket to, Direction dir) {
    PhaseTracker tracker = session.tracker();
    FrameCodec codec = new FrameCodec(tracker::compressionThreshold);
    try (from; to) {
      RecordingInputStream in = new RecordingInputStream(
          new BufferedInputStream(from.getInputStream()));
      OutputStream out = to.getOutputStream();
      try {
        while (true) {
          Optional<byte[]> packet = codec.decode(in);
          final byte[] raw = in.drain();
          if (packet.isEmpty()) {
            break;
          }
          Buf buf = new Buf(packet.get());
          int id = buf.readVarInt();
          byte[] payload = buf.readRemaining();
          Phase phase = tracker.phase(dir);
          session.trace().add(dir, phase, id, payload);
          // Update phase/compression state before the frame is released, so that whatever the
          // peer sends in response is already parsed under the new state.
          tracker.observe(dir, id, payload);
          out.write(raw);
          out.flush();
        }
      } catch (IOException | RuntimeException e) {
        if (closed || from.isClosed() || to.isClosed()) {
          return;
        }
        // A parse failure must never break the connection under test: fall back to blind copying
        // and leave a note. This is a harness limitation, not a finding.
        System.err.println("[tap] " + dir.id() + " parse stopped: " + e);
        out.write(in.drain());
        out.flush();
        copy(in, out);
      }
    } catch (IOException ignored) {
      // Connection closed by either end.
    }
  }

  private static void copy(InputStream in, OutputStream out) throws IOException {
    byte[] buffer = new byte[16384];
    int n;
    while ((n = in.read(buffer)) != -1) {
      out.write(buffer, 0, n);
      out.flush();
    }
  }

  @Override
  public void close() throws IOException {
    closed = true;
    server.close();
    List<Socket> toClose = new ArrayList<>(sockets);
    for (Socket socket : toClose) {
      try {
        socket.close();
      } catch (IOException ignored) {
        // Best effort.
      }
    }
  }
}
