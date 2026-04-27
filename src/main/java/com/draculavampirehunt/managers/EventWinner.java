package com.draculavampirehunt.managers;

/**
 * Indicates which side, if any, won a completed Vampire Hunt round.
 */
public enum EventWinner {

    /** Hunters eliminated all vampires. */
    HUNTERS,

    /** Vampires converted or outlasted all hunters. */
    VAMPIRES,

    /** Round ended with no clear winner (forced stop, empty arena, etc.). */
    NONE
}
