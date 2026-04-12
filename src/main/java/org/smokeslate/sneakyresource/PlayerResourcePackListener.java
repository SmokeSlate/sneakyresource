package org.smokeslate.sneakyresource;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.jetbrains.annotations.Nullable;

final class PlayerResourcePackListener implements Listener {
    private final SneakyResourcePlugin plugin;

    PlayerResourcePackListener(final SneakyResourcePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(final PlayerJoinEvent event) {
        if (!this.plugin.getConfig().getBoolean("resource-pack.enabled", true)) {
            return;
        }
        if (!this.plugin.getConfig().getBoolean("resource-pack.send-on-join", true)) {
            return;
        }

        final SyncReport report = this.plugin.getLastReport();
        final String url = report != null && report.resourcePackUrl() != null
            ? report.resourcePackUrl()
            : this.plugin.getSyncService().configuredPackUrl();
        final String sha1 = report != null ? report.resourcePackSha1() : null;

        if (url == null || url.isBlank() || sha1 == null || sha1.isBlank()) {
            return;
        }

        sendPack(event.getPlayer(), url, sha1, this.plugin.getSyncService().isPackRequired(), this.plugin.getSyncService().configuredPackPrompt());
    }

    private void sendPack(final Player player, final String url, final String sha1, final boolean required, @Nullable final String promptText) {
        final Component prompt = promptText == null ? null : Component.text(promptText);
        player.setResourcePack(url, sha1, required, prompt);
    }
}
