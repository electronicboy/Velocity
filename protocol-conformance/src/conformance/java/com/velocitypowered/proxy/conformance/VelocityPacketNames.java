/*
 * Copyright (C) 2018-2025 Velocity Contributors
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

package com.velocitypowered.proxy.conformance;

import static com.velocitypowered.api.network.ProtocolVersion.MINECRAFT_1_21;
import static com.velocitypowered.api.network.ProtocolVersion.MINECRAFT_1_21_2;
import static com.velocitypowered.proxy.protocol.ProtocolUtils.Direction.CLIENTBOUND;
import static com.velocitypowered.proxy.protocol.ProtocolUtils.Direction.SERVERBOUND;
import static com.velocitypowered.proxy.protocol.StateRegistry.CONFIG;
import static com.velocitypowered.proxy.protocol.StateRegistry.HANDSHAKE;
import static com.velocitypowered.proxy.protocol.StateRegistry.LOGIN;
import static com.velocitypowered.proxy.protocol.StateRegistry.PLAY;
import static com.velocitypowered.proxy.protocol.StateRegistry.STATUS;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.protocol.ProtocolUtils.Direction;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.packet.AvailableCommandsPacket;
import com.velocitypowered.proxy.protocol.packet.BossBarPacket;
import com.velocitypowered.proxy.protocol.packet.BundleDelimiterPacket;
import com.velocitypowered.proxy.protocol.packet.ClientSettingsPacket;
import com.velocitypowered.proxy.protocol.packet.ClientboundCookieRequestPacket;
import com.velocitypowered.proxy.protocol.packet.ClientboundPostEffectsPacket;
import com.velocitypowered.proxy.protocol.packet.ClientboundSoundEntityPacket;
import com.velocitypowered.proxy.protocol.packet.ClientboundStopSoundPacket;
import com.velocitypowered.proxy.protocol.packet.ClientboundStoreCookiePacket;
import com.velocitypowered.proxy.protocol.packet.DialogClearPacket;
import com.velocitypowered.proxy.protocol.packet.DialogShowPacket;
import com.velocitypowered.proxy.protocol.packet.DisconnectPacket;
import com.velocitypowered.proxy.protocol.packet.EncryptionRequestPacket;
import com.velocitypowered.proxy.protocol.packet.EncryptionResponsePacket;
import com.velocitypowered.proxy.protocol.packet.HandshakePacket;
import com.velocitypowered.proxy.protocol.packet.HeaderAndFooterPacket;
import com.velocitypowered.proxy.protocol.packet.KeepAlivePacket;
import com.velocitypowered.proxy.protocol.packet.LoginAcknowledgedPacket;
import com.velocitypowered.proxy.protocol.packet.LoginPluginMessagePacket;
import com.velocitypowered.proxy.protocol.packet.LoginPluginResponsePacket;
import com.velocitypowered.proxy.protocol.packet.PingIdentifyPacket;
import com.velocitypowered.proxy.protocol.packet.PluginMessagePacket;
import com.velocitypowered.proxy.protocol.packet.RemovePlayerInfoPacket;
import com.velocitypowered.proxy.protocol.packet.RemoveResourcePackPacket;
import com.velocitypowered.proxy.protocol.packet.ResourcePackRequestPacket;
import com.velocitypowered.proxy.protocol.packet.ResourcePackResponsePacket;
import com.velocitypowered.proxy.protocol.packet.ServerDataPacket;
import com.velocitypowered.proxy.protocol.packet.ServerLoginPacket;
import com.velocitypowered.proxy.protocol.packet.ServerLoginSuccessPacket;
import com.velocitypowered.proxy.protocol.packet.ServerboundCookieResponsePacket;
import com.velocitypowered.proxy.protocol.packet.ServerboundCustomClickActionPacket;
import com.velocitypowered.proxy.protocol.packet.ServerboundPlayerLoadedPacket;
import com.velocitypowered.proxy.protocol.packet.SetCompressionPacket;
import com.velocitypowered.proxy.protocol.packet.StatusPingPacket;
import com.velocitypowered.proxy.protocol.packet.StatusRequestPacket;
import com.velocitypowered.proxy.protocol.packet.StatusResponsePacket;
import com.velocitypowered.proxy.protocol.packet.TabCompleteRequestPacket;
import com.velocitypowered.proxy.protocol.packet.TabCompleteResponsePacket;
import com.velocitypowered.proxy.protocol.packet.TransferPacket;
import com.velocitypowered.proxy.protocol.packet.UpsertPlayerInfoPacket;
import com.velocitypowered.proxy.protocol.packet.chat.ChatAcknowledgementPacket;
import com.velocitypowered.proxy.protocol.packet.chat.PlayerChatCompletionPacket;
import com.velocitypowered.proxy.protocol.packet.chat.SystemChatPacket;
import com.velocitypowered.proxy.protocol.packet.chat.session.SessionPlayerChatPacket;
import com.velocitypowered.proxy.protocol.packet.chat.session.SessionPlayerCommandPacket;
import com.velocitypowered.proxy.protocol.packet.chat.session.UnsignedPlayerCommandPacket;
import com.velocitypowered.proxy.protocol.packet.config.ActiveFeaturesPacket;
import com.velocitypowered.proxy.protocol.packet.config.ClientboundCustomReportDetailsPacket;
import com.velocitypowered.proxy.protocol.packet.config.ClientboundServerLinksPacket;
import com.velocitypowered.proxy.protocol.packet.config.CodeOfConductAcceptPacket;
import com.velocitypowered.proxy.protocol.packet.config.CodeOfConductPacket;
import com.velocitypowered.proxy.protocol.packet.config.FinishedUpdatePacket;
import com.velocitypowered.proxy.protocol.packet.config.KnownPacksPacket;
import com.velocitypowered.proxy.protocol.packet.config.RegistrySyncPacket;
import com.velocitypowered.proxy.protocol.packet.config.StartUpdatePacket;
import com.velocitypowered.proxy.protocol.packet.config.TagsUpdatePacket;
import com.velocitypowered.proxy.protocol.packet.title.TitleActionbarPacket;
import com.velocitypowered.proxy.protocol.packet.title.TitleClearPacket;
import com.velocitypowered.proxy.protocol.packet.title.TitleSubtitlePacket;
import com.velocitypowered.proxy.protocol.packet.title.TitleTextPacket;
import com.velocitypowered.proxy.protocol.packet.title.TitleTimesPacket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Maps each packet class Velocity registers in {@link StateRegistry} to the name Mojang gives it in
 * the data generator's packet report.
 *
 * <p>Lookups are by phase and direction as well as class, since one class can stand for several
 * vanilla packets: {@link PingIdentifyPacket} is {@code ping} one way and {@code pong} the other,
 * and {@link FinishedUpdatePacket} is {@code finish_configuration} in the configuration phase but
 * {@code configuration_acknowledged} in play.</p>
 *
 * <p>Only packets registered from 1.21 onwards are listed, as that is the first version whose data
 * generator emits a packet report. Legacy and pre-1.21 classes are never registered for the
 * versions under test, so they need no entry.</p>
 */
