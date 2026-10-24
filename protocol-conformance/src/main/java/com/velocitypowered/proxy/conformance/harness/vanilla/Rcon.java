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

package com.velocitypowered.proxy.conformance.harness.vanilla;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * A Source RCON client, as implemented by the vanilla server: little-endian length, request id
 * and type, then an ASCII body and two NUL bytes.
 */
public final class Rcon implements AutoCloseable {

  private static final int TYPE_RESPONSE = 0;
  private static final int TYPE_COMMAND = 2;
  private static final int TYPE_LOGIN = 3;

  private final Socket socket;
  private final DataInputStream in;
  private final OutputStream out;
  private int nextId = 1;

  private Rcon(Socket socket) throws IOException {
    this.socket = socket;
    this.in = new DataInputStream(socket.getInputStream());
    this.out = socket.getOutputStream();
  }

  /**
   * Connects and authenticates.
   *
   * @param address the RCON listener
   * @param password the RCON password
   * @return a logged-in client
   * @throws IOException if the connection or login fails
   */
  public static Rcon connect(InetSocketAddress address, String password) throws IOException {
    Socket socket = new Socket();
    socket.connect(address, 5000);
    socket.setSoTimeout(30000);
    Rcon rcon = new Rcon(socket);
    int id = rcon.send(TYPE_LOGIN, password);
    Packet reply = rcon.read();
    if (reply.id() == -1 || reply.id() != id) {
      rcon.close();
      throw new IOException("RCON login refused");
    }
    return rcon;
  }

  /**
   * Runs a console command.
   *
   * @param command the command, without a leading slash
   * @return the server's reply text
   * @throws IOException on a connection error
   */
  public synchronized String command(String command) throws IOException {
    int id = send(TYPE_COMMAND, command);
    Packet reply;
    do {
      reply = read();
    } while (reply.id() != id);
    return reply.body();
  }

  private record Packet(int id, int type, String body) {
  }

  private int send(int type, String body) throws IOException {
    int id = nextId++;
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    ByteBuffer buffer = ByteBuffer.allocate(4 + 4 + 4 + bytes.length + 2)
        .order(ByteOrder.LITTLE_ENDIAN);
    buffer.putInt(4 + 4 + bytes.length + 2).putInt(id).putInt(type).put(bytes).put((byte) 0)
        .put((byte) 0);
    out.write(buffer.array());
    out.flush();
    return id;
  }

  private Packet read() throws IOException {
    byte[] header = new byte[4];
    in.readFully(header);
    int length = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt();
    if (length < 10 || length > 1 << 20) {
      throw new IOException("bad RCON packet length " + length);
    }
    byte[] body = new byte[length];
    in.readFully(body);
    ByteBuffer buffer = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);
    int id = buffer.getInt();
    int type = buffer.getInt();
    String text = new String(body, 8, length - 10, StandardCharsets.UTF_8);
    return new Packet(id, type == TYPE_COMMAND ? TYPE_RESPONSE : type, text);
  }

  @Override
  public void close() throws IOException {
    socket.close();
  }
}
