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

import static com.velocitypowered.proxy.conformance.harness.diff.DiffFixtures.CONFIG_SB_CLIENT_INFORMATION;
import static com.velocitypowered.proxy.conformance.harness.diff.DiffFixtures.CONFIG_SB_CUSTOM_PAYLOAD;
import static com.velocitypowered.proxy.conformance.harness.diff.DiffFixtures.CONFIG_SB_KEEP_ALIVE;
import static com.velocitypowered.proxy.conformance.harness.diff.DiffFixtures.PLAY_CB_COMMANDS;
import static com.velocitypowered.proxy.conformance.harness.diff.DiffFixtures.PLAY_CB_CUSTOM_PAYLOAD;
import static com.velocitypowered.proxy.conformance.harness.diff.DiffFixtures.PLAY_CB_TAB_LIST;
import static com.velocitypowered.proxy.conformance.harness.diff.DiffFixtures.PLAY_SB_CHAT;
import static com.velocitypowered.proxy.conformance.harness.diff.DiffFixtures.PLAY_SB_CUSTOM_PAYLOAD;
import static com.velocitypowered.proxy.conformance.harness.diff.DiffFixtures.PLAY_SB_KEEP_ALIVE;
import static com.velocitypowered.proxy.conformance.harness.diff.DiffFixtures.brand;
import static com.velocitypowered.proxy.conformance.harness.diff.DiffFixtures.channelPayload;
import static com.velocitypowered.proxy.conformance.harness.diff.DiffFixtures.commands;
import static com.velocitypowered.proxy.conformance.harness.diff.DiffFixtures.ctx;
import static com.velocitypowered.proxy.conformance.harness.diff.DiffFixtures.event;
import static com.velocitypowered.proxy.conformance.harness.diff.DiffFixtures.keepAlive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.proxy.conformance.harness.diff.Finding.Kind;
import com.velocitypowered.proxy.conformance.harness.trace.TraceEvent;
import com.velocitypowered.proxy.conformance.harness.wire.Buf;
import com.velocitypowered.proxy.conformance.harness.wire.Direction;
import com.velocitypowered.proxy.conformance.harness.wire.Phase;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests Velocity's documented tolerances, end to end through the differ.
 */
class VelocityRulesTest {

  private static final ProxyRules VELOCITY = ProxyRules.forKind("velocity");
  private static final RuleContext CLIENTBOUND = ctx(Direction.CLIENTBOUND, 774,
      CommandTreeTest.ARGUMENT_TYPES.toArray(String[]::new));
  private static final RuleContext SERVERBOUND = ctx(Direction.SERVERBOUND, 774, null);

  private static TraceEvent playClientbound(int id, byte[] payload) {
    return event(Direction.CLIENTBOUND, Phase.PLAY, id, payload);
  }

  private static TraceEvent playServerbound(int id, byte[] payload) {
    return event(Direction.SERVERBOUND, Phase.PLAY, id, payload);
  }

  private static TraceEvent configServerbound(int id, byte[] payload) {
    return event(Direction.SERVERBOUND, Phase.CONFIGURATION, id, payload);
  }

  private static Finding only(Differ.Result result) {
    assertEquals(1, result.findings().size(), () -> "findings: " + result.findings());
    return result.findings().get(0);
  }

  private static Finding brandFinding(String sent, String delivered) {
    TraceEvent source = playClientbound(PLAY_CB_CUSTOM_PAYLOAD, brand(sent));
    TraceEvent sink = playClientbound(PLAY_CB_CUSTOM_PAYLOAD, brand(delivered));
    return only(Differ.compare(CLIENTBOUND, List.of(keepAlive(1), source, keepAlive(2)),
        List.of(keepAlive(1), sink, keepAlive(2)), VELOCITY));
  }

  private static Finding injectedTabList(byte[] payload) {
    TraceEvent tabList = playClientbound(PLAY_CB_TAB_LIST, payload);
    return only(Differ.compare(CLIENTBOUND, List.of(keepAlive(1), keepAlive(2)),
        List.of(keepAlive(1), tabList, keepAlive(2)), VELOCITY));
  }

  private static Finding injectedServerboundPayload(String channel) {
    TraceEvent keepAlive = playServerbound(PLAY_SB_KEEP_ALIVE, new byte[8]);
    TraceEvent payload = playServerbound(PLAY_SB_CUSTOM_PAYLOAD,
        channelPayload(channel, "velocity:main\0bungeecord:main".getBytes(
            StandardCharsets.UTF_8)));
    return only(Differ.compare(SERVERBOUND, List.of(keepAlive),
        List.of(payload, keepAlive), VELOCITY));
  }

  private static Finding reorderedConfigPayload(byte[] body) {
    TraceEvent payload = configServerbound(CONFIG_SB_CUSTOM_PAYLOAD, body);
    TraceEvent information = configServerbound(CONFIG_SB_CLIENT_INFORMATION, new byte[] {1, 2});
    TraceEvent keepAlive = configServerbound(CONFIG_SB_KEEP_ALIVE, new byte[8]);
    return only(Differ.compare(SERVERBOUND, List.of(payload, information, keepAlive),
        List.of(information, keepAlive, payload), VELOCITY));
  }

  @Test
  void kindIsVelocity() {
    assertEquals("velocity", VELOCITY.kind());
  }

