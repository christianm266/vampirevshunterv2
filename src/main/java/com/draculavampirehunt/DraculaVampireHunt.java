package com.draculavampirehunt;

import com.draculavampirehunt.commands.VampireHuntAdminCommand;
import com.draculavampirehunt.commands.VampireHuntCommand;
import com.draculavampirehunt.listeners.EventChatListener;
import com.draculavampirehunt.listeners.EventCombatListener;
import com.draculavampirehunt.listeners.EventDeathListener;
import com.draculavampirehunt.listeners.EventRestrictionListener;
import com.draculavampirehunt.listeners.PlayerJoinRestoreListener;
import com.draculavampirehunt.listeners.PlayerQuitRestoreListener;
import com.draculavampirehunt.listeners.PlayerRespawnListener;
import com.draculavampirehunt.managers.ChatManager;
import com.draculavampirehunt.managers.EconomyService;
import com.draculavampirehunt.managers.EventArenaManager;
import com.draculavampirehunt.managers.EventStatsManager;
import com.draculavampirehunt.managers.VampireHuntManager;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public class DraculaVampireHunt extends JavaPlugin {

    private static DraculaVampireHunt instance;

    private ChatManager chatManager;
    private EconomyService economyService;
    private EventArenaManager eventArenaManager;
    private EventStatsManager eventStatsManager;
    private VampireHuntManager vampireHuntManager;

    private Economy vaultEconomy;

    @Override
    public void onEnable() {
        instance = this;

        saveDefaultConfig();
        reloadConfig();

        this.chatManager = new ChatManager(this);
        this.economyService = new EconomyService(this);
        this.eventArenaManager = new EventArenaManager(this);
        this.eventStatsManager = new EventStatsManager(this);
        this.vampireHuntManager = new VampireHuntManager(this);

        setupVaultEconomy();
        registerCommands();
        registerListeners();

        getLogger().info("DraculaVampireHunt enabled successfully.");
        if (vaultEconomy != null) {
            getLogger().info("Vault economy hooked: " + vaultEconomy.getName());
        } else {
            getLogger().warning("Vault economy not found. Economy rewards will be disabled.");
        }
    }

    @Override
    public void onDisable() {
        if (vampireHuntManager != null) {
            vampireHuntManager.shutdown();
        }

        if (eventStatsManager != null) {
            eventStatsManager.save();
        }

        saveConfig();
        getLogger().info("DraculaVampireHunt disabled successfully.");
    }

    private void registerCommands() {
        VampireHuntCommand playerCommand = new VampireHuntCommand(this);
        VampireHuntAdminCommand adminCommand = new VampireHuntAdminCommand(this);

        PluginCommand vhunt = getCommand("vhunt");
        if (vhunt != null) {
            vhunt.setExecutor(playerCommand);
            vhunt.setTabCompleter(playerCommand);
        } else {
            getLogger().severe("Command 'vhunt' is missing from plugin.yml");
        }

        PluginCommand vhadmin = getCommand("vhadmin");
        if (vhadmin != null) {
            vhadmin.setExecutor(adminCommand);
            vhadmin.setTabCompleter(adminCommand);
        } else {
            getLogger().severe("Command 'vhadmin' is missing from plugin.yml");
        }
    }

    private void registerListeners() {
        Bukkit.getPluginManager().registerEvents(new EventRestrictionListener(this), this);
        Bukkit.getPluginManager().registerEvents(new EventCombatListener(this), this);
        Bukkit.getPluginManager().registerEvents(new EventDeathListener(this), this);
        Bukkit.getPluginManager().registerEvents(new PlayerRespawnListener(this), this);
        Bukkit.getPluginManager().registerEvents(new PlayerQuitRestoreListener(this), this);
        Bukkit.getPluginManager().registerEvents(new PlayerJoinRestoreListener(this), this);
        Bukkit.getPluginManager().registerEvents(new EventChatListener(this), this);
    }

    private void setupVaultEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            vaultEconomy = null;
            economyService.setEconomy(null);
            return;
        }

        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            vaultEconomy = null;
            economyService.setEconomy(null);
            return;
        }

        vaultEconomy = rsp.getProvider();
        economyService.setEconomy(vaultEconomy);
    }

    public static DraculaVampireHunt getInstance() {
        return instance;
    }

    public ChatManager getChatManager() {
        return chatManager;
    }

    public EconomyService getEconomyService() {
        return economyService;
    }

    public EventArenaManager getEventArenaManager() {
        return eventArenaManager;
    }

    public EventStatsManager getEventStatsManager() {
        return eventStatsManager;
    }

    public VampireHuntManager getVampireHuntManager() {
        return vampireHuntManager;
    }

    public Economy getVaultEconomy() {
        return vaultEconomy;
    }
}
