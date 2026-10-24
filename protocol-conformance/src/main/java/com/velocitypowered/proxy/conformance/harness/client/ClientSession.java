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

import static com.velocitypowered.proxy.conformance.harness.wire.Direction.CLIENTBOUND;
import static com.velocitypowered.proxy.conformance.harness.wire.Direction.SERVERBOUND;

import com.velocitypowered.proxy.conformance.harness.auth.FakeSessionService;
import com.velocitypowered.proxy.conformance.harness.auth.MojangHash;
import com.velocitypowered.proxy.conformance.harness.trace.Trace;
import com.velocitypowered.proxy.conformance.harness.trace.TraceEvent;
import com.velocitypowered.proxy.conformance.harness.wire.Buf;
import com.velocitypowered.proxy.conformance.harness.wire.Cfb8;
import com.velocitypowered.proxy.conformance.harness.wire.FrameCodec;
import com.velocitypowered.proxy.conformance.harness.wire.PacketTable;
import com.velocitypowered.proxy.conformance.harness.wire.Phase;
import com.velocitypowered.proxy.conformance.harness.wire.PhaseTracker;
import com.velocitypowered.proxy.conformance.harness.wire.Protocols;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import javax.crypto.Cipher;

/**
 * One scripted client connection. Logs in synchronously, then answers the server on a reader
 * thread the way a vanilla client must (keep-alives, pings, teleports, chunk batches, known packs,
 * phase acknowledgements) while the scenario drives it.
 *
 * <p>Every packet received and sent is recorded, decompressed and decrypted.</p>
 */
public final class ClientSession implements AutoCloseable {

  private static final boolean DEBUG = Boolean.getBoolean("conformance.debug");

  private final Socket socket;
  private final int protocol;
  private final PacketTable table;
  private final PhaseTracker tracker;
  private final Serverbound out;
  private final ClientOptions options;
  private final FrameCodec codec = new FrameCodec();
  private final Trace received = new Trace("client-received");
  private final Trace sent = new Trace("client-sent");
  private final AtomicBoolean closed = new AtomicBoolean();
  private final Object stateLock = new Object();

  private InputStream input;
  private OutputStream output;
  private LoginResult login;
  private volatile String disconnectReason;
  private volatile boolean inPlay;
  private volatile boolean playerLoadedSent;
  private volatile Throwable readerFailure;
  private int sequence;

  private ClientSession(Socket socket, int protocol, PacketTable table, ClientOptions options)
      throws IOException {
    this.socket = socket;
    this.protocol = protocol;
    this.table = table;
    this.tracker = new PhaseTracker(table, protocol);
    this.out = new Serverbound(protocol);
    this.options = options;
    this.input = new BufferedInputStream(socket.getInputStream());
    this.output = socket.getOutputStream();
  }

  /**
   * Connects, logs in, and starts answering the server.
   *
   * @param address the proxy
   * @param protocol the protocol to speak
   * @param table that protocol's packet table
   * @param options client behaviour
   * @param timeout bound on the whole login
   * @return the session, logged in (in configuration or play)
   * @throws IOException if the connection fails
   * @throws LoginRejectedException if the server disconnects the client during login
   */
  public static ClientSession login(InetSocketAddress address, int protocol, PacketTable table,
      ClientOptions options, Duration timeout) throws IOException, LoginRejectedException {
    Socket socket = new Socket();
    socket.setTcpNoDelay(true);
    socket.connect(address, 5000);
    socket.setSoTimeout((int) timeout.toMillis());
    ClientSession session = new ClientSession(socket, protocol, table, options);
    try {
      session.doLogin(address);
    } catch (IOException | LoginRejectedException | RuntimeException e) {
      session.close();
      throw e;
    }
    socket.setSoTimeout(0);
    Thread reader = new Thread(session::readLoop, "client-" + options.name());
    reader.setDaemon(true);
    reader.start();
    return session;
  }

