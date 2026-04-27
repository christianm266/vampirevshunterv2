package com.draculavampirehunt.managers;

/**
 * Represents every lifecycle phase of a Vampire Hunt round.
 *
 * <pre>
 * IDLE  →  QUEUEING  →  READY_CHECK  →  COUNTDOWN  →  ACTIVE  →  SUDDEN_DEATH  →  ENDING  →  IDLE
 * </pre>
 */
public enum EventPhase {

    /** No event running, lobby empty. */
    IDLE,

    /** Players are joining the queue but the minimum has not yet been reached, or ready-check is not started. */
    QUEUEING,

    /** Minimum players are in queue; waiting for all to press /vhunt ready. */
    READY_CHECK,

    /** All players ready; countdown timer is ticking down to start. */
    COUNTDOWN,

    /** Round is running normally. */
    ACTIVE,

    /** Timer ran out; Blood Moon overtime – lasts until one side is eliminated. */
    SUDDEN_DEATH,

    /** Round is being cleaned up after a winner is determined. */
    ENDING
}
