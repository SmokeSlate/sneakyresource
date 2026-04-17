package org.smokeslate.sneakyresource;

import com.nexomc.nexo.api.NexoBlocks;
import com.nexomc.nexo.api.events.custom_block.NexoBlockBreakEvent;
import com.nexomc.nexo.api.events.custom_block.NexoBlockInteractEvent;
import com.nexomc.nexo.api.events.custom_block.NexoBlockPlaceEvent;
import com.nexomc.nexo.mechanics.custom_block.CustomBlockMechanic;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Objects;

final class NexoCustomBlockService implements CustomBlockService {
    private static final String STORAGE_FILE_NAME = "custom-blocks.yml";
    private static final String BLOCKS_ROOT = "blocks";
    private static final int CASHE_SIZE = 27;

    private final SneakyResourcePlugin plugin;
    private final Path storageFile;
    private final Map<BlockKey, ItemStack[]> casheInventories = new HashMap<>();

    NexoCustomBlockService(final SneakyResourcePlugin plugin) {
        this.plugin = plugin;
        this.storageFile = plugin.getDataFolder().toPath().resolve(STORAGE_FILE_NAME);
    }

    @Override
    public void load() {
        this.casheInventories.clear();

        if (!Files.isRegularFile(this.storageFile)) {
            return;
        }

        final YamlConfiguration config = YamlConfiguration.loadConfiguration(this.storageFile.toFile());
        final ConfigurationSection blocks = config.getConfigurationSection(BLOCKS_ROOT);
        if (blocks == null) {
            return;
        }

        for (final String key : blocks.getKeys(false)) {
            final ConfigurationSection section = blocks.getConfigurationSection(key);
            if (section == null) {
                continue;
            }

            final String worldName = section.getString("world", "");
            final World world = Bukkit.getWorld(worldName);
            if (world == null) {
                continue;
            }

            final BlockKey blockKey = new BlockKey(worldName, section.getInt("x"), section.getInt("y"), section.getInt("z"));
            final Block block = world.getBlockAt(blockKey.x(), blockKey.y(), blockKey.z());
            if (managedCustomBlockType(block) != ManagedCustomBlockType.CASHE) {
                continue;
            }

            this.casheInventories.put(blockKey, loadContents(section));
        }
    }

    @Override
    public void save() {
        final YamlConfiguration config = new YamlConfiguration();

        for (final Map.Entry<BlockKey, ItemStack[]> entry : this.casheInventories.entrySet()) {
            final BlockKey key = entry.getKey();
            final World world = Bukkit.getWorld(key.worldName());
            if (world == null) {
                continue;
            }

            if (managedCustomBlockType(world.getBlockAt(key.x(), key.y(), key.z())) != ManagedCustomBlockType.CASHE) {
                continue;
            }

            final String path = BLOCKS_ROOT + "." + key.serialized();
            config.set(path + ".world", key.worldName());
            config.set(path + ".x", key.x());
            config.set(path + ".y", key.y());
            config.set(path + ".z", key.z());
            config.set(path + ".contents", Arrays.asList(entry.getValue().clone()));
        }

        try {
            Files.createDirectories(this.storageFile.getParent());
            config.save(this.storageFile.toFile());
        } catch (IOException exception) {
            this.plugin.getLogger().severe("Failed to save custom blocks: " + exception.getMessage());
        }
    }

