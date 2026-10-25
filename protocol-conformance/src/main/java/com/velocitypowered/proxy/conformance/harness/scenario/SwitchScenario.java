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

package com.velocitypowered.proxy.conformance.harness.scenario;

import com.velocitypowered.proxy.conformance.harness.HarnessException;
import com.velocitypowered.proxy.conformance.harness.ManagedProcess;
import com.velocitypowered.proxy.conformance.harness.client.ClientOptions;
import com.velocitypowered.proxy.conformance.harness.client.ClientSession;
import com.velocitypowered.proxy.conformance.harness.client.TransitionPoint;
import com.velocitypowered.proxy.conformance.harness.diff.Differ;
import com.velocitypowered.proxy.conformance.harness.diff.Finding;
import com.velocitypowered.proxy.conformance.harness.diff.RuleContext;
import com.velocitypowered.proxy.conformance.harness.proxy.ProxyUnderTest;
import com.velocitypowered.proxy.conformance.harness.tap.TapRelay;
import com.velocitypowered.proxy.conformance.harness.trace.Trace;
import com.velocitypowered.proxy.conformance.harness.trace.TraceEvent;
import com.velocitypowered.proxy.conformance.harness.vanilla.VanillaServer;
import com.velocitypowered.proxy.conformance.harness.wire.Buf;
import com.velocitypowered.proxy.conformance.harness.wire.Direction;
import com.velocitypowered.proxy.conformance.harness.wire.Phase;
import com.velocitypowered.proxy.conformance.harness.wire.Protocols;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * Moves a player between two vanilla backends through the proxy and checks the transition: every
 * backend session is transparent, every probe payload the client sends during the transition
 * reaches exactly one backend (the new one, in its configuration phase, when sent during
 * configuration), the new backend receives the client's brand and settings, and the client is
 * never handed a configuration-starting packet inside an open bundle.
 */
public final class SwitchScenario implements Scenario {

  private static final Duration SWITCH_TIMEOUT = Duration.ofSeconds(45);
  private static final Duration SETTLE = Duration.ofMillis(1500);

  private final List<byte[]> proxyKeepAlives = new ArrayList<>();
  private long switchLeftAt = Long.MAX_VALUE;
  private final String id;
  private final String player;
  private final List<String> hops;
  private final boolean rapid;

  // Each scenario logs in under its own name: the proxy may not have finished disconnecting the
  // previous scenario's player when the next one connects.
  private SwitchScenario(String id, String player, List<String> hops, boolean rapid) {
    this.id = id;
    this.player = player;
    this.hops = hops;
    this.rapid = rapid;
  }

  /**
   * A single switch from the primary backend to the secondary one.
   *
   * @return the scenario
   */
  public static SwitchScenario basic() {
    return new SwitchScenario("switch/basic", "ConfSwitch", List.of(ProxyUnderTest.SECONDARY), false);
  }

  /**
   * There and back again, so the primary backend sees a second session.
   *
   * @return the scenario
   */
  public static SwitchScenario roundTrip() {
    return new SwitchScenario("switch/round-trip", "ConfRound",
        List.of(ProxyUnderTest.SECONDARY, ProxyUnderTest.PRIMARY), false);
  }

  /**
   * There and straight back the moment the new join game arrives.
   *
   * @return the scenario
   */
  public static SwitchScenario rapid() {
    return new SwitchScenario("switch/rapid", "ConfRapid",
        List.of(ProxyUnderTest.SECONDARY, ProxyUnderTest.PRIMARY), true);
  }

  @Override
  public String id() {
    return id;
  }

  @Override
  public boolean onlineMode() {
    return false;
  }

  @Override
  public boolean needsSecondBackend() {
    return true;
  }

  @Override
  public boolean comparesTraffic() {
    return true;
  }

  /** A probe payload the client sent, and when. */
  private record Probe(String token, String point, int epoch, Phase phase) {
  }