  /**
   * Thrown when the server ends the connection during login, with its reason.
   */
  public static final class LoginRejectedException extends Exception {

    public LoginRejectedException(String reason) {
      super(reason);
    }
  }

  public Trace received() {
    return received;
  }

  public Trace sent() {
    return sent;
  }

  public LoginResult loginResult() {
    return login;
  }

  public Optional<String> disconnectReason() {
    return Optional.ofNullable(disconnectReason);
  }

  public Optional<Throwable> readerFailure() {
    return Optional.ofNullable(readerFailure);
  }

  public boolean isConnected() {
    return !closed.get() && disconnectReason == null;
  }

  public Serverbound packets() {
    return out;
  }

  public Phase phase() {
    return tracker.phase(SERVERBOUND);
  }

  private void doLogin(InetSocketAddress address) throws IOException, LoginRejectedException {
    send(Phase.HANDSHAKE, "minecraft:intention",
        out.intention(address.getHostString(), address.getPort(), 2));
    UUID uuid = options.auth() != null
        ? FakeSessionService.profileFor(options.name()).id()
        : UUID.nameUUIDFromBytes(("OfflinePlayer:" + options.name())
            .getBytes(StandardCharsets.UTF_8));
    send(Phase.LOGIN, "minecraft:hello", out.loginStart(options.name(), uuid));

    boolean encrypted = false;
    while (true) {
      Optional<TraceEvent> next = readOne();
      if (next.isEmpty()) {
        throw new LoginRejectedException(disconnectReason != null ? disconnectReason
            : "connection closed during login");
      }
      TraceEvent e = next.get();
      String name = table.describe(Phase.LOGIN, CLIENTBOUND, e.id());
      Buf buf = new Buf(e.payload());
      switch (name) {
        case "minecraft:login_disconnect" -> {
          disconnectReason = buf.readString();
          throw new LoginRejectedException(disconnectReason);
        }
        case "minecraft:hello" -> {
          encrypt(buf);
          encrypted = true;
        }
        case "minecraft:login_compression" -> codec.setCompressionThreshold(tracker
            .compressionThreshold());
        case "minecraft:custom_query" -> send(Phase.LOGIN, "minecraft:custom_query_answer",
            out.customQueryAnswer(buf.readVarInt()));
        case "minecraft:cookie_request" -> send(Phase.LOGIN, "minecraft:cookie_response",
            out.cookieResponse(buf.readString()));
        case "minecraft:login_finished", "minecraft:game_profile" -> {
          login = parseLoginSuccess(buf, encrypted);
          if (Protocols.hasConfiguration(protocol)) {
            send(Phase.LOGIN, "minecraft:login_acknowledged", new byte[0]);
            onConfigurationStart();
          } else {
            inPlay = true;
          }
          return;
        }
        default -> throw new IOException("unexpected login packet " + name);
      }
    }
  }

  private void encrypt(Buf request) throws IOException {
    request.readString();
    byte[] publicKey;
    byte[] token;
    if (Protocols.atLeast(protocol, Protocols.V1_8)) {
      publicKey = request.readByteArray();
      token = request.readByteArray();
    } else {
      publicKey = request.readBytes(request.readUnsignedShort());
      token = request.readBytes(request.readUnsignedShort());
    }

    byte[] secret = new byte[16];
    new SecureRandom().nextBytes(secret);
    if (DEBUG) {
      System.err.println("[client " + options.name() + "] shared secret "
          + HexFormat.of().formatHex(secret));
    }
    if (options.auth() != null) {
      try {
        options.auth().clientJoin(options.name(), MojangHash.serverId("", secret, publicKey));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException(e);
      }
    }

    try {
      PublicKey key = KeyFactory.getInstance("RSA")
          .generatePublic(new X509EncodedKeySpec(publicKey));
      Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
      rsa.init(Cipher.ENCRYPT_MODE, key);
      byte[] encryptedSecret = rsa.doFinal(secret);
      rsa.init(Cipher.ENCRYPT_MODE, key);
      byte[] encryptedToken = rsa.doFinal(token);
      send(Phase.LOGIN, "minecraft:key", out.key(encryptedSecret, encryptedToken));

      synchronized (stateLock) {
        input = Cfb8.decrypting(input, secret);
        output = Cfb8.encrypting(output, secret);
      }
    } catch (GeneralSecurityException e) {
      throw new IOException("encryption setup failed", e);
    }
  }