final class VelocityPacketNames {

  private static final List<Mapping> MAPPINGS = new ArrayList<>();

  static {
    map(HANDSHAKE, SERVERBOUND, HandshakePacket.class, "minecraft:intention");

    map(STATUS, SERVERBOUND, StatusRequestPacket.class, "minecraft:status_request");
    map(STATUS, SERVERBOUND, StatusPingPacket.class, "minecraft:ping_request");
    map(STATUS, CLIENTBOUND, StatusResponsePacket.class, "minecraft:status_response");
    map(STATUS, CLIENTBOUND, StatusPingPacket.class, "minecraft:pong_response");

    map(LOGIN, SERVERBOUND, ServerLoginPacket.class, "minecraft:hello");
    map(LOGIN, SERVERBOUND, EncryptionResponsePacket.class, "minecraft:key");
    map(LOGIN, SERVERBOUND, LoginPluginResponsePacket.class, "minecraft:custom_query_answer");
    map(LOGIN, SERVERBOUND, LoginAcknowledgedPacket.class, "minecraft:login_acknowledged");
    map(LOGIN, SERVERBOUND, ServerboundCookieResponsePacket.class, "minecraft:cookie_response");
    map(LOGIN, CLIENTBOUND, DisconnectPacket.class, "minecraft:login_disconnect");
    map(LOGIN, CLIENTBOUND, EncryptionRequestPacket.class, "minecraft:hello");
    // Renamed by Mojang in 1.21.2; the packet itself is unchanged.
    map(LOGIN, CLIENTBOUND, ServerLoginSuccessPacket.class, MINECRAFT_1_21,
        "minecraft:game_profile");
    map(LOGIN, CLIENTBOUND, ServerLoginSuccessPacket.class, MINECRAFT_1_21_2,
        "minecraft:login_finished");
    map(LOGIN, CLIENTBOUND, SetCompressionPacket.class, "minecraft:login_compression");
    map(LOGIN, CLIENTBOUND, LoginPluginMessagePacket.class, "minecraft:custom_query");
    map(LOGIN, CLIENTBOUND, ClientboundCookieRequestPacket.class, "minecraft:cookie_request");

    map(CONFIG, SERVERBOUND, ClientSettingsPacket.class, "minecraft:client_information");
    map(CONFIG, SERVERBOUND, ServerboundCookieResponsePacket.class, "minecraft:cookie_response");
    map(CONFIG, SERVERBOUND, PluginMessagePacket.class, "minecraft:custom_payload");
    map(CONFIG, SERVERBOUND, FinishedUpdatePacket.class, "minecraft:finish_configuration");
    map(CONFIG, SERVERBOUND, KeepAlivePacket.class, "minecraft:keep_alive");
    map(CONFIG, SERVERBOUND, PingIdentifyPacket.class, "minecraft:pong");
    map(CONFIG, SERVERBOUND, ResourcePackResponsePacket.class, "minecraft:resource_pack");
    map(CONFIG, SERVERBOUND, KnownPacksPacket.class, "minecraft:select_known_packs");
    map(CONFIG, SERVERBOUND, ServerboundCustomClickActionPacket.class,
        "minecraft:custom_click_action");
    map(CONFIG, SERVERBOUND, CodeOfConductAcceptPacket.class, "minecraft:accept_code_of_conduct");
    map(CONFIG, CLIENTBOUND, ClientboundCookieRequestPacket.class, "minecraft:cookie_request");
    map(CONFIG, CLIENTBOUND, PluginMessagePacket.class, "minecraft:custom_payload");
    map(CONFIG, CLIENTBOUND, DisconnectPacket.class, "minecraft:disconnect");
    map(CONFIG, CLIENTBOUND, FinishedUpdatePacket.class, "minecraft:finish_configuration");
    map(CONFIG, CLIENTBOUND, KeepAlivePacket.class, "minecraft:keep_alive");
    map(CONFIG, CLIENTBOUND, PingIdentifyPacket.class, "minecraft:ping");
    map(CONFIG, CLIENTBOUND, RegistrySyncPacket.class, "minecraft:registry_data");
    map(CONFIG, CLIENTBOUND, RemoveResourcePackPacket.class, "minecraft:resource_pack_pop");
    map(CONFIG, CLIENTBOUND, ResourcePackRequestPacket.class, "minecraft:resource_pack_push");
    map(CONFIG, CLIENTBOUND, ClientboundPostEffectsPacket.class, "minecraft:post_effects");
    map(CONFIG, CLIENTBOUND, ClientboundStoreCookiePacket.class, "minecraft:store_cookie");
    map(CONFIG, CLIENTBOUND, TransferPacket.class, "minecraft:transfer");
    map(CONFIG, CLIENTBOUND, ActiveFeaturesPacket.class, "minecraft:update_enabled_features");
    map(CONFIG, CLIENTBOUND, TagsUpdatePacket.class, "minecraft:update_tags");
    map(CONFIG, CLIENTBOUND, KnownPacksPacket.class, "minecraft:select_known_packs");
    map(CONFIG, CLIENTBOUND, ClientboundCustomReportDetailsPacket.class,
        "minecraft:custom_report_details");
    map(CONFIG, CLIENTBOUND, ClientboundServerLinksPacket.class, "minecraft:server_links");
    map(CONFIG, CLIENTBOUND, DialogClearPacket.class, "minecraft:clear_dialog");
    map(CONFIG, CLIENTBOUND, DialogShowPacket.class, "minecraft:show_dialog");
    map(CONFIG, CLIENTBOUND, CodeOfConductPacket.class, "minecraft:code_of_conduct");

    map(PLAY, SERVERBOUND, TabCompleteRequestPacket.class, "minecraft:command_suggestion");
    map(PLAY, SERVERBOUND, ChatAcknowledgementPacket.class, "minecraft:chat_ack");
    map(PLAY, SERVERBOUND, SessionPlayerCommandPacket.class, "minecraft:chat_command_signed");
    map(PLAY, SERVERBOUND, UnsignedPlayerCommandPacket.class, "minecraft:chat_command");
    map(PLAY, SERVERBOUND, SessionPlayerChatPacket.class, "minecraft:chat");
    map(PLAY, SERVERBOUND, ClientSettingsPacket.class, "minecraft:client_information");
    map(PLAY, SERVERBOUND, ServerboundCookieResponsePacket.class, "minecraft:cookie_response");
    map(PLAY, SERVERBOUND, ServerboundPlayerLoadedPacket.class, "minecraft:player_loaded");
    map(PLAY, SERVERBOUND, PluginMessagePacket.class, "minecraft:custom_payload");
    map(PLAY, SERVERBOUND, KeepAlivePacket.class, "minecraft:keep_alive");
    map(PLAY, SERVERBOUND, ResourcePackResponsePacket.class, "minecraft:resource_pack");
    map(PLAY, SERVERBOUND, FinishedUpdatePacket.class, "minecraft:configuration_acknowledged");
    map(PLAY, CLIENTBOUND, BossBarPacket.class, "minecraft:boss_event");
    map(PLAY, CLIENTBOUND, TabCompleteResponsePacket.class, "minecraft:command_suggestions");
    map(PLAY, CLIENTBOUND, AvailableCommandsPacket.class, "minecraft:commands");
    map(PLAY, CLIENTBOUND, ClientboundCookieRequestPacket.class, "minecraft:cookie_request");
    map(PLAY, CLIENTBOUND, ClientboundPostEffectsPacket.class, "minecraft:post_effects");
    map(PLAY, CLIENTBOUND, ClientboundSoundEntityPacket.class, "minecraft:sound_entity");
    map(PLAY, CLIENTBOUND, ClientboundStopSoundPacket.class, "minecraft:stop_sound");
    map(PLAY, CLIENTBOUND, PluginMessagePacket.class, "minecraft:custom_payload");
    map(PLAY, CLIENTBOUND, DisconnectPacket.class, "minecraft:disconnect");
    map(PLAY, CLIENTBOUND, KeepAlivePacket.class, "minecraft:keep_alive");
    map(PLAY, CLIENTBOUND, byName("JoinGamePacket"), "minecraft:login");
    map(PLAY, CLIENTBOUND, byName("RespawnPacket"), "minecraft:respawn");
    map(PLAY, CLIENTBOUND, RemoveResourcePackPacket.class, "minecraft:resource_pack_pop");
    map(PLAY, CLIENTBOUND, ResourcePackRequestPacket.class, "minecraft:resource_pack_push");
    map(PLAY, CLIENTBOUND, HeaderAndFooterPacket.class, "minecraft:tab_list");
    map(PLAY, CLIENTBOUND, TitleSubtitlePacket.class, "minecraft:set_subtitle_text");
    map(PLAY, CLIENTBOUND, TitleTextPacket.class, "minecraft:set_title_text");
    map(PLAY, CLIENTBOUND, TitleActionbarPacket.class, "minecraft:set_action_bar_text");
    map(PLAY, CLIENTBOUND, TitleTimesPacket.class, "minecraft:set_titles_animation");
    map(PLAY, CLIENTBOUND, TitleClearPacket.class, "minecraft:clear_titles");
    map(PLAY, CLIENTBOUND, RemovePlayerInfoPacket.class, "minecraft:player_info_remove");
    map(PLAY, CLIENTBOUND, UpsertPlayerInfoPacket.class, "minecraft:player_info_update");
    map(PLAY, CLIENTBOUND, ClientboundStoreCookiePacket.class, "minecraft:store_cookie");
    map(PLAY, CLIENTBOUND, SystemChatPacket.class, "minecraft:system_chat");
    map(PLAY, CLIENTBOUND, PlayerChatCompletionPacket.class, "minecraft:custom_chat_completions");
    map(PLAY, CLIENTBOUND, ServerDataPacket.class, "minecraft:server_data");
    map(PLAY, CLIENTBOUND, StartUpdatePacket.class, "minecraft:start_configuration");
    map(PLAY, CLIENTBOUND, BundleDelimiterPacket.class, "minecraft:bundle_delimiter");
    map(PLAY, CLIENTBOUND, TransferPacket.class, "minecraft:transfer");
    map(PLAY, CLIENTBOUND, ClientboundCustomReportDetailsPacket.class,
        "minecraft:custom_report_details");
    map(PLAY, CLIENTBOUND, ClientboundServerLinksPacket.class, "minecraft:server_links");
  }

