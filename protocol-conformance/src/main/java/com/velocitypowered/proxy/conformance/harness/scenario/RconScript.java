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

import static com.velocitypowered.proxy.conformance.harness.wire.Protocols.V1_12;
import static com.velocitypowered.proxy.conformance.harness.wire.Protocols.V1_13;
import static com.velocitypowered.proxy.conformance.harness.wire.Protocols.V1_16;
import static com.velocitypowered.proxy.conformance.harness.wire.Protocols.V1_17;
import static com.velocitypowered.proxy.conformance.harness.wire.Protocols.V1_20_3;
import static com.velocitypowered.proxy.conformance.harness.wire.Protocols.V1_20_5;
import static com.velocitypowered.proxy.conformance.harness.wire.Protocols.V1_21_2;
import static com.velocitypowered.proxy.conformance.harness.wire.Protocols.V1_21_6;
import static com.velocitypowered.proxy.conformance.harness.wire.Protocols.V1_8;
import static com.velocitypowered.proxy.conformance.harness.wire.Protocols.V1_9;

import com.velocitypowered.proxy.conformance.harness.vanilla.VanillaVersion;
import com.velocitypowered.proxy.conformance.harness.wire.Protocols;
import java.util.ArrayList;
import java.util.List;

/**
 * Console commands that make the vanilla server emit as many distinct clientbound packet types
 * as it can without a real client's cooperation. Each entry is gated by the protocol range whose
 * command syntax it uses; a command the server rejects is logged and skipped, never fatal, and
 * the coverage report shows what was actually reached.
 */
public final class RconScript {

  private static final int V1_11 = 315;
  private static final int V1_13_1 = 401;
  private static final int V1_19_4 = 762;

  private record Entry(int from, int until, String command) {
  }

  private static final List<Entry> ENTRIES = new ArrayList<>();

