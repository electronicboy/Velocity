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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.proxy.conformance.harness.Fixtures;
import com.velocitypowered.proxy.conformance.harness.trace.TraceEvent;
import com.velocitypowered.proxy.conformance.harness.wire.Buf;
import com.velocitypowered.proxy.conformance.harness.wire.Direction;
import com.velocitypowered.proxy.conformance.harness.wire.FrameCodec;
import com.velocitypowered.proxy.conformance.harness.wire.Phase;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Tests the tap relay over loopback, between a test client and a scripted fake backend.
 */
@Timeout(30)
class TapRelayTest {

  private static final int PROTOCOL = 774;
  private static final int SOCKET_TIMEOUT_MS = 5000;

  // Ids from fixtures/packets.json.
  private static final int HANDSHAKE_INTENTION = 0;
  private static final int LOGIN_SB_HELLO = 0;
  private static final int LOGIN_SB_ACKNOWLEDGED = 3;
  private static final int LOGIN_CB_FINISHED = 2;
  private static final int LOGIN_CB_COMPRESSION = 3;

  private ServerSocket backendServer;
  private TapRelay relay;
  private Socket client;
  private Socket backend;

  @BeforeEach
  void connect() throws IOException {
    InetAddress loopback = InetAddress.getLoopbackAddress();
    backendServer = new ServerSocket(0, 50, loopback);
    backendServer.setSoTimeout(SOCKET_TIMEOUT_MS);
    relay = new TapRelay(new InetSocketAddress(loopback, backendServer.getLocalPort()),
        Fixtures.packets(), PROTOCOL);
    client = new Socket(loopback, relay.port());
    client.setSoTimeout(SOCKET_TIMEOUT_MS);
    // The relay dials the backend as soon as it accepts the client.
    backend = backendServer.accept();
    backend.setSoTimeout(SOCKET_TIMEOUT_MS);
  }

  @AfterEach
  void close() throws IOException {
    for (AutoCloseable closeable : new AutoCloseable[] {client, backend, backendServer, relay}) {
      try {
        if (closeable != null) {
          closeable.close();
        }
      } catch (Exception ignored) {
        // Best effort.
      }
    }
  }

  private static byte[] handshakePayload() {
    return new Buf().writeVarInt(PROTOCOL).writeString("localhost").writeShort(25565)
        .writeVarInt(2).toByteArray();
  }