  private LoginResult parseLoginSuccess(Buf buf, boolean encrypted) {
    UUID uuid;
    if (Protocols.atLeast(protocol, Protocols.V1_16)) {
      uuid = buf.readUuid();
    } else {
      String text = buf.readString();
      uuid = UUID.fromString(text.length() == 32
          ? text.replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5")
          : text);
    }
    String name = buf.readString();
    List<String> properties = new ArrayList<>();
    if (Protocols.atLeast(protocol, Protocols.V1_19)) {
      int count = buf.readVarInt();
      for (int i = 0; i < count; i++) {
        String key = buf.readString();
        String value = buf.readString();
        if (buf.readBoolean()) {
          buf.readString();
        }
        properties.add(key + "=" + value);
      }
    }
    return new LoginResult(uuid, name, properties, encrypted, tracker.compressionThreshold());
  }

  private void onConfigurationStart() throws IOException {
    // A vanilla client announces its brand and options as soon as configuration begins (or, before
    // 1.20.2, as soon as it joins).
    send(Phase.CONFIGURATION, "minecraft:custom_payload", out.brand());
    send(Phase.CONFIGURATION, "minecraft:client_information", out.clientInformation());
  }

  private void readLoop() {
    try {
      while (!closed.get()) {
        Optional<TraceEvent> next = readOne();
        if (next.isEmpty()) {
          break;
        }
        react(next.get());
      }
    } catch (IOException | RuntimeException e) {
      if (!closed.get()) {
        readerFailure = e;
      }
    } finally {
      closed.set(true);
      synchronized (this) {
        notifyAll();
      }
    }
  }

  private void react(TraceEvent e) throws IOException {
    Phase phase = e.phase();
    String name = table.describe(phase, CLIENTBOUND, e.id());
    Buf buf = new Buf(e.payload());
    if (phase == Phase.CONFIGURATION) {
      switch (name) {
        case "minecraft:keep_alive" -> send(phase, "minecraft:keep_alive", e.payload());
        case "minecraft:ping" -> send(phase, "minecraft:pong", e.payload());
        case "minecraft:select_known_packs" -> send(phase, "minecraft:select_known_packs",
            options.echoKnownPacks() ? e.payload() : new Buf().writeVarInt(0).toByteArray());
        case "minecraft:cookie_request" -> send(phase, "minecraft:cookie_response",
            out.cookieResponse(buf.readString()));
        case "minecraft:resource_pack_push" -> answerResourcePack(phase, buf);
        case "minecraft:code_of_conduct" ->
            send(phase, "minecraft:accept_code_of_conduct", new byte[0]);
        case "minecraft:finish_configuration" -> {
          send(phase, "minecraft:finish_configuration", new byte[0]);
          inPlay = true;
        }
        case "minecraft:disconnect" -> disconnected(e);
        default -> {
        }
      }
      return;
    }
    if (phase != Phase.PLAY) {
      return;
    }
    switch (name) {
      case "minecraft:keep_alive" -> send(phase, "minecraft:keep_alive", e.payload());
      case "minecraft:ping" -> send(phase, "minecraft:pong", e.payload());
      case "minecraft:login" -> {
        if (!Protocols.hasConfiguration(protocol)) {
          send(phase, "minecraft:custom_payload", out.brand());
          send(phase, "minecraft:client_information", out.clientInformation());
        }
      }
      case "minecraft:player_position" -> onPosition(buf);
      case "minecraft:chunk_batch_finished" ->
          send(phase, "minecraft:chunk_batch_received", out.chunkBatchReceived());
      case "minecraft:start_configuration" -> {
        inPlay = false;
        send(phase, "minecraft:configuration_acknowledged", new byte[0]);
        onConfigurationStart();
      }
      case "minecraft:set_health" -> {
        if (buf.readFloat() <= 0f) {
          send(phase, "minecraft:client_command", out.performRespawn());
        }
      }
      case "minecraft:resource_pack_push" -> answerResourcePack(phase, buf);
      case "minecraft:cookie_request" -> send(phase, "minecraft:cookie_response",
          out.cookieResponse(buf.readString()));
      case "minecraft:disconnect" -> disconnected(e);
      default -> {
      }
    }
  }

