package org.smokeslate.sneakyresource;

import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.Objects;

public final class SneakyResourcePlugin extends JavaPlugin implements CommandExecutor {
    private SyncService syncService;
    private SelfUpdateService selfUpdateService;
    private SyncReport lastReport;
    private SelfUpdateReport lastSelfUpdateReport;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.syncService = new SyncService(this);
        this.selfUpdateService = new SelfUpdateService(this);
        Objects.requireNonNull(getCommand("sneakyresource"), "sneakyresource command is missing from plugin.yml")
            .setExecutor(this);
        getServer().getPluginManager().registerEvents(new PlayerResourcePackListener(this), this);

        if (getConfig().getBoolean("self-update.run-on-startup", false)) {
            getServer().getScheduler().runTaskAsynchronously(this, () -> {
                try {
                    this.lastSelfUpdateReport = this.selfUpdateService.updateFromRepository();
                    getLogger().info(this.lastSelfUpdateReport.summaryLine());
                } catch (IOException | InterruptedException exception) {
                    if (exception instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    getLogger().severe("Self-update failed: " + exception.getMessage());
                }
            });
        } else if (getConfig().getBoolean("sync-on-startup", true)) {
            try {
                this.lastReport = this.syncService.syncAll(true);
                getLogger().info(this.lastReport.summaryLine());
            } catch (IOException exception) {
                getLogger().severe("Initial sync failed: " + exception.getMessage());
            }
        }
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label, final String[] args) {
        final String subcommand = args.length == 0 ? "sync" : args[0].toLowerCase();

        switch (subcommand) {
            case "sync" -> runSync(sender, true);
            case "reload" -> reloadPluginConfig(sender);
            case "update" -> runSelfUpdate(sender);
            case "status" -> showStatus(sender);
            default -> sender.sendMessage(Component.text("Usage: /sneakyresource <sync|reload|update|status>"));
        }
        return true;
    }

    private void reloadPluginConfig(final CommandSender sender) {
        reloadConfig();
        this.syncService = new SyncService(this);
        this.selfUpdateService = new SelfUpdateService(this);
        sender.sendMessage(Component.text("SneakyResource config reloaded."));
    }

    private void runSync(final CommandSender sender, final boolean allowReload) {
        try {
            this.lastReport = this.syncService.syncAll(allowReload);
            sender.sendMessage(Component.text(this.lastReport.summaryLine()));
        } catch (IOException exception) {
            sender.sendMessage(Component.text("SneakyResource sync failed: " + exception.getMessage()));
            getLogger().warning("SneakyResource sync failed: " + exception.getMessage());
        }
    }

    private void runSelfUpdate(final CommandSender sender) {
        sender.sendMessage(Component.text("SneakyResource self-update started."));
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                this.lastSelfUpdateReport = this.selfUpdateService.updateFromRepository();
                getServer().getScheduler().runTask(this, () -> sender.sendMessage(Component.text(this.lastSelfUpdateReport.summaryLine())));
            } catch (IOException | InterruptedException exception) {
                if (exception instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                final String message = "SneakyResource self-update failed: " + exception.getMessage();
                getLogger().warning(message);
                getServer().getScheduler().runTask(this, () -> sender.sendMessage(Component.text(message)));
            }
        });
    }

    private void showStatus(final CommandSender sender) {
        if (this.lastReport == null) {
            sender.sendMessage(Component.text("No sync has run yet."));
        } else {
            sender.sendMessage(Component.text(this.lastReport.summaryLine()));
            if (this.lastReport.resourcePackZip() != null) {
                sender.sendMessage(Component.text("Pack zip: " + this.lastReport.resourcePackZip()));
            }
            if (this.lastReport.resourcePackSha1() != null) {
                sender.sendMessage(Component.text("Pack sha1: " + this.lastReport.resourcePackSha1()));
            }
            if (this.lastReport.resourcePackUrl() != null) {
                sender.sendMessage(Component.text("Pack url: " + this.lastReport.resourcePackUrl()));
            }
            if (this.lastReport.datapackDestination() != null) {
                sender.sendMessage(Component.text("Datapack: " + this.lastReport.datapackDestination()));
            }
        }

        if (this.lastSelfUpdateReport == null) {
            sender.sendMessage(Component.text("No self-update has run yet."));
        } else {
            sender.sendMessage(Component.text(this.lastSelfUpdateReport.summaryLine()));
            sender.sendMessage(Component.text("Previous commit: " + this.lastSelfUpdateReport.previousCommit()));
            sender.sendMessage(Component.text("Current commit: " + this.lastSelfUpdateReport.currentCommit()));
            if (this.lastSelfUpdateReport.builtJar() != null) {
                sender.sendMessage(Component.text("Built jar: " + this.lastSelfUpdateReport.builtJar()));
            }
            if (this.lastSelfUpdateReport.deployedJar() != null) {
                sender.sendMessage(Component.text("Staged update jar: " + this.lastSelfUpdateReport.deployedJar()));
            }
        }
    }

    SyncService getSyncService() {
        return this.syncService;
    }

    SelfUpdateService getSelfUpdateService() {
        return this.selfUpdateService;
    }

    SyncReport getLastReport() {
        return this.lastReport;
    }

    void setLastReport(final SyncReport lastReport) {
        this.lastReport = lastReport;
    }
}
