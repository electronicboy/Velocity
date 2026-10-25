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
import com.velocitypowered.proxy.conformance.harness.client.ClientOptions;
import com.velocitypowered.proxy.conformance.harness.client.ClientSession;
import com.velocitypowered.proxy.conformance.harness.diff.Differ;
import com.velocitypowered.proxy.conformance.harness.diff.Finding;
import com.velocitypowered.proxy.conformance.harness.diff.RuleContext;
import com.velocitypowered.proxy.conformance.harness.tap.TapRelay;
import com.velocitypowered.proxy.conformance.harness.trace.Trace;
import com.velocitypowered.proxy.conformance.harness.trace.TraceEvent;
import com.velocitypowered.proxy.conformance.harness.wire.Direction;
import com.velocitypowered.proxy.conformance.harness.wire.Phase;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A scenario that joins through the proxy, runs a script, and then checks the session for
 * transparency in both directions.
 *
 * <p>The session is closed off with sync markers before comparing: a client custom payload
 * carrying a unique token (serverbound) and an RCON {@code tellraw} carrying another
 * (clientbound). Both sides' captures are cut at the marker, so packets still in flight when the
 * session ends are never mistaken for drops.</p>
 */
public abstract class SessionScenario implements Scenario {

  protected static final Duration LOGIN_TIMEOUT = Duration.ofSeconds(30);
  private static final Duration MARKER_TIMEOUT = Duration.ofSeconds(20);
  private static final Pattern PROXY_TROUBLE =
      Pattern.compile("(?i)(exception|\\berror\\b|failed to decode|unable to decode)");
  private static final Pattern BACKEND_TROUBLE = Pattern.compile(
      "(?i)(internal exception|decoderexception|failed to (decode|handle)|packet processing|"
          + "error receiving packet|bad packet)");

  /**
   * Returns the client options for this scenario.
   *
   * @param ctx the context
   * @return the options
   */
  protected abstract ClientOptions options(ScenarioContext ctx);

  /**
   * Drives the session once the client is in play.
   *
   * @param ctx the context
   * @param client the logged-in client
   * @param out where to record failures
   * @throws HarnessException on an infrastructure failure
   * @throws InterruptedException if interrupted
   * @throws IOException if sending fails
   */
  protected abstract void script(ScenarioContext ctx, ClientSession client, ScenarioOutput out)
      throws HarnessException, InterruptedException, IOException;

  /**
   * Checks what the proxy told the client during login. Default: nothing.
   *
   * @param ctx the context
   * @param client the logged-in client
   * @param out where to record failures
   */
  protected void checkLogin(ScenarioContext ctx, ClientSession client, ScenarioOutput out) {
  }

  @Override
  public boolean comparesTraffic() {
    return true;
  }

  @Override
  public ScenarioOutput run(ScenarioContext ctx) throws HarnessException, InterruptedException {
    ScenarioOutput out = new ScenarioOutput();
    int proxyLines = ctx.proxy().lineCount();
    int backendLines = ctx.server().process().lineCount();
    int sessionIndex = ctx.tap().sessions().size();

    ClientSession client;
    try {
      client = ClientSession.login(ctx.proxyAddress(), ctx.version().protocol(), ctx.table(),
          options(ctx), LOGIN_TIMEOUT);
    } catch (ClientSession.LoginRejectedException e) {
      out.fail("CHECK login.rejected", e.getMessage());
      checkLogs(ctx, proxyLines, backendLines, out);
      return out;
    } catch (IOException e) {
      throw new HarnessException("cannot connect to the proxy", e);
    }

    try (client) {
      checkLogin(ctx, client, out);
      Optional<TapRelay.Session> backend = ctx.tap().awaitSession(sessionIndex, LOGIN_TIMEOUT);
      if (backend.isEmpty()) {
        out.fail("CHECK backend.connect", "the proxy never connected to the backend; client is in "
            + client.phase() + client.disconnectReason().map(r -> ", disconnected: " + r).orElse(""));
        writeTraces(ctx, client, null, List.of());
        checkLogs(ctx, proxyLines, backendLines, out);
        return out;
      }
      if (!client.awaitPlay(LOGIN_TIMEOUT)) {
        out.fail("CHECK client.play", "never reached play: "
            + client.disconnectReason().orElse("no disconnect reason"));
        writeTraces(ctx, client, backend.get(), List.of());
        checkLogs(ctx, proxyLines, backendLines, out);
        return out;
      }

      try {
        script(ctx, client, out);
      } catch (IOException e) {
        out.fail("CHECK client.send", "could not send: " + e.getMessage());
      }

      List<Finding> findings = synchroniseAndCompare(ctx, client, backend.get(), out);
      writeTraces(ctx, client, backend.get(), findings);
      client.disconnectReason().ifPresent(reason ->
          out.fail("CHECK client.disconnected", "disconnected mid-session: " + reason));
      client.readerFailure().ifPresent(failure ->
          out.fail("CHECK client.decode", "could not read the proxy's stream: " + failure));
    }
    checkLogs(ctx, proxyLines, backendLines, out);
    if (ctx.server().process().hasLine(l -> l.contains("This crash report has been saved"))
        || !ctx.server().process().isAlive()) {
      // The backend itself fell over; whatever the proxy did afterwards is not a finding.
      throw new HarnessException("the vanilla server crashed during the scenario:\n"
          + ctx.server().process().tail(20));
    }
    return out;
  }

