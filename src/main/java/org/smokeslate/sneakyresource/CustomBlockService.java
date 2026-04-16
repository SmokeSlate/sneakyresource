package org.smokeslate.sneakyresource;

import org.bukkit.event.Listener;

interface CustomBlockService extends Listener {
    void load();

    void save();

    String integrationName();
}
