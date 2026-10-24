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

package com.velocitypowered.proxy.conformance.harness.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests ledger version ranges and version ordering.
 */
class VersionRangeTest {

  private static void assertContains(String range, String... versions) {
    VersionRange parsed = VersionRange.parse(range);
    for (String version : versions) {
      assertTrue(parsed.contains(version), range + " should contain " + version);
    }
  }

  private static void assertExcludes(String range, String... versions) {
    VersionRange parsed = VersionRange.parse(range);
    for (String version : versions) {
      assertFalse(parsed.contains(version), range + " should not contain " + version);
    }
  }

  @Test
  void comparesNumericallyNotLexically() {
    assertTrue(VersionRange.compare("1.9", "1.20.2") < 0);
    assertTrue(VersionRange.compare("1.20.2", "1.9") > 0);
    assertTrue(VersionRange.compare("1.20.2", "26.1") < 0);
    assertTrue(VersionRange.compare("1.20", "1.20.1") < 0);
    assertEquals(0, VersionRange.compare("1.21", "1.21.0"));
    assertEquals(0, VersionRange.compare("1.20.4", "1.20.4"));
  }

  @Test
  void preReleaseSortsBeforeItsReleaseAndAfterOlderReleases() {
    assertTrue(VersionRange.compare("26.4-snapshot-5", "26.4") < 0);
    assertTrue(VersionRange.compare("26.4", "26.4-snapshot-5") > 0);
    assertTrue(VersionRange.compare("26.4-snapshot-5", "26.3") > 0);
    assertTrue(VersionRange.compare("1.21-pre1", "1.21-rc1") < 0);
  }

  @Test
  void starContainsEverything() {
    assertContains("*", "1.7.2", "1.20.4", "26.4-snapshot-5");
  }

  @Test
  void singleVersionIsExact() {
    assertContains("1.20.4", "1.20.4");
    assertExcludes("1.20.4", "1.20.3", "1.20.5", "1.20.40", "1.20");
  }

  @Test
  void lowerBoundIsInclusive() {
    assertContains(">=1.20.2", "1.20.2", "1.20.4", "1.21", "26.1", "26.4-snapshot-5");
    assertExcludes(">=1.20.2", "1.20.1", "1.9", "1.20.2-pre1");
  }

  @Test
  void strictUpperBoundIsExclusive() {
    assertContains("<1.13", "1.12.2", "1.8", "1.7.10", "1.13-pre1");
    assertExcludes("<1.13", "1.13", "1.13.2", "1.20.4");
  }

  @Test
  void otherComparisons() {
    assertContains("<=1.12.2", "1.12.2", "1.8");
    assertExcludes("<=1.12.2", "1.13");
    assertContains(">1.12.2", "1.13");
    assertExcludes(">1.12.2", "1.12.2");
  }

  @Test
  void dottedRangeIsInclusiveAtBothEnds() {
    assertContains("1.8..1.12.2", "1.8", "1.8.9", "1.10.2", "1.12", "1.12.2");
    assertExcludes("1.8..1.12.2", "1.7.10", "1.13", "1.20.4");
  }

  @Test
  void commaSeparatedClausesAreAlternatives() {
    assertContains("1.8,>=1.21", "1.8", "1.21", "1.21.4", "26.1");
    assertExcludes("1.8,>=1.21", "1.8.9", "1.12.2", "1.20.6");
    assertContains(" 1.8 , >= 1.21 ", "1.8", "1.21.4");
  }

  @Test
  void toStringIsTheOriginalText() {
    assertEquals("1.8,>=1.21", VersionRange.parse("1.8,>=1.21").toString());
  }
}
