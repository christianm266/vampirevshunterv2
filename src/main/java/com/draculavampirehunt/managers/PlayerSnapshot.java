package com.draculavampirehunt.managers;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Immutable snapshot of a player's pre-event state so it can be fully restored
 * when they leave or the event ends.
 */
public final class PlayerSnapshot {

    private final Location location;
    private final GameMode gameMode;
    private final ItemStack[] inventoryContents;
    private final ItemStack[] armorContents;
    private final ItemStack offHandItem;
    private final double health;
    private final int foodLevel;
    private final float saturation;
    private final List<PotionEffect> potionEffects;
    private final int level;
    private final float exp;
    private final boolean allowFlight;
    private final boolean flying;
    private final int fireTicks;

    public PlayerSnapshot(
            Location location,
            GameMode gameMode,
            ItemStack[] inventoryContents,
            ItemStack[] armorContents,
            ItemStack offHandItem,
            double health,
            int foodLevel,
            float saturation,
            Collection<PotionEffect> potionEffects,
            int level,
            float exp,
            boolean allowFlight,
            boolean flying,
            int fireTicks
    ) {
        this.location          = location;
        this.gameMode          = gameMode;
        this.inventoryContents = inventoryContents != null ? inventoryContents.clone() : new ItemStack[0];
        this.armorContents     = armorContents     != null ? armorContents.clone()     : new ItemStack[0];
        this.offHandItem       = offHandItem;
        this.health            = health;
        this.foodLevel         = foodLevel;
        this.saturation        = saturation;
        this.potionEffects     = potionEffects != null
                ? Collections.unmodifiableList(new java.util.ArrayList<>(potionEffects))
                : Collections.emptyList();
        this.level             = level;
        this.exp               = exp;
        this.allowFlight       = allowFlight;
        this.flying            = flying;
        this.fireTicks         = fireTicks;
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public Location getLocation()            { return location; }
    public GameMode getGameMode()            { return gameMode; }
    public ItemStack[] getInventoryContents(){
        return inventoryContents.clone();
    }
    public ItemStack[] getArmorContents()   {
        return armorContents.clone();
    }
    public ItemStack getOffHandItem()        { return offHandItem; }
    public double getHealth()                { return health; }
    public int getFoodLevel()                { return foodLevel; }
    public float getSaturation()             { return saturation; }
    public List<PotionEffect> getPotionEffects() { return potionEffects; }
    public int getLevel()                    { return level; }
    public float getExp()                    { return exp; }
    public boolean isAllowFlight()           { return allowFlight; }
    public boolean isFlying()                { return flying; }
    public int getFireTicks()                { return fireTicks; }
}
