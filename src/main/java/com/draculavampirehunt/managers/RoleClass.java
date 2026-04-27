package com.draculavampirehunt.managers;

public enum RoleClass {

    NONE("None"),
    VAMPIRE_STALKER("Vampire Stalker"),
    VAMPIRE_BRUTE("Vampire Brute"),
    HUNTER_TRACKER("Hunter Tracker"),
    HUNTER_PRIEST("Hunter Priest");

    private final String displayName;

    RoleClass(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * Resolves a player-typed string such as "stalker", "brute", "tracker", "priest"
     * (case-insensitive) to the matching {@link RoleClass}, or {@code null} if not recognised.
     */
    public static RoleClass fromInput(String input) {
        if (input == null) {
            return null;
        }
        return switch (input.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "stalker",  "vampire_stalker",  "vampirestalker"  -> VAMPIRE_STALKER;
            case "brute",   "vampire_brute",    "vampirebrute"    -> VAMPIRE_BRUTE;
            case "tracker", "hunter_tracker",   "huntertracker"   -> HUNTER_TRACKER;
            case "priest",  "hunter_priest",    "hunterpriest"    -> HUNTER_PRIEST;
            case "none", ""                                        -> NONE;
            default -> null;
        };
    }
}
