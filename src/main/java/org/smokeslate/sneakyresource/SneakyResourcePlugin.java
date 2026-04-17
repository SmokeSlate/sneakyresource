package org.smokeslate.sneakyresource;

import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Objects;

public final class SneakyResourcePlugin extends JavaPlugin implements CommandExecutor {
    private SyncService syncService;
    private SelfUpdateService selfUpdateService;
    private ResourcePackHttpServer resourcePackHttpServer;
    private SyncReport lastReport;
    private SelfUpdateReport lastSelfUpdateReport;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        this.syncService = new SyncService(this);
        this.selfUpdateService = new SelfUpdateService(this);
        this.resourcePackHttpServer = new ResourcePackHttpServer(this);

        try {
            this.resourcePackHttpServer.start();
        } catch (IOException exception) {
            getLogger().severe("Failed to start self-hosted resource pack server: " + exception.getMessage());
        }
        if (getConfig().getBoolean("nexo.enabled", true) && !isNexoIntegrationActive()) {
            getLogger().warning("Nexo is not installed or not enabled. SneakyResource will not provide custom items/blocks without Nexo.");
        }

        final PluginCommand command = Objects.requireNonNull(getCommand("sneakyresource"), "sneakyresource command is missing from plugin.yml");
        command.setExecutor(this);
        command.setTabCompleter(new SneakyResourceTabCompleter());
        getServer().getPluginManager().registerEvents(new PlayerResourcePackListener(this), this);