  private List<Finding> synchroniseAndCompare(ScenarioContext ctx, ClientSession client,
      TapRelay.Session backend, ScenarioOutput out) throws HarnessException,
      InterruptedException {
    Trace backendTrace = backend.trace();
    String serverboundToken = "sb-" + UUID.randomUUID();
    String clientboundToken = "cb-" + UUID.randomUUID();

    int sentCut = -1;
    int backendSbCut = -1;
    if (client.isConnected()) {
      try {
        client.send("minecraft:custom_payload", client.packets().customPayload(
            client.packets().markerChannel(),
            serverboundToken.getBytes(StandardCharsets.UTF_8)));
        sentCut = indexOf(client.sent().events(Direction.SERVERBOUND), serverboundToken);
        backendSbCut = awaitIndex(backendTrace, Direction.SERVERBOUND, serverboundToken);
        if (backendSbCut < 0) {
          out.fail("CHECK sync.serverbound", "the serverbound marker never reached the backend");
        }
      } catch (IOException e) {
        out.fail("CHECK client.send", "could not send the serverbound marker: " + e.getMessage());
      }
    }

    int backendCbCut = -1;
    int receivedCut = -1;
    if (client.isConnected()) {
      ctx.server().command(RconScript.tellraw(ctx.version(), options(ctx).name(), clientboundToken));
      backendCbCut = awaitIndex(backendTrace, Direction.CLIENTBOUND, clientboundToken);
      if (client.awaitMarker(clientboundToken, MARKER_TIMEOUT).isPresent()) {
        receivedCut = indexOf(client.received().events(Direction.CLIENTBOUND), clientboundToken);
      } else {
        out.fail("CHECK sync.clientbound", "the clientbound marker never reached the client");
      }
    }

    List<Finding> all = new ArrayList<>();
    RuleContext cb = new RuleContext(Direction.CLIENTBOUND, ctx.table(), ctx.version().protocol(),
        ctx.argumentTypes());
    all.addAll(compare(ctx, cb,
        window(backendTrace.events(Direction.CLIENTBOUND), backendCbCut),
        window(client.received().events(Direction.CLIENTBOUND), receivedCut), out));
    RuleContext sb = new RuleContext(Direction.SERVERBOUND, ctx.table(), ctx.version().protocol(),
        ctx.argumentTypes());
    all.addAll(compare(ctx, sb,
        window(client.sent().events(Direction.SERVERBOUND), sentCut),
        window(backendTrace.events(Direction.SERVERBOUND), backendSbCut), out));
    return all;
  }

