package com.draculavampirehunt.managers;

import com.draculavampirehunt.DraculaVampireHunt;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;

public class EconomyService {

    private final DraculaVampireHunt plugin;
    private Economy economy;

    public EconomyService(DraculaVampireHunt plugin) {
        this.plugin = plugin;
    }

    public void setEconomy(Economy economy) {
        this.economy = economy;
    }

    public Economy getEconomy() {
        return economy;
    }

    public boolean isAvailable() {
        return economy != null;
    }

    public EconomyResponse deposit(OfflinePlayer player, double amount) {
        if (economy == null || player == null || amount <= 0.0D) {
            return null;
        }

        try {
            return economy.depositPlayer(player, amount);
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Vault deposit failed for " + (player.getName() == null ? player.getUniqueId() : player.getName()) + ": " + throwable.getMessage());
            return null;
        }
    }

    public String format(double amount) {
        if (economy == null) {
            return String.valueOf(amount);
        }

        try {
            return economy.format(amount);
        } catch (Throwable throwable) {
            return String.valueOf(amount);
        }
    }
}