  @Test
  void brandWithVelocityAppendedIsTolerated() {
    Finding finding = brandFinding("vanilla", "vanilla (Velocity)");
    assertEquals(Kind.TOLERATED, finding.kind());
    assertEquals("minecraft:custom_payload", finding.packet());
    assertEquals("Velocity appends itself to the backend brand (\"vanilla\" -> "
        + "\"vanilla (Velocity)\")", finding.detail());
  }

  @Test
  void brandRewrittenToSomethingElseIsMismatch() {
    assertEquals(Kind.MISMATCH, brandFinding("vanilla", "Paper").kind());
    // The backend brand survives, but Velocity's name is not what was appended.
    assertEquals(Kind.MISMATCH, brandFinding("vanilla", "vanilla (Waterfall)").kind());
    // Velocity appended itself, but the backend brand was lost.
    assertEquals(Kind.MISMATCH, brandFinding("vanilla", "Velocity").kind());
  }

  @Test
  void otherChannelRewriteIsMismatch() {
    TraceEvent source = playClientbound(PLAY_CB_CUSTOM_PAYLOAD,
        new Buf().writeString("example:channel").writeString("vanilla").toByteArray());
    TraceEvent sink = playClientbound(PLAY_CB_CUSTOM_PAYLOAD,
        new Buf().writeString("example:channel").writeString("vanilla (Velocity)")
            .toByteArray());
    assertEquals(Kind.MISMATCH,
        only(Differ.compare(CLIENTBOUND, List.of(source), List.of(sink), VELOCITY)).kind());
  }

  @Test
  void injectedEmptyTabListIsTolerated() {
    // Header and footer both the empty NBT string component.
    Finding finding = injectedTabList(HexFormat.of().parseHex("080000080000"));
    assertEquals(Kind.TOLERATED, finding.kind());
    assertEquals("minecraft:tab_list", finding.packet());
    assertTrue(finding.detail().contains("TransitionSessionHandler"), finding.detail());
  }

  @Test
  void injectedTabListWithContentIsNotTolerated() {
    Finding different = injectedTabList(HexFormat.of().parseHex("080000080001"));
    assertEquals(Kind.INJECTED, different.kind());

    byte[] large = new byte[42];
    Arrays.fill(large, (byte) 0x41);
    assertEquals(Kind.INJECTED, injectedTabList(large).kind());
  }

  @Test
  void injectedRegisterIsTolerated() {
    Finding modern = injectedServerboundPayload("minecraft:register");
    assertEquals(Kind.TOLERATED, modern.kind());
    assertEquals("minecraft:custom_payload", modern.packet());
    assertTrue(modern.detail().contains("re-announces"), modern.detail());
    assertEquals(Kind.TOLERATED, injectedServerboundPayload("REGISTER").kind());
  }

  @Test
  void injectedPayloadOnAnotherChannelIsNotTolerated() {
    assertEquals(Kind.INJECTED, injectedServerboundPayload("minecraft:brand").kind());
    assertEquals(Kind.INJECTED, injectedServerboundPayload("minecraft:unregister").kind());
  }

  @Test
  void injectedServerboundChatIsNotTolerated() {
    TraceEvent chat = playServerbound(PLAY_SB_CHAT, new Buf().writeString("hi").toByteArray());
    assertEquals(Kind.INJECTED,
        only(Differ.compare(SERVERBOUND, List.of(), List.of(chat), VELOCITY)).kind());
  }

  @Test
  void deferredConfigurationBrandIsTolerated() {
    Finding finding = reorderedConfigPayload(brand("vanilla"));
    assertEquals(Kind.TOLERATED, finding.kind());
    assertEquals(Phase.CONFIGURATION, finding.phase());
    assertTrue(finding.detail().contains("holds the client's brand"), finding.detail());
  }

  @Test
  void otherReorderedConfigurationPayloadIsNotTolerated() {
    Finding finding = reorderedConfigPayload(
        new Buf().writeString("example:hello").writeString("vanilla").toByteArray());
    assertEquals(Kind.REORDERED, finding.kind());
  }

  @Test
  void commandTreeWithVelocityCommandsIsTolerated() {
    TraceEvent source = playClientbound(PLAY_CB_COMMANDS,
        commands(CommandTreeTest.base(), 774, CommandTreeTest.ARGUMENT_TYPES));
    TraceEvent sink = playClientbound(PLAY_CB_COMMANDS,
        commands(CommandTreeTest.withVelocity(), 774, CommandTreeTest.ARGUMENT_TYPES));
    Finding finding = only(Differ.compare(CLIENTBOUND, List.of(source), List.of(sink),
        VELOCITY));
    assertEquals(Kind.TOLERATED, finding.kind());
    assertEquals("minecraft:commands", finding.packet());
  }

  @Test
  void commandTreeWithChangedParserIsExplainedMismatch() {
    TraceEvent source = playClientbound(PLAY_CB_COMMANDS, commands(
        CommandTreeTest.withLocate("minecraft:resource_or_tag"), 774,
        CommandTreeTest.ARGUMENT_TYPES));
    TraceEvent sink = playClientbound(PLAY_CB_COMMANDS, commands(
        CommandTreeTest.withLocate("minecraft:resource"), 774, CommandTreeTest.ARGUMENT_TYPES));
    Finding finding = only(Differ.compare(CLIENTBOUND, List.of(source), List.of(sink),
        VELOCITY));
    assertEquals(Kind.MISMATCH, finding.kind());
    // This is what the committed ledger entry matches on.
    assertTrue(finding.detail().startsWith("/locate changed (sent minecraft:resource_or_tag "
        + "delivered minecraft:resource); payload differs at byte "), finding.detail());
  }
}
