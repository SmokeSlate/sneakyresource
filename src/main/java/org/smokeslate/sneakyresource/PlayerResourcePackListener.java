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
        if (!this.plugin.shouldSendResourcePack()) {
            return;
        }

        final long delayTicks = Math.max(0L, this.plugin.getConfig().getLong("resource-pack.send-delay-ticks", 40L));
        this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> {
            final boolean sent = this.plugin.sendResourcePackTo(event.getPlayer());
            if (!sent) {
                final long retryTicks = Math.max(0L, this.plugin.getConfig().getLong("resource-pack.retry-if-pending-ticks", 100L));
                if (retryTicks > 0L) {
                    this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> this.plugin.sendResourcePackTo(event.getPlayer()), retryTicks);
                }
            }
        }, delayTicks);
    }
}
