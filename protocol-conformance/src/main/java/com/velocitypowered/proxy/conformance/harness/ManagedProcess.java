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

package com.velocitypowered.proxy.conformance.harness;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * A child process whose output is logged to a file and watched line by line. Every process is
 * reaped when the harness exits, however it exits, so an aborted run leaves no orphans.
 */
public final class ManagedProcess implements AutoCloseable {

  private static final Set<ManagedProcess> LIVE = ConcurrentHashMap.newKeySet();

  static {
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      for (ManagedProcess process : LIVE) {
        process.kill();
      }
    }, "reap-children"));
  }

  private final String name;
  private final Process process;
  private final List<String> lines = new ArrayList<>();

  private ManagedProcess(String name, Process process) {
    this.name = name;
    this.process = process;
  }

  /**
   * Starts a process.
   *
   * @param name a label for logs
   * @param command the command line
   * @param dir its working directory
   * @param log where its combined output goes
   * @return the running process
   * @throws HarnessException if it cannot be started
   */
  public static ManagedProcess start(String name, List<String> command, Path dir, Path log)
      throws HarnessException {
    try {
      Process process = new ProcessBuilder(command)
          .directory(dir.toFile())
          .redirectErrorStream(true)
          .redirectInput(ProcessBuilder.Redirect.PIPE)
          .start();
      ManagedProcess managed = new ManagedProcess(name, process);
      LIVE.add(managed);
      Thread reader = new Thread(() -> managed.pump(log), name + "-output");
      reader.setDaemon(true);
      reader.start();
      return managed;
    } catch (IOException e) {
      throw new HarnessException("cannot start " + name + ": " + command, e);
    }
  }

  public String name() {
    return name;
  }

  public boolean isAlive() {
    return process.isAlive();
  }

  /**
   * Blocks until a line matching {@code predicate} has been printed (including earlier lines).
   *
   * @param predicate what to look for
   * @param timeout how long to wait
   * @return the matching line
   * @throws HarnessException on timeout or process exit
   */
  public String awaitLine(Predicate<String> predicate, Duration timeout) throws HarnessException {
    long deadline = System.nanoTime() + timeout.toNanos();
    int checked = 0;
    while (true) {
      synchronized (lines) {
        for (; checked < lines.size(); checked++) {
          if (predicate.test(lines.get(checked))) {
            return lines.get(checked);
          }
        }
        if (!process.isAlive()) {
          throw new HarnessException(name + " exited with " + process.exitValue()
              + " before becoming ready:\n" + tail(30));
        }
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
          throw new HarnessException(name + " did not become ready within " + timeout
              + ":\n" + tail(30));
        }
        try {
          lines.wait(Math.max(1, Math.min(TimeUnit.NANOSECONDS.toMillis(remaining), 500)));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new HarnessException("interrupted waiting for " + name, e);
        }
      }
    }
  }

  /**
   * Returns whether any line so far matches.
   *
   * @param predicate what to look for
   * @return whether one was printed
   */
  public boolean hasLine(Predicate<String> predicate) {
    synchronized (lines) {
      return lines.stream().anyMatch(predicate);
    }
  }

  /**
   * Returns how many lines have been printed so far.
   *
   * @return the line count
   */
  public int lineCount() {
    synchronized (lines) {
      return lines.size();
    }
  }

  /**
   * Returns the lines printed since {@code from}.
   *
   * @param from a value previously returned by {@link #lineCount()}
   * @return the lines
   */
  public List<String> linesSince(int from) {
    synchronized (lines) {
      return List.copyOf(lines.subList(Math.min(from, lines.size()), lines.size()));
    }
  }

  /**
   * Returns the last {@code count} lines of output.
   *
   * @param count how many
   * @return the lines, joined
   */
  public String tail(int count) {
    synchronized (lines) {
      return String.join("\n", lines.subList(Math.max(0, lines.size() - count), lines.size()));
    }
  }

  /**
   * Waits for the process to exit by itself, then kills it.
   *
   * @param grace how long to wait first
   */
  public void stop(Duration grace) {
    try {
      if (!process.waitFor(grace.toMillis(), TimeUnit.MILLISECONDS)) {
        kill();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      kill();
    }
    LIVE.remove(this);
  }

  private void kill() {
    process.descendants().forEach(ProcessHandle::destroyForcibly);
    process.destroyForcibly();
    try {
      process.waitFor(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void pump(Path log) {
    try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        Writer out = Files.newBufferedWriter(log, StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        out.write(line);
        out.write('\n');
        out.flush();
        synchronized (lines) {
          lines.add(line);
          lines.notifyAll();
        }
      }
    } catch (IOException ignored) {
      // Process gone.
    } finally {
      synchronized (lines) {
        lines.notifyAll();
      }
    }
  }

  @Override
  public void close() {
    kill();
    LIVE.remove(this);
  }
}