  /** Sends a probe custom payload at every transition point. */
  private static final class Probes implements TransitionPoint.Listener {
    private final List<Probe> sent = new CopyOnWriteArrayList<>();
    private volatile int epoch;
    private int joins;

    @Override
    public void on(TransitionPoint point, ClientSession session) throws IOException {
      if (point == TransitionPoint.START_CONFIGURATION) {
        // Still talking to the old server; the epoch moves on once configuration is entered.
        send(session, point.name(), epoch);
        epoch++;
        return;
      }
      if (point == TransitionPoint.JOIN_GAME) {
        // Without a configuration phase (before 1.20.2) a new join game is the new epoch.
        boolean hasConfiguration = session.has("minecraft:configuration_acknowledged");
        if (!hasConfiguration && joins++ > 0) {
          epoch++;
        }
      }
      send(session, point.name(), epoch);
    }

    void send(ClientSession session, String point, int atEpoch) throws IOException {
      if (!session.has("minecraft:custom_payload")) {
        return;
      }
      String token = "probe-" + point + "-" + UUID.randomUUID();
      Phase phase = session.phase();
      session.send("minecraft:custom_payload", session.packets().customPayload(
          session.packets().markerChannel(), token.getBytes(StandardCharsets.UTF_8)));
      sent.add(new Probe(token, point, atEpoch, phase));
    }
  }

  @Override
  public ScenarioOutput run(ScenarioContext ctx) throws HarnessException, InterruptedException {
    ScenarioOutput out = new ScenarioOutput();
    if (ctx.tapB() == null || ctx.serverB() == null) {
      throw new HarnessException("switch scenario run without a second backend");
    }
    final int proxyLines = ctx.proxy().lineCount();
    final int primaryLines = ctx.server().process().lineCount();
    final int secondaryLines = ctx.serverB().process().lineCount();
    int firstA = ctx.tap().sessions().size();
    int firstB = ctx.tapB().sessions().size();
    boolean configuration = Protocols.hasConfiguration(ctx.version().protocol());

    Probes probes = new Probes();
    ClientSession client;
    try {
      client = ClientSession.login(ctx.proxyAddress(), ctx.version().protocol(), ctx.table(),
          new ClientOptions(player, null, true, probes), SessionScenario.LOGIN_TIMEOUT);
    } catch (ClientSession.LoginRejectedException e) {
      out.fail("CHECK login.rejected", e.getMessage());
      out.abort();
      return out;
    } catch (IOException e) {
      throw new HarnessException("cannot connect to the proxy", e);
    }

    // Which backend each epoch (initial join, then one per hop) talked to, and when the switch
    // away from it was requested.
    List<String> epochServers = new ArrayList<>();
    epochServers.add(ProxyUnderTest.PRIMARY);
    List<Long> switchRequested = new ArrayList<>();
    try (client) {
      if (!client.awaitPlay(SessionScenario.LOGIN_TIMEOUT)
          || !client.awaitJoins(1, SessionScenario.LOGIN_TIMEOUT)) {
        out.fail("CHECK client.play", "never reached play on the first backend: "
            + client.disconnectReason().orElse("no disconnect reason"));
        out.abort();
        return out;
      }
      Thread.sleep(SETTLE.toMillis());

      try {
        probes.send(client, "BEFORE_SWITCH", 0);
        for (int hop = 0; hop < hops.size(); hop++) {
          int joins = client.joins();
          TapRelay target = hops.get(hop).equals(ProxyUnderTest.PRIMARY) ? ctx.tap() : ctx.tapB();
          int targetSessions = target.sessions().size();
          switchRequested.add(System.nanoTime());
          String command = "server " + hops.get(hop);
          if (client.packets().hasChatCommandPacket()) {
            client.send("minecraft:chat_command", client.packets().chatCommand(command));
          } else {
            client.send("minecraft:chat", client.packets().chat("/" + command));
          }
          if (!client.awaitJoins(joins + 1, SWITCH_TIMEOUT)) {
            // Tell "the proxy never acted on the command" apart from "the switch stalled".
            String signature = target.sessions().size() == targetSessions
                ? "CHECK switch.ignored" : "CHECK switch.complete";
            out.fail(signature, "switch " + (hop + 1) + " to " + hops.get(hop)
                + " did not complete within " + SWITCH_TIMEOUT.toSeconds() + "s (client in "
                + client.phase() + client.disconnectReason().map(r -> ", disconnected: " + r)
                    .orElse("") + ")");
            out.abort();
            break;
          }
          epochServers.add(hops.get(hop));
          if (!rapid || hop == hops.size() - 1) {
            Thread.sleep(SETTLE.toMillis());
          }
        }
      } catch (IOException e) {
        out.fail("CHECK client.send", "could not send: " + e.getMessage());
      }

      List<TapRelay.Session> sessions = sessions(ctx, epochServers, firstA, firstB, out);
      if (sessions != null && out.failures().stream()
          .noneMatch(f -> f.signature().equals("CHECK switch.complete"))) {
        TapRelay.Session last = sessions.getLast();
        VanillaServer lastServer = epochServers.getLast().equals(ProxyUnderTest.PRIMARY)
            ? ctx.server() : ctx.serverB();
        int[] cuts = synchronise(ctx, client, last, lastServer, player, out);
        analyse(ctx, client, sessions, epochServers, switchRequested, probes, cuts, configuration,
            out);
      }
      client.disconnectReason().ifPresent(reason ->
          out.fail("CHECK client.disconnected", "disconnected mid-session: " + reason));
      client.readerFailure().ifPresent(failure ->
          out.fail("CHECK client.decode", "could not read the proxy's stream: " + failure));
    }

    checkLog(ctx.proxy(), proxyLines, SessionScenario.PROXY_TROUBLE, "CHECK proxy.log", out);
    checkLog(ctx.server().process(), primaryLines, SessionScenario.BACKEND_TROUBLE,
        "CHECK backend.log", out);
    checkLog(ctx.serverB().process(), secondaryLines, SessionScenario.BACKEND_TROUBLE,
        "CHECK backend.log", out);
    for (VanillaServer server : List.of(ctx.server(), ctx.serverB())) {
      if (!server.process().isAlive()
          || server.process().hasLine(l -> l.contains("This crash report has been saved"))) {
        throw new HarnessException("a vanilla server crashed during the scenario:\n"
            + server.process().tail(20));
      }
    }
    return out;
  }

