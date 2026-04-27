package com.draculavampirehunt.listeners;

import com.draculavampirehunt.DraculaVampireHunt;
import com.draculavampirehunt.managers.VampireHuntManager;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Appends a coloured role tag ([Vampire] / [Hunter] / [Spectator] / [Queue])
 * in front of any chat message sent by an event participant.
 *
 * Uses the modern Paper AsyncChatEvent + Adventure Component API
 * required for api-version 1.19+ (deprecated AsyncPlayerChatEvent removed).
 */
@SuppressWarnings("unstable")
public class EventChatListener implements Listener {

    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.legacySection();

    private final DraculaVampireHunt plugin;

    public EventChatListener(DraculaVampireHunt plugin) {
        this.plugin = plugin;
    }

    /**
     * HIGHEST priority so our format runs after most chat plugins but can
     * still be overridden by a MONITOR-priority chat-logger.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        VampireHuntManager manager = plugin.getVampireHuntManager();

        if (manager == null) {
            return;
        }

        if (!manager.isEventParticipant(player.getUniqueId())) {
            return;
        }

        // ----------------------------------------------------------------
        // Build the Adventure component role tag
        // ----------------------------------------------------------------

        Component tag;
        if (manager.isVampire(player.getUniqueId())) {
            tag = Component.text("[", NamedTextColor.DARK_GRAY)
                    .append(Component.text("Vampire", NamedTextColor.DARK_PURPLE, TextDecoration.BOLD))
                    .append(Component.text("] ", NamedTextColor.DARK_GRAY));

        } else if (manager.isHunter(player.getUniqueId())) {
            tag = Component.text("[", NamedTextColor.DARK_GRAY)
                    .append(Component.text("Hunter", NamedTextColor.AQUA, TextDecoration.BOLD))
                    .append(Component.text("] ", NamedTextColor.DARK_GRAY));

        } else if (manager.isSpectatorParticipant(player.getUniqueId())) {
            tag = Component.text("[", NamedTextColor.DARK_GRAY)
                    .append(Component.text("Spectator", NamedTextColor.GRAY))
                    .append(Component.text("] ", NamedTextColor.DARK_GRAY));

        } else {
            // In queue but not yet assigned a role
            tag = Component.text("[", NamedTextColor.DARK_GRAY)
                    .append(Component.text("Queue", NamedTextColor.YELLOW))
                    .append(Component.text("] ", NamedTextColor.DARK_GRAY));
        }

        // ----------------------------------------------------------------
        // Build "displayName: message" and prepend the tag
        // ----------------------------------------------------------------

        Component name = player.displayName();
        Component separator = Component.text(": ", NamedTextColor.DARK_GRAY);
        Component body = event.message().color(NamedTextColor.WHITE);

        event.renderer((source, sourceDisplayName, message, viewer) ->
                tag.append(name).append(separator).append(body)
        );
    }
}