  private static byte[] helloPayload() {
    return new Buf().writeString("Alice")
        .writeUuid(UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5")).toByteArray();
  }

  /** A login_finished carrying a textures property, comfortably over a 64-byte threshold. */
  private static byte[] loginFinishedPayload() {
    return new Buf().writeUuid(UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"))
        .writeString("Alice").writeVarInt(1).writeString("textures")
        .writeString("eyJ0aW1lc3RhbXAiOjAsInByb2ZpbGVJZCI6IjA2OWE3OWY0IiwicHJvZmlsZU5hbWUiOiJB")
        .writeBoolean(false).toByteArray();
  }

  private static byte[] concat(byte[]... parts) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] part : parts) {
      out.writeBytes(part);
    }
    return out.toByteArray();
  }

  private static byte[] readExactly(Socket socket, int length) throws IOException {
    InputStream in = socket.getInputStream();
    byte[] bytes = in.readNBytes(length);
    assertEquals(length, bytes.length, "bytes received before end of stream");
    return bytes;
  }

  private static void send(Socket socket, byte[] bytes) throws IOException {
    OutputStream out = socket.getOutputStream();
    out.write(bytes);
    out.flush();
  }

  private static void assertEvent(TraceEvent event, Direction dir, Phase phase, int id,
      byte[] payload) {
    assertEquals(dir, event.dir());
    assertEquals(phase, event.phase());
    assertEquals(id, event.id());
    assertArrayEquals(payload, event.payload());
  }

  private TapRelay.Session session() throws InterruptedException {
    return relay.awaitSession(0, Duration.ofSeconds(5)).orElseThrow();
  }

  /** Sends the handshake and login hello through the relay and checks they arrive intact. */
  private TapRelay.Session handshakeAndHello() throws Exception {
    FrameCodec plain = new FrameCodec();
    byte[] wire = concat(
        plain.encode(Buf.packet(HANDSHAKE_INTENTION, handshakePayload())),
        plain.encode(Buf.packet(LOGIN_SB_HELLO, helloPayload())));
    send(client, wire);
    assertArrayEquals(wire, readExactly(backend, wire.length));
    return session();
  }

  @Test
  void forwardsBytesUnchangedAndRecordsPhases() throws Exception {
    TapRelay.Session session = handshakeAndHello();
    assertEquals(0, session.index());
    assertEquals(1, relay.sessions().size());

    // Events are recorded before the frame is forwarded, so they are visible by now.
    List<TraceEvent> serverbound = session.trace().events(Direction.SERVERBOUND);
    assertEquals(2, serverbound.size());
    assertEvent(serverbound.get(0), Direction.SERVERBOUND, Phase.HANDSHAKE, HANDSHAKE_INTENTION,
        handshakePayload());
    assertEvent(serverbound.get(1), Direction.SERVERBOUND, Phase.LOGIN, LOGIN_SB_HELLO,
        helloPayload());
    assertEquals(Phase.LOGIN, session.tracker().phase(Direction.SERVERBOUND));
    assertEquals(Phase.LOGIN, session.tracker().phase(Direction.CLIENTBOUND));
    assertEquals(List.of(), session.trace().events(Direction.CLIENTBOUND));
  }

  @Test
  void decodesFramesAfterLoginCompression() throws Exception {
    final TapRelay.Session session = handshakeAndHello();

    // The backend turns compression on and immediately sends a compressed frame.
    FrameCodec compressed = new FrameCodec();
    compressed.setCompressionThreshold(64);
    byte[] finished = Buf.packet(LOGIN_CB_FINISHED, loginFinishedPayload());
    assertTrue(finished.length >= 64, "login_finished must be over the threshold");
    byte[] finishedFrame = compressed.encode(finished);
    Buf frame = new Buf(finishedFrame);
    frame.readVarInt();
    assertEquals(finished.length, frame.readVarInt(), "the frame is really compressed");
    byte[] clientbound = concat(
        new FrameCodec().encode(Buf.packet(LOGIN_CB_COMPRESSION, new Buf().writeVarInt(64).toByteArray())),
        finishedFrame);
    send(backend, clientbound);
    assertArrayEquals(clientbound, readExactly(client, clientbound.length));

    assertEquals(64, session.tracker().compressionThreshold());
    List<TraceEvent> events = session.trace().events(Direction.CLIENTBOUND);
    assertEquals(2, events.size());
    assertEvent(events.get(0), Direction.CLIENTBOUND, Phase.LOGIN, LOGIN_CB_COMPRESSION,
        new byte[] {64});
    assertEvent(events.get(1), Direction.CLIENTBOUND, Phase.LOGIN, LOGIN_CB_FINISHED,
        loginFinishedPayload());

    // The client's reply is now framed with a data-length header and parsed as such.
    byte[] acknowledged = compressed.encode(Buf.packet(LOGIN_SB_ACKNOWLEDGED, new byte[0]));
    assertArrayEquals(new byte[] {2, 0, LOGIN_SB_ACKNOWLEDGED}, acknowledged);
    send(client, acknowledged);
    assertArrayEquals(acknowledged, readExactly(backend, acknowledged.length));

    List<TraceEvent> serverbound = session.trace().events(Direction.SERVERBOUND);
    assertEquals(3, serverbound.size());
    assertEvent(serverbound.get(2), Direction.SERVERBOUND, Phase.LOGIN, LOGIN_SB_ACKNOWLEDGED,
        new byte[0]);
    assertEquals(Phase.CONFIGURATION, session.tracker().phase(Direction.CLIENTBOUND));
  }
}
