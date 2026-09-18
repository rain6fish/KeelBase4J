// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The merge the generator now depends on, tested on its own — it is the part most likely to be subtly
 * wrong, and a wrong merge silently loses someone's work rather than failing.
 */
class ThreeWayMergeTest {

    private static final List<String> BASE = List.of(
            "class A {",
            "    int one = 1;",
            "    int two = 2;",
            "    int three = 3;",
            "}");

    @Test
    void bothSidesAgreeingNeedsNoMerge() {
        List<String> same = List.of("class A {", "}");
        ThreeWayMerge.Result result = ThreeWayMerge.merge(BASE, same, same);
        assertEquals(same, result.lines());
        assertFalse(result.conflicted());
    }

    @Test
    void onlyOneSideMovingTakesThatSide() {
        List<String> theirs = List.of("class B {", "}");
        assertEquals(theirs, ThreeWayMerge.merge(BASE, BASE, theirs).lines());
        assertEquals(theirs, ThreeWayMerge.merge(BASE, theirs, BASE).lines());
    }

    @Test
    void editsInDifferentRegionsBothSurvive() {
        List<String> mine = List.of(
                "class A {",
                "    int one = 1;",
                "    // mine, near the top",
                "    int two = 2;",
                "    int three = 3;",
                "}");
        List<String> theirs = List.of(
                "class A {",
                "    int one = 1;",
                "    int two = 2;",
                "    int three = 3;",
                "    // theirs, near the bottom",
                "}");

        ThreeWayMerge.Result result = ThreeWayMerge.merge(BASE, mine, theirs);

        assertFalse(result.conflicted(), "disjoint edits are not a conflict");
        assertEquals(List.of(
                "class A {",
                "    int one = 1;",
                "    // mine, near the top",
                "    int two = 2;",
                "    int three = 3;",
                "    // theirs, near the bottom",
                "}"), result.lines());
    }

    @Test
    void theSameRegionChangedDifferentlyIsAConflict() {
        List<String> mine = List.of("class A {", "    int two = 20;", "    int three = 3;", "}");
        List<String> theirs = List.of("class A {", "    long two = 2L;", "    int three = 3;", "}");

        ThreeWayMerge.Result result = ThreeWayMerge.merge(BASE, mine, theirs);

        assertTrue(result.conflicted(), "the same region changed twice must not be guessed at");
        String merged = String.join("\n", result.lines());
        assertTrue(merged.contains("<<<<<<< your changes"), "the developer's side is marked");
        assertTrue(merged.contains("int two = 20;"), "and its content is kept for them to resolve");
        assertTrue(merged.contains("||||||| generated last time"), "with the ancestry shown");
        assertTrue(merged.contains("long two = 2L;"), "and what the generator wanted");
    }

    @Test
    void theSameRegionChangedIdenticallyIsNotAConflict() {
        List<String> both = List.of("class A {", "    int two = 22;", "}", "");
        List<String> base = List.of("class A {", "    int two = 22;", "}", "");
        assertEquals(both, ThreeWayMerge.merge(base, both, both).lines());
    }

    @Test
    void aDeletionOnOneSideOnlyIsApplied() {
        List<String> mine = List.of("class A {", "    int one = 1;", "    int three = 3;", "}");
        ThreeWayMerge.Result result = ThreeWayMerge.merge(BASE, mine, BASE);
        assertEquals(mine, result.lines());
        assertFalse(result.conflicted());
    }

    @Test
    void anAdditionAtTheEndStillLands() {
        List<String> theirs = List.of(
                "class A {", "    int one = 1;", "    int two = 2;", "    int three = 3;", "}", "", "class B {", "}");
        ThreeWayMerge.Result result = ThreeWayMerge.merge(BASE, BASE, theirs);
        assertEquals(theirs, result.lines());
        assertFalse(result.conflicted());
    }

    @Test
    void refusingIsBetterThanExhaustingTheHeap() {
        // All three sides differ, so this cannot short-circuit — it has to align, and must decline.
        List<String> big = java.util.stream.IntStream.range(0, 4000).mapToObj(i -> "line " + i).toList();
        List<String> alsoBig = java.util.stream.IntStream.range(0, 4000).mapToObj(i -> "other " + i).toList();
        org.junit.jupiter.api.Assertions.assertThrows(ThreeWayMerge.TooLargeToMergeException.class,
                () -> ThreeWayMerge.merge(big, alsoBig, List.of("tiny")));
    }
}
