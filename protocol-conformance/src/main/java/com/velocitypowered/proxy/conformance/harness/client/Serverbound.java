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

import static com.velocitypowered.proxy.conformance.harness.wire.Protocols.V1_13;
import static com.velocitypowered.proxy.conformance.harness.wire.Protocols.V1_17;
import static com.velocitypowered.proxy.conformance.harness.wire.Protocols.V1_18;
import static com.velocitypowered.proxy.conformance.harness.wire.Protocols.V1_19;
import static com.velocitypowered.proxy.conformance.harness.wire.Protocols.V1_19_1;
import static com.velocitypowered.proxy.conformance.harness.wire.Protocols.V1_19_3;
import static com.velocitypowered.proxy.conformance.harness.wire.Protocols.V1_20_2;
import static com.velocitypowered.proxy.conformance.harness.wire.Protocols.V1_20_5;
import static com.velocitypowered.proxy.conformance.harness.wire.Protocols.V1_21;
import static com.velocitypowered.proxy.conformance.harness.wire.Protocols.V1_21_2;
import static com.velocitypowered.proxy.conformance.harness.wire.Protocols.V1_21_5;
import static com.velocitypowered.proxy.conformance.harness.wire.Protocols.V1_8;
import static com.velocitypowered.proxy.conformance.harness.wire.Protocols.V1_9;

import com.velocitypowered.proxy.conformance.harness.wire.Buf;
import com.velocitypowered.proxy.conformance.harness.wire.Protocols;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Version-gated encoders for the serverbound packets the scripted client sends, written from the
 * protocol documentation. Each returns the packet body without the id.
 */
public final class Serverbound {

  private final int protocol;

  public Serverbound(int protocol) {
    this.protocol = protocol;
  }

  private boolean atLeast(int level) {
    return Protocols.atLeast(protocol, level);
  }

  /**
   * Builds the handshake.
   *
   * @param host the address the client thinks it is connecting to
   * @param port the port
   * @param next 1 for status, 2 for login
   * @return the body
   */
  public byte[] intention(String host, int port, int next) {
    return new Buf().writeVarInt(protocol).writeString(host).writeShort(port).writeVarInt(next)
        .toByteArray();
  }

  /**
   * Builds login start.
   *
   * @param name the player name
   * @param uuid the profile id the client believes it has
   * @return the body
   */
  public byte[] loginStart(String name, UUID uuid) {
    Buf buf = new Buf().writeString(name);
    if (atLeast(V1_20_2)) {
      buf.writeUuid(uuid);
    } else if (atLeast(V1_19_3)) {
      buf.writeBoolean(true).writeUuid(uuid);
    } else if (atLeast(V1_19_1)) {
      buf.writeBoolean(false).writeBoolean(true).writeUuid(uuid);
    } else if (atLeast(V1_19)) {
      buf.writeBoolean(false);
    }
    return buf.toByteArray();
  }

  /**
   * Builds the encryption response.
   *
   * @param secret the RSA-encrypted shared secret
   * @param token the RSA-encrypted verify token
   * @return the body
   */
  public byte[] key(byte[] secret, byte[] token) {
    Buf buf = new Buf();
    if (!atLeast(V1_8)) {
      buf.writeShort(secret.length).writeBytes(secret).writeShort(token.length).writeBytes(token);
    } else if (atLeast(V1_19) && !atLeast(V1_19_3)) {
      buf.writeByteArray(secret).writeBoolean(true).writeByteArray(token);
    } else {
      buf.writeByteArray(secret).writeByteArray(token);
    }
    return buf.toByteArray();
  }

  /**
   * Builds client information (settings) the way a vanilla client with default options does.
   *
   * @return the body
   */
  public byte[] clientInformation() {
    Buf buf = new Buf().writeString("en_us").writeByte(3);
    if (!atLeast(V1_8)) {
      return buf.writeByte(0).writeBoolean(true).writeByte(1).writeBoolean(true).toByteArray();
    }
    if (atLeast(V1_9)) {
      buf.writeVarInt(0);
    } else {
      buf.writeByte(0);
    }
    buf.writeBoolean(true).writeByte(0x7F);
    if (atLeast(V1_9)) {
      buf.writeVarInt(1);
    }
    if (atLeast(V1_17)) {
      buf.writeBoolean(false);
    }
    if (atLeast(V1_18)) {
      buf.writeBoolean(true);
    }
    if (atLeast(V1_21_2)) {
      buf.writeVarInt(0);
    }
    return buf.toByteArray();
  }

  /**
   * Builds a custom payload.
   *
   * @param channel the channel, already in the version's naming
   * @param data the payload
   * @return the body
   */
  public byte[] customPayload(String channel, byte[] data) {
    Buf buf = new Buf().writeString(channel);
    if (!atLeast(V1_8)) {
      buf.writeShort(data.length);
    }
    return buf.writeBytes(data).toByteArray();
  }

  public String brandChannel() {
    return atLeast(V1_13) ? "minecraft:brand" : "MC|Brand";
  }

  public String markerChannel() {
    return atLeast(V1_13) ? "conformance:mark" : "CONF|mark";
  }