  private void onPosition(Buf buf) throws IOException {
    if (!out.protocolHasTeleportIds()) {
      return;
    }
    int teleportId;
    double x;
    double y;
    double z;
    if (Protocols.atLeast(protocol, Protocols.V1_21_2)) {
      teleportId = buf.readVarInt();
      x = buf.readDouble();
      y = buf.readDouble();
      z = buf.readDouble();
    } else {
      x = buf.readDouble();
      y = buf.readDouble();
      z = buf.readDouble();
      buf.skip(4 + 4 + 1);
      teleportId = buf.readVarInt();
    }
    send(Phase.PLAY, "minecraft:accept_teleportation", out.acceptTeleportation(teleportId));
    send(Phase.PLAY, "minecraft:move_player_pos_rot", out.movePlayerPosRot(x, y, z, 0f, 0f));
    if (!playerLoadedSent && table.id(Phase.PLAY, SERVERBOUND, "minecraft:player_loaded")
        .isPresent()) {
      playerLoadedSent = true;
      send(Phase.PLAY, "minecraft:player_loaded", new byte[0]);
    }
  }

  private void answerResourcePack(Phase phase, Buf buf) throws IOException {
    UUID id = Protocols.atLeast(protocol, Protocols.V1_20_3) ? buf.readUuid() : null;
    // Declined: the harness has nowhere to download a pack to, and declining is a legal answer.
    send(phase, "minecraft:resource_pack", out.resourcePack(id, 1));
  }

  private void disconnected(TraceEvent e) {
    disconnectReason = describeReason(e.payload());
    close();
  }

  private static String describeReason(byte[] payload) {
    // JSON text before 1.20.3, NBT after; either way the readable part is printable ASCII.
    StringBuilder sb = new StringBuilder();
    for (byte b : payload) {
      sb.append(b >= 0x20 && b < 0x7F ? (char) b : '.');
    }
    return sb.toString().replaceAll("\\.{2,}", " ").trim();
  }

  /**
   * Waits until the client has reached play.
   *
   * @param timeout how long to wait
   * @return whether it did
   * @throws InterruptedException if interrupted
   */
  public boolean awaitPlay(Duration timeout) throws InterruptedException {
    return awaitReceived(e -> e.phase() == Phase.PLAY, timeout).isPresent();
  }

  /**
   * Waits for a clientbound packet whose payload contains {@code token}.
   *
   * @param token the marker text
   * @param timeout how long to wait
   * @return the index of the packet in {@link #received()}, if it arrived
   * @throws InterruptedException if interrupted
   */
  public OptionalInt awaitMarker(String token, Duration timeout) throws InterruptedException {
    byte[] needle = token.getBytes(StandardCharsets.UTF_8);
    Optional<TraceEvent> found = awaitReceived(e -> contains(e.payload(), needle), timeout);
    if (found.isEmpty()) {
      return OptionalInt.empty();
    }
    return OptionalInt.of(received.events().indexOf(found.get()));
  }