  private static List<Finding> compare(ScenarioContext ctx, RuleContext rc,
      List<TraceEvent> source, List<TraceEvent> sink, ScenarioOutput out) {
    Differ.Result result = Differ.compare(rc, source, sink, ctx.rules());
    out.addMatched(result.matched());
    ctx.coverage().record(source);
    for (Finding f : result.findings()) {
      if (f.kind() == Finding.Kind.TOLERATED) {
        out.tolerate(f.packet() + ": " + f.detail());
      } else {
        out.fail(f.kind() + " " + f.dir().arrow() + " " + f.phase() + " " + f.packet(),
            "src#" + f.sourceIndex() + " sink#" + f.sinkIndex() + " " + f.detail());
      }
    }
    return result.findings();
  }

  /**
   * Keeps configuration and play packets up to and including the marker. Login and handshake
   * are between each side and the proxy, so they are not comparable.
   */
  private static List<TraceEvent> window(List<TraceEvent> events, int cut) {
    List<TraceEvent> out = new ArrayList<>();
    int end = cut < 0 ? events.size() : Math.min(events.size(), cut + 1);
    for (int i = 0; i < end; i++) {
      Phase phase = events.get(i).phase();
      if (phase == Phase.CONFIGURATION || phase == Phase.PLAY) {
        out.add(events.get(i));
      }
    }
    return out;
  }

  private static int indexOf(List<TraceEvent> events, String token) {
    byte[] needle = token.getBytes(StandardCharsets.UTF_8);
    for (int i = 0; i < events.size(); i++) {
      if (ClientSession.contains(events.get(i).payload(), needle)) {
        return i;
      }
    }
    return -1;
  }

  private static int awaitIndex(Trace trace, Direction dir, String token)
      throws InterruptedException {
    long deadline = System.nanoTime() + MARKER_TIMEOUT.toNanos();
    while (System.nanoTime() < deadline) {
      int index = indexOf(trace.events(dir), token);
      if (index >= 0) {
        return index;
      }
      Thread.sleep(25);
    }
    return -1;
  }

  private static void checkLogs(ScenarioContext ctx, int proxyFrom, int backendFrom,
      ScenarioOutput out) {
    for (String line : ctx.proxy().linesSince(proxyFrom)) {
      if (PROXY_TROUBLE.matcher(line).find()) {
        out.fail("CHECK proxy.log", line.strip());
      }
    }
    for (String line : ctx.server().process().linesSince(backendFrom)) {
      if (BACKEND_TROUBLE.matcher(line).find()) {
        out.fail("CHECK backend.log", line.strip());
      }
    }
  }

  private void writeTraces(ScenarioContext ctx, ClientSession client, TapRelay.Session backend,
      List<Finding> findings) {
    try {
      Path dir = ctx.traceDir();
      Files.createDirectories(dir);
      String header = "scenario=" + id() + " version=" + ctx.version().id() + " proxy="
          + ctx.rules().kind();
      client.received().write(dir.resolve("client-received.trace"), ctx.table(), header);
      client.sent().write(dir.resolve("client-sent.trace"), ctx.table(), header);
      if (backend != null) {
        backend.trace().write(dir.resolve("backend.trace"), ctx.table(), header);
      }
      try (Writer w = Files.newBufferedWriter(dir.resolve("findings.txt"),
          StandardCharsets.UTF_8)) {
        for (Finding f : findings) {
          w.write(f.toString());
          w.write('\n');
        }
      }
      // Keep both sides of every mismatch so it can be decoded offline.
      int n = 0;
      for (Finding f : findings) {
        if (f.kind() != Finding.Kind.MISMATCH) {
          continue;
        }
        List<TraceEvent> source = f.dir() == Direction.CLIENTBOUND
            ? backend.trace().events(Direction.CLIENTBOUND)
            : client.sent().events(Direction.SERVERBOUND);
        List<TraceEvent> sink = f.dir() == Direction.CLIENTBOUND
            ? client.received().events(Direction.CLIENTBOUND)
            : backend.trace().events(Direction.SERVERBOUND);
        String stem = String.format("mismatch-%02d-%s", n++, f.packet().replace(':', '_'));
        Files.write(dir.resolve(stem + "-sent.bin"),
            window(source, -1).get(f.sourceIndex()).payload());
        Files.write(dir.resolve(stem + "-delivered.bin"),
            window(sink, -1).get(f.sinkIndex()).payload());
      }
    } catch (IOException e) {
      System.err.println("[report] could not write traces: " + e);
    }
  }
}
