package org.smokeslate.sneakyresource;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class ReservedStateCustomBlockService implements CustomBlockService {
    private static final String STORAGE_FILE_NAME = "custom-blocks.yml";
    private static final String BLOCKS_ROOT = "blocks";
    private static final int CASHE_SIZE = 27;

    private final SneakyResourcePlugin plugin;
    private final Path storageFile;
    private final Map<BlockKey, ItemStack[]> casheInventories = new HashMap<>();

    ReservedStateCustomBlockService(final SneakyResourcePlugin plugin) {
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
            this.casheInventories.put(blockKey, loadContents(section));
            final Block block = world.getBlockAt(blockKey.x(), blockKey.y(), blockKey.z());
            if (customBlockType(block) != CustomBlockType.CASHE) {
                continue;
            }
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

            if (customBlockType(world.getBlockAt(key.x(), key.y(), key.z())) != CustomBlockType.CASHE) {
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
        return "Reserved States";
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerInteract(final PlayerInteractEvent event) {
        final Block clickedBlock = event.getClickedBlock();
        if (clickedBlock == null) {
            return;
        }

        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        final CustomBlockType clickedType = customBlockType(clickedBlock);
        if (clickedType == CustomBlockType.CASHE && event.getHand() == EquipmentSlot.HAND) {
            event.setCancelled(true);
            final BlockKey key = BlockKey.from(clickedBlock.getLocation());
            this.casheInventories.putIfAbsent(key, new ItemStack[CASHE_SIZE]);
            openCasheInventory(event.getPlayer(), key);
            return;
        }

        final ItemStack item = event.getItem();
        final CustomBlockType type = detectCustomBlockType(item);
        if (type == null) {
            return;
        }

        final Block targetBlock = clickedBlock.getRelative(event.getBlockFace());
        if (!canPlaceAt(targetBlock)) {
            return;
        }

        event.setCancelled(true);
        targetBlock.setType(type.hostMaterial(), false);
        targetBlock.setBlockData(type.createBlockData(), false);
        if (customBlockType(targetBlock) != type) {
            return;
        }

        if (type == CustomBlockType.CASHE) {
            this.casheInventories.putIfAbsent(BlockKey.from(targetBlock.getLocation()), new ItemStack[CASHE_SIZE]);
            save();
        }

        consumePlacedItem(event.getPlayer(), event.getHand(), item);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockBreak(final BlockBreakEvent event) {
        final CustomBlockType type = customBlockType(event.getBlock());
        if (type == null) {
            return;
        }

        event.setDropItems(false);
        event.setCancelled(true);
        breakCustomBlock(event.getBlock(), type);
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

    private void openCasheInventory(final Player player, final BlockKey key) {
        final Inventory inventory = Bukkit.createInventory(new CasheInventoryHolder(key), CASHE_SIZE, Component.text("Cashe"));
        final ItemStack[] contents = this.casheInventories.getOrDefault(key, new ItemStack[CASHE_SIZE]);
        for (int i = 0; i < Math.min(contents.length, inventory.getSize()); i++) {
            inventory.setItem(i, contents[i]);
        }
        player.openInventory(inventory);
    }

    private void breakCustomBlock(final Block block, final CustomBlockType type) {
        block.setType(Material.AIR, false);

        final Location dropLocation = block.getLocation().add(0.5, 0.5, 0.5);
        final World world = Objects.requireNonNull(dropLocation.getWorld(), "world");
        world.dropItemNaturally(dropLocation, type.createVanillaItem());

        if (type == CustomBlockType.CASHE) {
            final ItemStack[] contents = this.casheInventories.remove(BlockKey.from(block.getLocation()));
            if (contents != null) {
                for (final ItemStack content : contents) {
                    if (content != null && content.getType() != Material.AIR) {
                        world.dropItemNaturally(dropLocation, content);
                    }
                }
            }
            save();
        }
    }

    private void handleExplodedBlocks(final Collection<Block> blocks) {
        final List<Block> customBlocks = new ArrayList<>();
        for (final Block block : blocks) {
            if (customBlockType(block) != null) {
                customBlocks.add(block);
            }
        }

        for (final Block block : customBlocks) {
            final CustomBlockType type = customBlockType(block);
            if (type != null) {
                breakCustomBlock(block, type);
            }
        }
    }

    private boolean canPlaceAt(final Block targetBlock) {
        return targetBlock.getType().isAir() || targetBlock.isReplaceable();
    }

    private void consumePlacedItem(final Player player, @Nullable final EquipmentSlot hand, final ItemStack item) {
        if (player.getGameMode() == GameMode.CREATIVE || hand == null) {
            return;
        }

        final int newAmount = item.getAmount() - 1;
        if (newAmount > 0) {
            item.setAmount(newAmount);
            return;
        }

        final PlayerInventory inventory = player.getInventory();
        if (hand == EquipmentSlot.HAND) {
            inventory.setItemInMainHand(null);
        } else if (hand == EquipmentSlot.OFF_HAND) {
            inventory.setItemInOffHand(null);
        }
    }

    @Nullable
    private CustomBlockType detectCustomBlockType(@Nullable final ItemStack stack) {
        if (stack == null) {
            return null;
        }

        final ItemMeta meta = stack.getItemMeta();
        if (meta == null || !meta.hasItemModel()) {
            return null;
        }

        final NamespacedKey itemModel = meta.getItemModel();
        if (itemModel == null || !"sasquatch".equals(itemModel.getNamespace())) {
            return null;
        }

        return CustomBlockType.fromItemModel(itemModel.getKey());
    }

    @Nullable
    private CustomBlockType customBlockType(final Block block) {
        for (final CustomBlockType type : CustomBlockType.values()) {
            if (type.matches(block)) {
                return type;
            }
        }
        return null;
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

    private enum CustomBlockType {
        CASHE(
            "cashe",
            Material.TRAPPED_CHEST,
            "Cashe",
            NamedTextColor.RED,
            "Custom container item for 1.21.11+",
            NamedTextColor.GRAY,
            Material.JIGSAW,
            "minecraft:jigsaw[orientation=up_north]"
        ),
        ROCK(
            "rock",
            Material.FLINT,
            "Rock",
            NamedTextColor.GRAY,
            "Decorative stone",
            NamedTextColor.DARK_GRAY,
            Material.PINK_PETALS,
            "minecraft:pink_petals[flower_amount=1,facing=west]"
        ),
        ROCK_SMALL_1(
            "rock_small_1",
            Material.FLINT,
            "Small Rock 1",
            NamedTextColor.GRAY,
            "Decorative stone",
            NamedTextColor.DARK_GRAY,
            Material.PINK_PETALS,
            "minecraft:pink_petals[flower_amount=2,facing=west]"
        ),
        ROCK_SMALL_2(
            "rock_small_2",
            Material.FLINT,
            "Small Rock 2",
            NamedTextColor.GRAY,
            "Decorative stone",
            NamedTextColor.DARK_GRAY,
            Material.PINK_PETALS,
            "minecraft:pink_petals[flower_amount=3,facing=west]"
        ),
        ROCK_SMALL_3(
            "rock_small_3",
            Material.FLINT,
            "Small Rock 3",
            NamedTextColor.GRAY,
            "Decorative stone",
            NamedTextColor.DARK_GRAY,
            Material.PINK_PETALS,
            "minecraft:pink_petals[flower_amount=4,facing=west]"
        );

        private final String itemModel;
        private final Material itemMaterial;
        private final String displayName;
        private final NamedTextColor displayColor;
        private final String loreText;
        private final NamedTextColor loreColor;
        private final Material hostMaterial;
        private final String blockDataString;

        CustomBlockType(
            final String itemModel,
            final Material itemMaterial,
            final String displayName,
            final NamedTextColor displayColor,
            final String loreText,
            final NamedTextColor loreColor,
            final Material hostMaterial,
            final String blockDataString
        ) {
            this.itemModel = itemModel;
            this.itemMaterial = itemMaterial;
            this.displayName = displayName;
            this.displayColor = displayColor;
            this.loreText = loreText;
            this.loreColor = loreColor;
            this.hostMaterial = hostMaterial;
            this.blockDataString = blockDataString;
        }

        Material hostMaterial() {
            return this.hostMaterial;
        }

        BlockData createBlockData() {
            return Bukkit.createBlockData(this.blockDataString);
        }

        boolean matches(final Block block) {
            return block.getType() == this.hostMaterial && block.getBlockData().matches(createBlockData());
        }

        ItemStack createVanillaItem() {
            final ItemStack stack = new ItemStack(this.itemMaterial);
            final ItemMeta meta = stack.getItemMeta();
            meta.setItemModel(new NamespacedKey("sasquatch", this.itemModel));
            meta.itemName(Component.text(this.displayName, this.displayColor).decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(Component.text(this.loreText, this.loreColor).decoration(TextDecoration.ITALIC, false)));
            stack.setItemMeta(meta);
            return stack;
        }

        @Nullable
        static CustomBlockType fromItemModel(final String itemModel) {
            for (final CustomBlockType type : values()) {
                if (type.itemModel.equals(itemModel)) {
                    return type;
                }
            }
            return null;
        }
    }

    private record CasheInventoryHolder(BlockKey key) implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return Bukkit.createInventory(this, CASHE_SIZE, Component.text("Cashe"));
        }
    }
}