    @Override
    public String integrationName() {
        return "Nexo";
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onNexoBlockPlace(final NexoBlockPlaceEvent event) {
        if (managedCustomBlockType(event.getMechanic()) != ManagedCustomBlockType.CASHE) {
            return;
        }

        this.casheInventories.putIfAbsent(BlockKey.from(event.getBlock().getLocation()), new ItemStack[CASHE_SIZE]);
        save();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onNexoBlockInteract(final NexoBlockInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        if (managedCustomBlockType(event.getMechanic()) != ManagedCustomBlockType.CASHE) {
            return;
        }

        event.setCancelled(true);
        final BlockKey key = BlockKey.from(event.getBlock().getLocation());
        this.casheInventories.putIfAbsent(key, new ItemStack[CASHE_SIZE]);
        openCasheInventory(event.getPlayer(), key);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onNexoBlockBreak(final NexoBlockBreakEvent event) {
        if (managedCustomBlockType(event.getMechanic()) != ManagedCustomBlockType.CASHE) {
            return;
        }

        dropCasheContents(event.getBlock());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityExplode(final EntityExplodeEvent event) {
        handleExplodedBlocks(event.blockList());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockExplode(final BlockExplodeEvent event) {
        handleExplodedBlocks(event.blockList());
    }

    @EventHandler
    public void onInventoryClose(final InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof CasheInventoryHolder holder)) {
            return;
        }

        final ItemStack[] contents = new ItemStack[CASHE_SIZE];
        final ItemStack[] source = event.getInventory().getContents();
        System.arraycopy(source, 0, contents, 0, Math.min(source.length, contents.length));
        this.casheInventories.put(holder.key(), contents);
        save();
    }

    private void handleExplodedBlocks(final List<Block> blocks) {
        final List<Block> managedBlocks = new ArrayList<>();
        blocks.removeIf(block -> {
            if (managedCustomBlockType(block) == null) {
                return false;
            }
            managedBlocks.add(block);
            return true;
        });

        for (final Block block : managedBlocks) {
            final ManagedCustomBlockType type = managedCustomBlockType(block);
            if (type == null) {
                continue;
            }

            final boolean removed = NexoBlocks.remove(block.getLocation(), null, true);
            if (removed && type == ManagedCustomBlockType.CASHE) {
                dropCasheContents(block);
            }
        }
    }

    private void openCasheInventory(final Player player, final BlockKey key) {
        final Inventory inventory = Bukkit.createInventory(new CasheInventoryHolder(key), CASHE_SIZE, Component.text("Cashe"));
        final ItemStack[] contents = this.casheInventories.getOrDefault(key, new ItemStack[CASHE_SIZE]);
        for (int i = 0; i < Math.min(contents.length, inventory.getSize()); i++) {
            inventory.setItem(i, contents[i]);
        }
        player.openInventory(inventory);
    }

    private void dropCasheContents(final Block block) {
        final ItemStack[] contents = this.casheInventories.remove(BlockKey.from(block.getLocation()));
        if (contents == null) {
            return;
        }

        final Location dropLocation = block.getLocation().add(0.5, 0.5, 0.5);
        final World world = Objects.requireNonNull(dropLocation.getWorld(), "world");
        for (final ItemStack content : contents) {
            if (content != null && !content.getType().isAir()) {
                world.dropItemNaturally(dropLocation, content);
            }
        }
        save();
    }

    @Nullable
    private ManagedCustomBlockType managedCustomBlockType(final Block block) {
        final CustomBlockMechanic mechanic = NexoBlocks.customBlockMechanic(block);
        return mechanic == null ? null : ManagedCustomBlockType.fromItemId(mechanic.getItemID());
    }

    @Nullable
    private ManagedCustomBlockType managedCustomBlockType(final CustomBlockMechanic mechanic) {
        return ManagedCustomBlockType.fromItemId(mechanic.getItemID());
    }

    private ItemStack[] loadContents(final ConfigurationSection section) {
        final List<?> rawContents = section.getList("contents", List.of());
        final ItemStack[] contents = new ItemStack[CASHE_SIZE];
        for (int i = 0; i < Math.min(rawContents.size(), contents.length); i++) {
            final Object entry = rawContents.get(i);
            if (entry instanceof ItemStack stack) {
                contents[i] = stack;
            }
        }
        return contents;
    }

    private record BlockKey(String worldName, int x, int y, int z) {
        static BlockKey from(final Location location) {
            return new BlockKey(
                Objects.requireNonNull(location.getWorld(), "world").getName(),
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ()
            );
        }

        String serialized() {
            return this.worldName + "_" + this.x + "_" + this.y + "_" + this.z;
        }
    }

    private enum ManagedCustomBlockType {
        CASHE("cashe"),
        ROCK("rock"),
        ROCK_SMALL_1("rock_small_1"),
        ROCK_SMALL_2("rock_small_2"),
        ROCK_SMALL_3("rock_small_3");

        private static final Map<String, ManagedCustomBlockType> BY_ITEM_ID = Arrays.stream(values())
            .collect(HashMap::new, (map, type) -> map.put(type.itemId, type), HashMap::putAll);

        private final String itemId;

        ManagedCustomBlockType(final String itemId) {
            this.itemId = itemId;
        }

        @Nullable
        static ManagedCustomBlockType fromItemId(@Nullable final String itemId) {
            return itemId == null ? null : BY_ITEM_ID.get(itemId);
        }
    }

    private record CasheInventoryHolder(BlockKey key) implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return Bukkit.createInventory(this, CASHE_SIZE, Component.text("Cashe"));
        }
    }
}
