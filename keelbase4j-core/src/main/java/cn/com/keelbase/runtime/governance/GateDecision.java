// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.governance;

/** The runtime's decision for a tool call, derived from the risk strategy. */
public enum GateDecision {
    /** Execute immediately (R0/R1, and R2 unless policy denies). */
    ALLOW,
    /** Do not execute until a human confirms (R3). */
    CONFIRM,
    /** Do not execute until an approval is granted by a second party (R4). */
    REQUIRE_APPROVAL,
    /** Never execute (R5). */
    BLOCK
}