  private static List<TapRelay.Session> sessions(ScenarioContext ctx, List<String> epochServers,
      int firstA, int firstB, ScenarioOutput out) throws InterruptedException {
    List<TapRelay.Session> sessions = new ArrayList<>();
    int nextA = firstA;
    int nextB = firstB;
    for (String server : epochServers) {
      boolean primary = server.equals(ProxyUnderTest.PRIMARY);
      TapRelay tap = primary ? ctx.tap() : ctx.tapB();
      Optional<TapRelay.Session> session = tap.awaitSession(primary ? nextA++ : nextB++,
          SessionScenario.LOGIN_TIMEOUT);
      if (session.isEmpty()) {
        out.fail("CHECK backend.connect", "the proxy never connected to " + server);
        out.abort();
        return null;
      }
      sessions.add(session.get());
    }
    return sessions;
  }

  /**
   * Cuts the final epoch at sync markers, as the session scenarios do.
   *
   * @return {client sent cut, final backend serverbound cut, final backend clientbound cut,
   *     client received cut}, -1 where a marker was not seen
   */
  private static int[] synchronise(ScenarioContext ctx, ClientSession client,
      TapRelay.Session last, VanillaServer lastServer, String player, ScenarioOutput out)
      throws HarnessException, InterruptedException {
    int[] cuts = {-1, -1, -1, -1};
    if (!client.isConnected()) {
      return cuts;
    }
    String sb = "sb-" + UUID.randomUUID();
    String cb = "cb-" + UUID.randomUUID();
    try {
      client.send("minecraft:custom_payload", client.packets().customPayload(
          client.packets().markerChannel(), sb.getBytes(StandardCharsets.UTF_8)));
      cuts[0] = SessionScenario.indexOf(client.sent().events(Direction.SERVERBOUND), sb);
      cuts[1] = SessionScenario.awaitIndex(last.trace(), Direction.SERVERBOUND, sb);
      if (cuts[1] < 0) {
        out.fail("CHECK sync.serverbound", "the serverbound marker never reached the backend");
      }
    } catch (IOException e) {
      out.fail("CHECK client.send", "could not send the serverbound marker: " + e.getMessage());
    }
    lastServer.command(RconScript.tellraw(ctx.version(), player, cb));
    cuts[2] = SessionScenario.awaitIndex(last.trace(), Direction.CLIENTBOUND, cb);
    if (client.awaitMarker(cb, SessionScenario.MARKER_TIMEOUT).isPresent()) {
      cuts[3] = SessionScenario.indexOf(client.received().events(Direction.CLIENTBOUND), cb);
    } else {
      out.fail("CHECK sync.clientbound", "the clientbound marker never reached the client");
    }
    return cuts;
  }