  static {
    // Ops get a richer command tree and the op-level entity event.
    add(0, 0, "op {p}");
    // World state.
    add(0, 0, "time set 1000");
    add(0, 0, "weather rain");
    add(0, 0, "weather clear");
    add(0, V1_13, "difficulty 3");
    add(V1_13, 0, "difficulty hard");
    add(0, 0, "setworldspawn 0 64 0");
    add(V1_20_3, 0, "tick freeze");
    add(V1_20_3, 0, "tick step 1");
    add(V1_20_3, 0, "tick unfreeze");
    add(V1_8, 0, "worldborder set 2000");
    add(V1_8, 0, "worldborder set 1000 5");
    add(V1_8, 0, "worldborder center 1 1");
    add(V1_8, 0, "worldborder warning distance 6");
    add(V1_8, 0, "worldborder warning time 20");
    // The player.
    add(0, V1_13, "gamemode 1 {p}");
    add(V1_13, 0, "gamemode creative {p}");
    add(0, V1_13, "xp 10 {p}");
    add(V1_13, 0, "xp add {p} 10 levels");
    add(0, 0, "give {p} minecraft:diamond_sword 1");
    add(0, 0, "give {p} minecraft:apple 3");
    add(V1_13, 0, "enchant {p} minecraft:sharpness 1");
    add(V1_8, V1_13, "effect {p} 1 30 1");
    add(V1_8, V1_13, "effect {p} clear");
    add(V1_13, 0, "effect give {p} minecraft:speed 30 1");
    add(V1_13, 0, "effect clear {p}");
    add(V1_16, V1_21_2, "attribute {p} minecraft:generic.movement_speed base set 0.11");
    add(V1_21_2, 0, "attribute {p} minecraft:movement_speed base set 0.11");
    add(V1_17, 0, "item replace entity {p} armor.head with minecraft:diamond_helmet");
    add(0, 0, "tp {p} 4 5 4");
    add(V1_12, 0, "advancement grant {p} only minecraft:story/root");
    add(V1_12, 0, "recipe give {p} minecraft:crafting_table");
    add(V1_12, 0, "recipe take {p} minecraft:crafting_table");
    add(V1_19_4, 0, "damage {p} 1");
    add(0, 0, "clear {p} minecraft:apple");
    // Chat and titles.
    add(0, 0, "say conformance says hello");
    add(0, 0, "tellraw {p} {\"text\":\"conformance\",\"color\":\"gold\"}");
    add(V1_8, 0, "title {p} times 5 20 5");
    add(V1_8, 0, "title {p} subtitle {\"text\":\"sub\"}");
    add(V1_8, 0, "title {p} title {\"text\":\"title\"}");
    add(V1_11, 0, "title {p} actionbar {\"text\":\"bar\"}");
    add(V1_8, 0, "title {p} clear");
    add(V1_8, 0, "title {p} reset");
    // Scoreboard and teams.
    add(0, 0, "scoreboard objectives add conf dummy");
    add(0, 0, "scoreboard objectives setdisplay sidebar conf");
    add(0, 0, "scoreboard players set {p} conf 5");
    add(V1_13, 0, "scoreboard players reset {p} conf");
    add(0, V1_13, "scoreboard teams add confteam");
    add(0, V1_13, "scoreboard teams join confteam {p}");
    add(V1_13, 0, "team add confteam");
    add(V1_13, 0, "team modify confteam color red");
    add(V1_13, 0, "team join confteam {p}");
    add(0, 0, "scoreboard objectives remove conf");
    add(V1_13, 0, "bossbar add conformance:bar \"Conformance\"");
    add(V1_13, 0, "bossbar set conformance:bar players {p}");
    add(V1_13, 0, "bossbar set conformance:bar value 50");
    add(V1_13, 0, "bossbar set conformance:bar color red");
    add(V1_13, 0, "bossbar remove conformance:bar");
    // Sounds and particles.
    add(V1_9, V1_13, "playsound entity.experience_orb.pickup master {p}");
    add(V1_13, 0, "playsound minecraft:entity.experience_orb.pickup master {p}");
    add(V1_13, 0, "stopsound {p}");
    add(V1_8, V1_13, "execute {p} ~ ~ ~ particle heart ~ ~1 ~ 1 1 1 0 10 force");
    add(V1_13, 0, "execute at {p} run particle minecraft:heart ~ ~1 ~ 1 1 1 0 10 force");
    // Blocks.
    add(V1_8, V1_13, "execute {p} ~ ~ ~ setblock ~2 ~ ~ stone");
    add(V1_8, V1_13, "execute {p} ~ ~ ~ fill ~3 ~ ~3 ~5 ~2 ~5 glass");
    add(V1_8, V1_13, "execute {p} ~ ~ ~ setblock ~2 ~ ~ air 0 destroy");
    add(V1_13, 0, "execute at {p} run setblock ~2 ~ ~ minecraft:stone");
    add(V1_13, 0, "execute at {p} run fill ~3 ~ ~3 ~5 ~2 ~5 minecraft:glass");
    add(V1_13, 0, "execute at {p} run setblock ~2 ~ ~ minecraft:air destroy");
    add(V1_13, 0, "execute at {p} run setblock ~-2 ~ ~ minecraft:oak_sign");
    // Entities.
    add(V1_8, V1_11, "execute {p} ~ ~ ~ summon Pig ~3 ~ ~");
    add(V1_11, V1_13, "execute {p} ~ ~ ~ summon pig ~3 ~ ~");
    add(V1_13, 0, "execute at {p} run summon minecraft:pig ~3 ~ ~ {NoAI:1b}");
    add(V1_13, 0, "execute at {p} run summon minecraft:armor_stand ~-3 ~ ~");
    add(V1_13, 0, "execute at {p} run summon minecraft:experience_orb ~ ~ ~1");
    add(V1_13, 0, "execute at {p} run summon minecraft:lightning_bolt ~8 ~ ~8");
    add(V1_13, V1_20_5, "execute at {p} run summon minecraft:item ~ ~ ~ "
        + "{Item:{id:\"minecraft:apple\",Count:1b}}");
    add(V1_20_5, 0, "execute at {p} run summon minecraft:item ~ ~ ~ "
        + "{Item:{id:\"minecraft:apple\",count:1}}");
    add(V1_13, 0, "execute as @e[type=minecraft:pig] at @s run tp @s ~1 ~ ~ ~90 ~");
    add(V1_19_4, 0, "ride {p} mount @e[type=minecraft:pig,limit=1,sort=nearest]");
    add(V1_19_4, 0, "ride {p} dismount");
    add(V1_13, 0, "gamemode spectator {p}");
    add(V1_13, 0, "spectate @e[type=minecraft:armor_stand,limit=1] {p}");
    add(V1_13, 0, "spectate");
    add(V1_13, 0, "gamemode creative {p}");
    add(V1_13, 0, "execute at {p} run summon minecraft:tnt ~6 ~ ~6 {fuse:1}");
    add(V1_8, V1_13, "kill @e[type=Pig]");
    add(V1_13, 0, "kill @e[type=!minecraft:player]");
    // Dialogs.
    add(V1_21_6, 0, "dialog show {p} {type:\"minecraft:notice\",title:\"Conformance\"}");
    add(V1_21_6, 0, "dialog clear {p}");
    // Death and respawn: the client answers with perform-respawn.
    add(0, V1_13, "gamemode 0 {p}");
    add(V1_13, 0, "gamemode survival {p}");
    // Not on 1.13 itself: its RCON runs commands off the server thread and /kill crashes the tick
    // loop with a ConcurrentModificationException (fixed in 1.13.1).
    add(0, V1_13, "kill {p}");
    add(V1_13_1, 0, "kill {p}");
  }

  private RconScript() {
  }

  private static void add(int from, int until, String command) {
    ENTRIES.add(new Entry(from, until, command));
  }

  /**
   * Returns the commands that apply to {@code version}, with the player substituted.
   *
   * @param version the version
   * @param player the player name
   * @return the commands, in order
   */
  public static List<String> commands(VanillaVersion version, String player) {
    List<String> out = new ArrayList<>();
    for (Entry e : ENTRIES) {
      if (e.from() != 0 && !Protocols.atLeast(version.protocol(), e.from())) {
        continue;
      }
      if (e.until() != 0 && Protocols.atLeast(version.protocol(), e.until())) {
        continue;
      }
      out.add(e.command().replace("{p}", player));
    }
    return out;
  }

  /**
   * Builds a {@code tellraw} carrying {@code text}, used as the clientbound sync marker.
   *
   * @param version the version
   * @param player the player name
   * @param text the marker text
   * @return the command
   */
  public static String tellraw(VanillaVersion version, String player, String text) {
    return "tellraw " + player + " {\"text\":\"" + text + "\"}";
  }
}