  private VelocityPacketNames() {
  }

  /**
   * Returns Mojang's name for a Velocity packet class as registered in the given phase and
   * direction for the given version, or {@code null} if the class is not mapped there.
   */
  static String name(StateRegistry state, Direction direction, Class<?> packet,
      ProtocolVersion version) {
    Mapping best = null;
    for (Mapping mapping : MAPPINGS) {
      if (mapping.state() == state && mapping.direction() == direction
          && mapping.packet() == packet && version.noLessThan(mapping.since())
          && (best == null || mapping.since().greaterThan(best.since()))) {
        best = mapping;
      }
    }
    return best == null ? null : best.name();
  }

  /**
   * Returns every mapping in the table.
   */
  static List<Mapping> mappings() {
    return Collections.unmodifiableList(MAPPINGS);
  }

  /**
   * Returns the name of a phase as it appears in the packet report.
   */
  static String phase(StateRegistry state) {
    return switch (state) {
      case HANDSHAKE -> "handshake";
      case STATUS -> "status";
      case LOGIN -> "login";
      case CONFIG -> "configuration";
      case PLAY -> "play";
    };
  }

  /**
   * Returns the name of a direction as it appears in the packet report.
   */
  static String direction(Direction direction) {
    return switch (direction) {
      case SERVERBOUND -> "serverbound";
      case CLIENTBOUND -> "clientbound";
    };
  }

  /**
   * Loads a class from Velocity's packet package by name. Only for classes that cannot be named as
   * literals here: those carrying type-annotated fastutil types in their fields, which javac must
   * resolve when it reads the class file, while fastutil is an implementation dependency of the
   * proxy and so absent from this source set's compile classpath.
   */
  private static Class<?> byName(String simpleName) {
    String name = "com.velocitypowered.proxy.protocol.packet." + simpleName;
    try {
      return Class.forName(name);
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException(name + " no longer exists", e);
    }
  }

  private static void map(StateRegistry state, Direction direction, Class<?> packet,
      String name) {
    map(state, direction, packet, MINECRAFT_1_21, name);
  }

  private static void map(StateRegistry state, Direction direction, Class<?> packet,
      ProtocolVersion since, String name) {
    MAPPINGS.add(new Mapping(state, direction, packet, since, name));
  }

  /**
   * One entry of the table: the vanilla name a class carries from {@code since} until the next
   * entry for the same phase, direction and class.
   */
  record Mapping(StateRegistry state, Direction direction, Class<?> packet,
      ProtocolVersion since, String name) {
  }
}