  private void analyse(ScenarioContext ctx, ClientSession client, List<TapRelay.Session> sessions,
      List<String> epochServers, List<Long> switchRequested, Probes probes, int[] cuts,
      boolean configuration, ScenarioOutput out) {
    List<TraceEvent> received = cut(client.received().events(Direction.CLIENTBOUND), cuts[3]);
    List<TraceEvent> sent = cut(client.sent().events(Direction.SERVERBOUND), cuts[0]);
    checkBundles(ctx, received, out);

    List<byte[]> replay = new ArrayList<>();
    for (TraceEvent e : sent) {
      String name = ctx.table().describe(e.phase(), Direction.SERVERBOUND, e.id());
      if (name.equals("minecraft:client_information") || (name.equals("minecraft:custom_payload")
          && "minecraft:brand".equals(channelOf(e.payload())))) {
        replay.add(e.payload());
      }
    }
    // Keep-alives the client received that no backend sent are the proxy's own, sent while
    // switching; the client's answers to them are the proxy's to consume.
    proxyKeepAlives.clear();
    for (TraceEvent e : received) {
      if (isKeepAlive(ctx, Direction.CLIENTBOUND, e) && sessions.stream().noneMatch(session ->
          session.trace().events(Direction.CLIENTBOUND).stream().anyMatch(b -> b.sameContent(e)))) {
        proxyKeepAlives.add(e.payload());
      }
    }
    List<List<TraceEvent>> receivedEpochs = splitReceived(ctx, received, configuration);
    List<List<TraceEvent>> sentEpochs = splitSent(ctx, client, sent, received, configuration);
    List<Finding> all = new ArrayList<>();
    for (int epoch = 0; epoch < sessions.size(); epoch++) {
      boolean last = epoch == sessions.size() - 1;
      Trace trace = sessions.get(epoch).trace();
      List<TraceEvent> source = SessionScenario.window(
          cut(trace.events(Direction.CLIENTBOUND), last ? cuts[2] : -1), -1);
      List<TraceEvent> sink = epoch < receivedEpochs.size() ? receivedEpochs.get(epoch)
          : List.of();
      long leftAt = last ? Long.MAX_VALUE : switchRequested.get(epoch);
      all.addAll(compare(ctx, Direction.CLIENTBOUND, epoch, last, epochServers.get(epoch), source,
          sink, leftAt, probes, replay, out));

      List<TraceEvent> clientSent = epoch < sentEpochs.size() ? sentEpochs.get(epoch) : List.of();
      List<TraceEvent> backendGot = SessionScenario.window(
          cut(trace.events(Direction.SERVERBOUND), last ? cuts[1] : -1), -1);
      switchLeftAt = leftAt;
      all.addAll(compare(ctx, Direction.SERVERBOUND, epoch, last, epochServers.get(epoch),
          clientSent,
          backendGot, Long.MAX_VALUE, probes, replay, out));

      if (epoch > 0 && configuration) {
        checkReplay(ctx, client, epoch, epochServers.get(epoch), trace, out);
      }
    }
    checkProbes(ctx, sessions, epochServers, probes, configuration, out);
    writeTraces(ctx, client, sessions, epochServers, all);
  }