  /**
   * Waits for a received packet matching {@code predicate}.
   *
   * @param predicate what to wait for
   * @param timeout how long to wait
   * @return the first match
   * @throws InterruptedException if interrupted
   */
  public Optional<TraceEvent> awaitReceived(Predicate<TraceEvent> predicate, Duration timeout)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (true) {
      for (TraceEvent e : received.events()) {
        if (predicate.test(e)) {
          return Optional.of(e);
        }
      }
      if (closed.get() || System.nanoTime() > deadline) {
        return Optional.empty();
      }
      Thread.sleep(25);
    }
  }

  /**
   * Returns whether {@code haystack} contains {@code needle}.
   *
   * @param haystack the bytes to search
   * @param needle the bytes to find
   * @return whether found
   */
  public static boolean contains(byte[] haystack, byte[] needle) {
    outer:
    for (int i = 0; i <= haystack.length - needle.length; i++) {
      for (int j = 0; j < needle.length; j++) {
        if (haystack[i + j] != needle[j]) {
          continue outer;
        }
      }
      return true;
    }
    return false;
  }

  /**
   * Returns whether this version has a serverbound packet called {@code name} in the current
   * phase.
   *
   * @param name the Mojang packet name
   * @return whether it exists
   */
  public boolean has(String name) {
    return table.id(tracker.phase(SERVERBOUND), SERVERBOUND, name).isPresent();
  }

  public synchronized int nextSequence() {
    return ++sequence;
  }

  /**
   * Sends a packet by name in the current phase.
   *
   * @param name the Mojang packet name
   * @param payload its body
   * @throws IOException if the version has no such packet or the write fails
   */
  public void send(String name, byte[] payload) throws IOException {
    send(tracker.phase(SERVERBOUND), name, payload);
  }

  private void send(Phase phase, String name, byte[] payload) throws IOException {
    OptionalInt id = table.id(phase, SERVERBOUND, name);
    if (id.isEmpty()) {
      throw new IOException("no serverbound " + name + " in " + phase + " for protocol "
          + protocol);
    }
    synchronized (stateLock) {
      if (closed.get()) {
        throw new IOException("connection closed");
      }
      if (tracker.phase(SERVERBOUND) != phase) {
        throw new IOException("cannot send " + name + " in " + phase + ": connection is in "
            + tracker.phase(SERVERBOUND));
      }
      final byte[] packet = Buf.packet(id.getAsInt(), payload);
      sent.add(SERVERBOUND, phase, id.getAsInt(), payload);
      if (DEBUG) {
        System.err.println("[client " + options.name() + "] -> " + phase + " " + name + " ("
            + payload.length + " bytes)");
      }
      tracker.observe(SERVERBOUND, id.getAsInt(), payload);
      output.write(codec.encode(packet));
      output.flush();
    }
  }

  private Optional<TraceEvent> readOne() throws IOException {
    InputStream in;
    synchronized (stateLock) {
      in = input;
    }
    Optional<byte[]> packet;
    try {
      packet = codec.decode(in);
    } catch (IOException e) {
      if (closed.get()) {
        return Optional.empty();
      }
      throw e;
    }
    if (packet.isEmpty()) {
      return Optional.empty();
    }
    Buf buf = new Buf(packet.get());
    int id = buf.readVarInt();
    byte[] payload = buf.readRemaining();
    Phase phase = tracker.phase(CLIENTBOUND);
    TraceEvent event = new TraceEvent(System.nanoTime(), CLIENTBOUND, phase, id, payload);
    received.add(event);
    if (DEBUG) {
      System.err.println("[client " + options.name() + "] <- " + phase + " "
          + table.describe(phase, CLIENTBOUND, id) + " (" + payload.length + " bytes)");
    }
    tracker.observe(CLIENTBOUND, id, payload);
    return Optional.of(event);
  }

  public boolean inPlay() {
    return inPlay;
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      try {
        socket.close();
      } catch (IOException ignored) {
        // Already closed.
      }
    }
  }
}
