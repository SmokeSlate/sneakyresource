package org.smokeslate.sneakyresource;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.List;
import java.util.Locale;

final class SneakyResourceTabCompleter implements TabCompleter {
    private static final List<String> SUBCOMMANDS = List.of("sync", "reload", "update", "status");

    @Override
    public List<String> onTabComplete(final CommandSender sender, final Command command, final String alias, final String[] args) {
        if (args.length != 1) {
            return List.of();
        }

        final String prefix = args[0].toLowerCase(Locale.ROOT);
        return SUBCOMMANDS.stream()
            .filter(value -> value.startsWith(prefix))
            .toList();
    }
}