  private List<Finding> compare(ScenarioContext ctx, Direction dir, int epoch, boolean lastEpoch,
      String server, List<TraceEvent> source, List<TraceEvent> sink, long leftAt, Probes probes,
      List<byte[]> replay, ScenarioOutput out) {
    RuleContext rc = new RuleContext(dir, ctx.table(), ctx.version().protocol(),
        ctx.argumentTypes());
    Differ.Result result = Differ.compare(rc, source, sink, ctx.rules());
    out.addMatched(result.matched());
    ctx.coverage().record(source);
    String where = "epoch " + epoch + " (" + server + ")";
    for (Finding f : result.findings()) {
      TraceEvent event = f.sourceIndex() >= 0 ? source.get(f.sourceIndex())
          : sink.get(f.sinkIndex());
      if (isProbe(event, probes) || isTransitionControl(ctx, dir, event)) {
        // Probes are judged by checkProbes; the switch command and acknowledgements are the
        // proxy's to consume.
        continue;
      }
      Optional<String> switchRule = Optional.empty();
      if (f.kind() == Finding.Kind.INJECTED) {
        // An epoch in the middle of a round trip is both entered and left by a switch.
        if (epoch > 0) {
          switchRule = ctx.rules().tolerateSwitchInjection(rc, event, true);
        }
        if (switchRule.isEmpty() && !lastEpoch) {
          switchRule = ctx.rules().tolerateSwitchInjection(rc, event, false);
        }
      } else if (f.kind() == Finding.Kind.MISMATCH) {
        switchRule = ctx.rules().tolerateSwitchMismatch(rc, source.get(f.sourceIndex()),
            sink.get(f.sinkIndex()), epoch > 0);
      }
      if (switchRule.isPresent()) {
        out.tolerate(where + " " + f.packet() + ": " + switchRule.get());
      } else if (f.kind() == Finding.Kind.TOLERATED) {
        out.tolerate(where + " " + f.packet() + ": " + f.detail());
      } else if (f.kind() == Finding.Kind.INJECTED && dir == Direction.CLIENTBOUND
          && isProxyKeepAlive(ctx, dir, event)) {
        out.tolerate(where + " the proxy sends its own keep-alive while switching");
      } else if (f.kind() == Finding.Kind.MISMATCH && dir == Direction.CLIENTBOUND
          && isProxyKeepAlive(ctx, dir, sink.get(f.sinkIndex()))) {
        // Not a rewrite: the proxy's keep-alive happened to line up with one of the old
        // server's that was never delivered.
        out.tolerate(where + " the proxy sends its own keep-alive while switching");
        TraceEvent backendKeepAlive = source.get(f.sourceIndex());
        if (backendKeepAlive.nanos() >= leftAt) {
          out.tolerate(where + " " + f.packet() + ": sent by the old server after the switch "
              + "was requested; the client can no longer accept it");
        } else {
          out.fail("DROPPED " + f.dir().arrow() + " " + f.phase() + " " + f.packet(),
              where + " src#" + f.sourceIndex() + " sent but never delivered");
        }
      } else if (f.kind() == Finding.Kind.DROPPED && dir == Direction.SERVERBOUND
          && isProxyKeepAlive(ctx, Direction.CLIENTBOUND, event)) {
        out.tolerate(where + " the client's answer to the proxy's own keep-alive is consumed");
      } else if (f.kind() == Finding.Kind.DROPPED && dir == Direction.SERVERBOUND
          && isKeepAlive(ctx, dir, event) && event.nanos() >= switchLeftAt) {
        out.tolerate(where + " the client's answer to a keep-alive from the server it is leaving "
            + "was sent after the switch was requested; that server is gone");
      } else if (f.kind() == Finding.Kind.INJECTED && dir == Direction.SERVERBOUND
          && epoch > 0 && isReplay(ctx, event, replay)) {
        out.tolerate(where + " " + f.packet() + ": the proxy replays the client's "
            + "brand/settings to the new backend (checked by replay.*)");
      } else if (f.kind() == Finding.Kind.DROPPED && dir == Direction.CLIENTBOUND
          && event.nanos() >= leftAt) {
        out.tolerate(where + " " + f.packet() + ": sent by the old server after the switch was "
            + "requested; the client can no longer accept it");
      } else {
        out.fail(f.kind() + " " + f.dir().arrow() + " " + f.phase() + " " + f.packet(),
            where + " src#" + f.sourceIndex() + " sink#" + f.sinkIndex() + " " + f.detail());
      }
    }
    return result.findings();
  }

