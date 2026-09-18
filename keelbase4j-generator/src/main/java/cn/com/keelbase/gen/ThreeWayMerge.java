// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.gen;

import java.util.ArrayList;
import java.util.List;

/**
 * A line-based three-way merge — the shape a regenerating generator needs, because it always has all
 * three sides: what it produced last time (base), what the file is now (the developer's, possibly
 * hand-edited anywhere), and what it would produce this time.
 *
 * <p><b>This merges text, not meaning.</b> It can tell that two sides changed different regions and
 * keep both; it cannot tell that renaming a method and updating its call sites is one change, because
 * that needs the program's structure rather than its lines. A change that both sides made to the same
 * region is reported as a conflict rather than resolved by a guess — losing the developer's work
 * silently is the one outcome worse than stopping.
 *
 * <p>Conflicts are marked in the standard {@code diff3} form, so the file is resolvable in place with
 * the tools a developer already uses. Nothing here writes anything; the caller decides.
 */
final class ThreeWayMerge {

    /**
     * Too many line pairs to align: refuse rather than allocate a table that could exhaust the heap.
     * The generated sources are a few hundred lines, so this only ever fires on something pathological.
     */
    private static final long MAX_CELLS = 9_000_000L;

    private ThreeWayMerge() {
    }

    /**
     * The merged lines, and whether any region could not be resolved.
     *
     * @param lines      the merge result, carrying conflict markers when {@code conflicted}
     * @param conflicted true when at least one region was changed by both sides
     */
    record Result(List<String> lines, boolean conflicted) {
    }

    /** Thrown when the inputs are too large to align within {@link #MAX_CELLS}. */
    static final class TooLargeToMergeException extends RuntimeException {
        TooLargeToMergeException(String message) {
            super(message);
        }
    }

    /**
     * Merge {@code mine} and {@code theirs} against the common ancestor {@code base}.
     *
     * <p>Any two of the three being equal short-circuits: if both sides agree, or only one side moved,
     * there is nothing to reconcile.
     */
    static Result merge(List<String> base, List<String> mine, List<String> theirs) {
        if (mine.equals(theirs)) {
            return new Result(List.copyOf(mine), false);
        }
        if (base.equals(mine)) {
            return new Result(List.copyOf(theirs), false);
        }
        if (base.equals(theirs)) {
            return new Result(List.copyOf(mine), false);
        }

        int[] mineAt = alignment(base, mine);
        int[] theirsAt = alignment(base, theirs);

        List<String> merged = new ArrayList<>();
        boolean conflicted = false;
        int mineIdx = 0;
        int theirsIdx = 0;
        int at = 0;
        while (at < base.size()) {
            if (mineAt[at] >= 0 && theirsAt[at] >= 0
                    && mineAt[at] == mineIdx && theirsAt[at] == theirsIdx) {
                // A line neither side touched: it is the anchor the chunks between conflicts hang off.
                merged.add(base.get(at));
                at++;
                mineIdx++;
                theirsIdx++;
                continue;
            }
            int anchor = nextAnchor(mineAt, theirsAt, at);
            int mineEnd = anchor < 0 ? mine.size() : mineAt[anchor];
            int theirsEnd = anchor < 0 ? theirs.size() : theirsAt[anchor];
            List<String> baseChunk = base.subList(at, anchor < 0 ? base.size() : anchor);
            List<String> mineChunk = mine.subList(mineIdx, mineEnd);
            List<String> theirsChunk = theirs.subList(theirsIdx, theirsEnd);

            if (mineChunk.equals(baseChunk)) {
                merged.addAll(theirsChunk);
            } else if (theirsChunk.equals(baseChunk)) {
                merged.addAll(mineChunk);
            } else if (mineChunk.equals(theirsChunk)) {
                merged.addAll(mineChunk);
            } else {
                conflict(merged, mineChunk, baseChunk, theirsChunk);
                conflicted = true;
            }
            at = anchor < 0 ? base.size() : anchor;
            mineIdx = mineEnd;
            theirsIdx = theirsEnd;
        }
        // Whatever trails the last anchor on either side still has to land somewhere.
        if (mineIdx < mine.size() || theirsIdx < theirs.size()) {
            List<String> mineTail = mine.subList(mineIdx, mine.size());
            List<String> theirsTail = theirs.subList(theirsIdx, theirs.size());
            if (mineTail.equals(theirsTail)) {
                merged.addAll(mineTail);
            } else if (mineTail.isEmpty()) {
                merged.addAll(theirsTail);
            } else if (theirsTail.isEmpty()) {
                merged.addAll(mineTail);
            } else {
                conflict(merged, mineTail, List.of(), theirsTail);
                conflicted = true;
            }
        }
        return new Result(merged, conflicted);
    }

    /** The next base index past {@code from} that both sides still have, or {@code -1}. */
    private static int nextAnchor(int[] mineAt, int[] theirsAt, int from) {
        for (int i = from + 1; i < mineAt.length; i++) {
            if (mineAt[i] >= 0 && theirsAt[i] >= 0) {
                return i;
            }
        }
        return -1;
    }

    private static void conflict(List<String> merged, List<String> mine, List<String> base, List<String> theirs) {
        merged.add("<<<<<<< your changes");
        merged.addAll(mine);
        merged.add("||||||| generated last time");
        merged.addAll(base);
        merged.add("=======");
        merged.addAll(theirs);
        merged.add(">>>>>>> regenerated");
    }

    /**
     * Longest common subsequence of the two line lists, as a map from {@code base} index to the
     * matching {@code other} index ({@code -1} when unmatched). Monotonic in both directions, which is
     * what lets {@link #merge} walk the two alignments together.
     */
    private static int[] alignment(List<String> base, List<String> other) {
        long cells = (long) (base.size() + 1) * (other.size() + 1);
        if (cells > MAX_CELLS) {
            throw new TooLargeToMergeException("cannot align " + base.size() + " x " + other.size()
                    + " lines (over " + MAX_CELLS + " cells)");
        }
        int[][] lcs = new int[base.size() + 1][other.size() + 1];
        for (int i = base.size() - 1; i >= 0; i--) {
            for (int j = other.size() - 1; j >= 0; j--) {
                lcs[i][j] = base.get(i).equals(other.get(j))
                        ? lcs[i + 1][j + 1] + 1
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        int[] at = new int[base.size()];
        java.util.Arrays.fill(at, -1);
        int i = 0;
        int j = 0;
        while (i < base.size() && j < other.size()) {
            if (base.get(i).equals(other.get(j))) {
                at[i] = j;
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                i++;
            } else {
                j++;
            }
        }
        return at;
    }
}