  /**
   * Builds the vanilla brand payload.
   *
   * @return the body
   */
  public byte[] brand() {
    byte[] brand = new Buf().writeString("vanilla").toByteArray();
    return customPayload(brandChannel(), atLeast(V1_8) ? brand
        : "vanilla".getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Builds an unsigned chat message.
   *
   * @param message the text
   * @return the body
   */
  public byte[] chat(String message) {
    Buf buf = new Buf().writeString(message);
    if (!atLeast(V1_19)) {
      return buf.toByteArray();
    }
    buf.writeLong(System.currentTimeMillis()).writeLong(0L);
    if (atLeast(V1_19_3)) {
      buf.writeBoolean(false);
      lastSeen(buf);
    } else {
      buf.writeVarInt(0).writeBoolean(false);
      if (atLeast(V1_19_1)) {
        buf.writeVarInt(0).writeBoolean(false);
      }
    }
    return buf.toByteArray();
  }

  public boolean hasChatCommandPacket() {
    return atLeast(V1_19);
  }

  /**
   * Builds an unsigned command. Before 1.19 commands are chat messages starting with a slash.
   *
   * @param command the command, without the slash
   * @return the body
   */
  public byte[] chatCommand(String command) {
    Buf buf = new Buf().writeString(command);
    if (atLeast(V1_20_5)) {
      return buf.toByteArray();
    }
    buf.writeLong(System.currentTimeMillis()).writeLong(0L).writeVarInt(0);
    if (atLeast(V1_19_3)) {
      lastSeen(buf);
    } else {
      buf.writeBoolean(false);
      if (atLeast(V1_19_1)) {
        buf.writeVarInt(0).writeBoolean(false);
      }
    }
    return buf.toByteArray();
  }

  private void lastSeen(Buf buf) {
    // offset, then a fixed 20-bit acknowledgement set, then (1.21.5+) a checksum byte where 0
    // means "not computed".
    buf.writeVarInt(0).writeBytes(new byte[3]);
    if (atLeast(V1_21_5)) {
      buf.writeByte(0);
    }
  }

  /**
   * Builds a command suggestion request.
   *
   * @param transaction the transaction id (1.13+)
   * @param text the partial input, including the leading slash
   * @return the body
   */
  public byte[] commandSuggestion(int transaction, String text) {
    if (atLeast(V1_13)) {
      return new Buf().writeVarInt(transaction).writeString(text).toByteArray();
    }
    Buf buf = new Buf().writeString(text);
    if (atLeast(V1_9)) {
      buf.writeBoolean(false);
    }
    if (atLeast(V1_8)) {
      buf.writeBoolean(false);
    }
    return buf.toByteArray();
  }

  /**
   * Builds a teleport confirmation (1.9+).
   *
   * @param teleportId the id from the position packet
   * @return the body
   */
  public byte[] acceptTeleportation(int teleportId) {
    return new Buf().writeVarInt(teleportId).toByteArray();
  }

  /**
   * Builds a position-and-rotation update (1.9+ layout).
   *
   * @param x x
   * @param y y
   * @param z z
   * @param yaw yaw
   * @param pitch pitch
   * @return the body
   */
  public byte[] movePlayerPosRot(double x, double y, double z, float yaw, float pitch) {
    Buf buf = new Buf().writeDouble(x).writeDouble(y).writeDouble(z).writeFloat(yaw)
        .writeFloat(pitch);
    if (atLeast(V1_21_2)) {
      buf.writeByte(1);
    } else {
      buf.writeBoolean(true);
    }
    return buf.toByteArray();
  }

  /**
   * Builds an arm swing (1.8+).
   *
   * @return the body
   */
  public byte[] swing() {
    return atLeast(V1_9) ? new Buf().writeVarInt(0).toByteArray() : new byte[0];
  }

  public byte[] setCarriedItem(int slot) {
    return new Buf().writeShort(slot).toByteArray();
  }

  /**
   * Builds a respawn request.
   *
   * @return the body
   */
  public byte[] performRespawn() {
    return atLeast(V1_8) ? new Buf().writeVarInt(0).toByteArray()
        : new Buf().writeByte(0).toByteArray();
  }

  /**
   * Builds a use-item (1.9+).
   *
   * @param sequence the block-change sequence (1.19+)
   * @return the body
   */
  public byte[] useItem(int sequence) {
    Buf buf = new Buf().writeVarInt(0);
    if (atLeast(V1_19)) {
      buf.writeVarInt(sequence);
    }
    if (atLeast(V1_21)) {
      buf.writeFloat(0f).writeFloat(0f);
    }
    return buf.toByteArray();
  }

  /**
   * Builds a resource pack status.
   *
   * @param packId the pack id (1.20.3+)
   * @param status the status ordinal
   * @return the body
   */
  public byte[] resourcePack(UUID packId, int status) {
    Buf buf = new Buf();
    if (packId != null) {
      buf.writeUuid(packId);
    }
    return buf.writeVarInt(status).toByteArray();
  }

  /**
   * Builds a chunk batch acknowledgement (1.20.2+).
   *
   * @return the body
   */
  public byte[] chunkBatchReceived() {
    return new Buf().writeFloat(20f).toByteArray();
  }

  /**
   * Builds a login plugin answer declining the query (1.13+).
   *
   * @param transaction the query id
   * @return the body
   */
  public byte[] customQueryAnswer(int transaction) {
    return new Buf().writeVarInt(transaction).writeBoolean(false).toByteArray();
  }

  /**
   * Builds a cookie response without a payload (1.20.5+).
   *
   * @param key the requested cookie key
   * @return the body
   */
  public byte[] cookieResponse(String key) {
    return new Buf().writeString(key).writeBoolean(false).toByteArray();
  }

  public boolean protocolHasTeleportIds() {
    return atLeast(V1_9);
  }
}