  private static boolean isKeepAlive(ScenarioContext ctx, Direction dir, TraceEvent event) {
    return ctx.table().describe(event.phase(), dir, event.id()).equals("minecraft:keep_alive");
  }

  /**
   * Whether a keep-alive (or the client's answer to one, which echoes the same body) came from
   * the proxy rather than a backend.
   */
  private boolean isProxyKeepAlive(ScenarioContext ctx, Direction dir, TraceEvent event) {
    return isKeepAlive(ctx, event.dir(), event)
        && proxyKeepAlives.stream().anyMatch(k -> Arrays.equals(k, event.payload()));
  }

  private static boolean isReplay(ScenarioContext ctx, TraceEvent event, List<byte[]> replay) {
    String name = ctx.table().describe(event.phase(), Direction.SERVERBOUND, event.id());
    return (name.equals("minecraft:client_information")
        || name.equals("minecraft:custom_payload"))
        && replay.stream().anyMatch(r -> Arrays.equals(r, event.payload()));
  }

  private static boolean isProbe(TraceEvent event, Probes probes) {
    byte[] prefix = "probe-".getBytes(StandardCharsets.UTF_8);
    return ClientSession.contains(event.payload(), prefix);
  }

  private static boolean isTransitionControl(ScenarioContext ctx, Direction dir,
      TraceEvent event) {
    String name = ctx.table().describe(event.phase(), dir, event.id());
    if (dir == Direction.CLIENTBOUND) {
      return name.equals("minecraft:start_configuration");
    }
    if (name.equals("minecraft:configuration_acknowledged")) {
      return true;
    }
    return (name.equals("minecraft:chat_command") || name.equals("minecraft:chat"))
        && ClientSession.contains(event.payload(),
            ("server " + ProxyUnderTest.PRIMARY).getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Splits what the client received into one list per backend session: at each configuration
   * start (1.20.2+), or at each join game after the first (before).
   */
  private static List<List<TraceEvent>> splitReceived(ScenarioContext ctx,
      List<TraceEvent> received, boolean configuration) {
    List<List<TraceEvent>> epochs = new ArrayList<>();
    List<TraceEvent> current = new ArrayList<>();
    boolean joined = false;
    for (TraceEvent e : received) {
      String name = ctx.table().describe(e.phase(), Direction.CLIENTBOUND, e.id());
      boolean boundary = configuration
          ? name.equals("minecraft:start_configuration")
          : e.phase() == Phase.PLAY && name.equals("minecraft:login") && joined;
      if (boundary) {
        epochs.add(current);
        current = new ArrayList<>();
      }
      if (e.phase() == Phase.PLAY && name.equals("minecraft:login")) {
        joined = true;
      }
      if (e.phase() == Phase.CONFIGURATION || e.phase() == Phase.PLAY) {
        current.add(e);
      }
    }
    epochs.add(current);
    return epochs;
  }

  /**
   * Splits what the client sent the same way: after each configuration acknowledgement
   * (1.20.2+), or after each join game following the first (before).
   */
  private static List<List<TraceEvent>> splitSent(ScenarioContext ctx, ClientSession client,
      List<TraceEvent> sent, List<TraceEvent> received, boolean configuration) {
    List<Long> boundaries = new ArrayList<>();
    if (configuration) {
      for (TraceEvent e : sent) {
        if (e.phase() == Phase.PLAY && ctx.table().is(Phase.PLAY, Direction.SERVERBOUND, e.id(),
            "minecraft:configuration_acknowledged")) {
          boundaries.add(e.nanos());
        }
      }
    } else {
      boolean first = true;
      for (TraceEvent e : received) {
        if (e.phase() == Phase.PLAY && ctx.table().is(Phase.PLAY, Direction.CLIENTBOUND, e.id(),
            "minecraft:login")) {
          if (!first) {
            boundaries.add(e.nanos());
          }
          first = false;
        }
      }
    }
    List<List<TraceEvent>> epochs = new ArrayList<>();
    List<TraceEvent> current = new ArrayList<>();
    int next = 0;
    for (TraceEvent e : sent) {
      while (next < boundaries.size() && e.nanos() > boundaries.get(next)) {
        epochs.add(current);
        current = new ArrayList<>();
        next++;
      }
      if (e.phase() == Phase.CONFIGURATION || e.phase() == Phase.PLAY) {
        current.add(e);
      }
    }
    epochs.add(current);
    return epochs;
  }

  /**
   * A vanilla client does not resend its brand or settings when it re-enters configuration, so
   * the proxy has to: the new backend must see both, byte-identical to what the client last sent.
   */
  private static void checkReplay(ScenarioContext ctx, ClientSession client, int epoch,
      String server, Trace trace, ScenarioOutput out) {
    List<TraceEvent> sent = client.sent().events(Direction.SERVERBOUND);
    List<TraceEvent> got = trace.events(Direction.SERVERBOUND);
    byte[] settings = null;
    byte[] brand = null;
    for (TraceEvent e : sent) {
      String name = ctx.table().describe(e.phase(), Direction.SERVERBOUND, e.id());
      if (name.equals("minecraft:client_information")) {
        settings = e.payload();
      } else if (name.equals("minecraft:custom_payload")
          && "minecraft:brand".equals(channelOf(e.payload()))) {
        brand = e.payload();
      }
    }
    String where = "epoch " + epoch + " (" + server + ")";
    if (settings != null && !contains(ctx, got, "minecraft:client_information", settings)) {
      out.fail("CHECK replay.client_information", where + ": the new backend never received the "
          + "client's settings (skin layers, view distance, locale)");
    }
    if (brand != null && !contains(ctx, got, "minecraft:custom_payload", brand)) {
      out.fail("CHECK replay.brand", where + ": the new backend never received the client's "
          + "brand");
    }
  }

  private static boolean contains(ScenarioContext ctx, List<TraceEvent> events, String name,
      byte[] payload) {
    for (TraceEvent e : events) {
      if (ctx.table().describe(e.phase(), Direction.SERVERBOUND, e.id()).equals(name)
          && Arrays.equals(e.payload(), payload)) {
        return true;
      }
    }
    return false;
  }

  private static String channelOf(byte[] payload) {
    try {
      return new Buf(payload).readString();
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * Every probe must reach exactly one backend; one sent during configuration must reach the
   * backend being configured, during its configuration phase; one sent after must reach it in
   * play.
   */
  private static void checkProbes(ScenarioContext ctx, List<TapRelay.Session> sessions,
      List<String> epochServers, Probes probes, boolean configuration, ScenarioOutput out) {
    for (Probe probe : probes.sent) {
      if (probe.epoch() >= sessions.size()) {
        continue;
      }
      byte[] token = probe.token().getBytes(StandardCharsets.UTF_8);
      List<String> seen = new ArrayList<>();
      Phase seenPhase = null;
      int seenEpoch = -1;
      for (int epoch = 0; epoch < sessions.size(); epoch++) {
        for (TraceEvent e : sessions.get(epoch).trace().events(Direction.SERVERBOUND)) {
          if (ClientSession.contains(e.payload(), token)) {
            seen.add("epoch " + epoch + " (" + epochServers.get(epoch) + ") " + e.phase());
            seenPhase = e.phase();
            seenEpoch = epoch;
          }
        }
      }
      String what = "probe sent at " + probe.point() + " (client in " + probe.phase()
          + ", epoch " + probe.epoch() + ")";
      if (seen.isEmpty()) {
        out.fail("CHECK probe.dropped " + probe.point(), what + " never reached a backend");
        continue;
      }
      if (seen.size() > 1) {
        out.fail("CHECK probe.duplicated " + probe.point(), what + " arrived " + seen.size()
            + " times: " + seen);
        continue;
      }
      if (probe.point().equals("START_CONFIGURATION") || probe.point().equals("BEFORE_SWITCH")) {
        out.tolerate(what + " arrived at " + seen.getFirst());
        continue;
      }
      Phase expected = probe.phase();
      if (seenEpoch != probe.epoch() || seenPhase != expected) {
        out.fail("CHECK probe.misrouted " + probe.point(), what + " arrived at "
            + seen.getFirst() + ", expected epoch " + probe.epoch() + " ("
            + epochServers.get(probe.epoch()) + ") in " + expected);
      }
    }
  }

  /**
   * The client rejects a configuration start (or a join game) inside an open bundle with
   * "Terminal message received in bundle" (#1384).
   */
  private static void checkBundles(ScenarioContext ctx, List<TraceEvent> received,
      ScenarioOutput out) {
    boolean open = false;
    for (TraceEvent e : received) {
      if (e.phase() != Phase.PLAY) {
        open = false;
        continue;
      }
      String name = ctx.table().describe(Phase.PLAY, Direction.CLIENTBOUND, e.id());
      if (name.equals("minecraft:bundle_delimiter")) {
        open = !open;
      } else if (open && (name.equals("minecraft:start_configuration")
          || name.equals("minecraft:login"))) {
        out.fail("CHECK client.bundle-terminal", name + " delivered inside an open bundle");
      }
    }
  }

  private static List<TraceEvent> cut(List<TraceEvent> events, int at) {
    return at < 0 ? events : events.subList(0, Math.min(events.size(), at + 1));
  }

  private static void checkLog(ManagedProcess process, int from, Pattern trouble,
      String signature, ScenarioOutput out) {
    for (String finding : SessionScenario.logFindings(process.linesSince(from), trouble)) {
      out.fail(signature, finding);
    }
  }

  private void writeTraces(ScenarioContext ctx, ClientSession client,
      List<TapRelay.Session> sessions, List<String> epochServers, List<Finding> findings) {
    try {
      Path dir = ctx.traceDir();
      Files.createDirectories(dir);
      String header = "scenario=" + id + " version=" + ctx.version().id() + " proxy="
          + ctx.rules().kind();
      client.received().write(dir.resolve("client-received.trace"), ctx.table(), header);
      client.sent().write(dir.resolve("client-sent.trace"), ctx.table(), header);
      for (int i = 0; i < sessions.size(); i++) {
        sessions.get(i).trace().write(dir.resolve("backend-" + i + "-" + epochServers.get(i)
            + ".trace"), ctx.table(), header);
      }
      try (Writer w = Files.newBufferedWriter(dir.resolve("findings.txt"),
          StandardCharsets.UTF_8)) {
        for (Finding f : findings) {
          w.write(f.toString());
          w.write('\n');
        }
      }
    } catch (IOException e) {
      System.err.println("[report] could not write traces: " + e);
    }
  }
}