        if (getConfig().getBoolean("self-update.run-on-startup", false)) {
            getServer().getScheduler().runTaskAsynchronously(this, () -> runSelfUpdateTask(null, true));
        } else if (getConfig().getBoolean("sync-on-startup", true)) {
            runStartupSync();
        }
    }

    @Override
    public void onDisable() {
        if (this.resourcePackHttpServer != null) {
            this.resourcePackHttpServer.stop();
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
        if (this.resourcePackHttpServer != null) {
            this.resourcePackHttpServer.stop();
        }
        this.resourcePackHttpServer = new ResourcePackHttpServer(this);
        try {
            this.resourcePackHttpServer.start();
        } catch (IOException exception) {
            getLogger().warning("Failed to restart self-hosted resource pack server: " + exception.getMessage());
        }
        sender.sendMessage(Component.text("SneakyResource config reloaded."));
    }

    private void runSync(final CommandSender sender, final boolean allowReload) {
        try {
            this.lastReport = this.syncService.syncAll(allowReload);
            sendResourcePackToOnlinePlayers();
            sender.sendMessage(Component.text(this.lastReport.summaryLine()));
        } catch (Exception exception) {
            sender.sendMessage(Component.text("SneakyResource sync failed: " + exception.getMessage()));
            getLogger().warning("SneakyResource sync failed: " + exception.getMessage());
        }
    }

    private void runSelfUpdate(final CommandSender sender) {
        sender.sendMessage(Component.text("SneakyResource self-update started."));
        getServer().getScheduler().runTaskAsynchronously(this, () -> runSelfUpdateTask(sender, false));
    }

    private void showStatus(final CommandSender sender) {
        sender.sendMessage(Component.text("Plugin version: " + getPluginMeta().getVersion()));
        final String currentCommit = this.selfUpdateService.currentBuildCommit();
        if (currentCommit != null && !currentCommit.isBlank()) {
            sender.sendMessage(Component.text("Build commit: " + shortCommit(currentCommit)));
        }
        final String currentBranch = this.selfUpdateService.currentBuildBranch();
        if (currentBranch != null && !currentBranch.isBlank() && !"unknown".equalsIgnoreCase(currentBranch)) {
            sender.sendMessage(Component.text("Build branch: " + currentBranch));
        }

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

        sender.sendMessage(Component.text("Custom block integration: " + (isNexoIntegrationActive() ? "Nexo-managed" : "Unavailable")));
        sender.sendMessage(Component.text("Self-update branch: " + this.selfUpdateService.configuredUpdateBranch()));

        if (this.lastSelfUpdateReport == null) {
            sender.sendMessage(Component.text("No self-update has run yet."));
        } else {
            sender.sendMessage(Component.text(this.lastSelfUpdateReport.summaryLine()));
            sender.sendMessage(Component.text("Previous commit: " + this.lastSelfUpdateReport.previousCommit()));
            sender.sendMessage(Component.text("Current commit: " + this.lastSelfUpdateReport.currentCommit()));
            if (this.lastSelfUpdateReport.downloadedJar() != null) {
                sender.sendMessage(Component.text("Downloaded jar: " + this.lastSelfUpdateReport.downloadedJar()));
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

    private void runStartupSync() {
        try {
            this.lastReport = this.syncService.syncAll(true);
            sendResourcePackToOnlinePlayers();
            getLogger().info(this.lastReport.summaryLine());
        } catch (Exception exception) {
            getLogger().severe("Initial sync failed: " + exception.getMessage());
        }
    }

    private void runSelfUpdateTask(@Nullable final CommandSender sender, final boolean startup) {
        try {
            this.lastSelfUpdateReport = this.selfUpdateService.updateFromRepository();
            final String summary = this.lastSelfUpdateReport.summaryLine();
            getLogger().info(summary);
            sendResourcePackToOnlinePlayers();
            if (sender != null) {
                getServer().getScheduler().runTask(this, () -> sender.sendMessage(Component.text(summary)));
            }
            scheduleRestartIfNeeded(sender, this.lastSelfUpdateReport);
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            final String message = "SneakyResource self-update failed: " + exception.getMessage();
            getLogger().severe(message);
            if (sender != null) {
                getServer().getScheduler().runTask(this, () -> sender.sendMessage(Component.text(message)));
            }
            if (startup && getConfig().getBoolean("sync-on-startup", true)) {
                getLogger().warning("Falling back to startup sync after self-update failure.");
                runStartupSync();
            }
        }
    }

    private void scheduleRestartIfNeeded(@Nullable final CommandSender sender, final SelfUpdateReport report) {
        if (!report.updateAvailable() || report.deployedJar() == null) {
            return;
        }
        if (!getConfig().getBoolean("self-update.restart-after-update", true)) {
            return;
        }

        final int delaySeconds = Math.max(0, getConfig().getInt("self-update.restart-delay-seconds", 5));
        final String message = delaySeconds > 0
            ? "SneakyResource scheduled a restart in " + delaySeconds + " seconds to apply the updated plugin."
            : "SneakyResource is restarting the server now to apply the updated plugin.";

        getLogger().info(message);
        if (sender != null) {
            getServer().getScheduler().runTask(this, () -> sender.sendMessage(Component.text(message)));
        }

        getServer().getScheduler().runTaskLater(this, this::restartServer, delaySeconds * 20L);
    }

    private void restartServer() {
        final String configuredCommand = getConfig().getString("self-update.restart-command", "").trim();
        if (!configuredCommand.isBlank()) {
            final boolean handled = getServer().dispatchCommand(getServer().getConsoleSender(), configuredCommand);
            if (handled) {
                return;
            }
            getLogger().warning("Configured restart command did not execute successfully, shutting down instead: " + configuredCommand);
        }

        getServer().shutdown();
    }

    private String shortCommit(final String commit) {
        return commit.length() <= 12 ? commit : commit.substring(0, 12);
    }

    boolean shouldSendResourcePack() {
        return !isNexoIntegrationActive()
            && getConfig().getBoolean("resource-pack.enabled", true)
            && getConfig().getBoolean("resource-pack.send-on-join", true);
    }

    boolean sendResourcePackTo(final org.bukkit.entity.Player player) {
        if (player == null || !player.isOnline() || !shouldSendResourcePack()) {
            return false;
        }

        final SyncReport report = this.lastReport;
        final String url = resolvePackUrl(report);
        final String sha1 = report != null ? report.resourcePackSha1() : null;

        if (url == null || url.isBlank() || sha1 == null || sha1.isBlank()) {
            return false;
        }

        final String promptText = this.syncService.configuredPackPrompt();
        final Component prompt = promptText == null ? null : Component.text(promptText);
        player.setResourcePack(url, sha1, this.syncService.isPackRequired(), prompt);
        return true;
    }

    private void sendResourcePackToOnlinePlayers() {
        if (!shouldSendResourcePack()) {
            return;
        }
        getServer().getScheduler().runTask(this, () -> getServer().getOnlinePlayers().forEach(this::sendResourcePackTo));
    }

    @Nullable
    private String resolvePackUrl(@Nullable final SyncReport report) {
        if (report != null && report.resourcePackUrl() != null && !report.resourcePackUrl().isBlank()) {
            return report.resourcePackUrl();
        }

        final String configured = this.syncService.configuredPackUrl();
        if (configured != null && !configured.isBlank()) {
            return configured;
        }

        return null;
    }

    boolean isNexoIntegrationActive() {
        return getConfig().getBoolean("nexo.enabled", true)
            && getServer().getPluginManager().isPluginEnabled("Nexo");
    }
}
