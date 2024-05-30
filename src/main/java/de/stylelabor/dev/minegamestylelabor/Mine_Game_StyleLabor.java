package de.stylelabor.dev.minegamestylelabor;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.*;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.*;


public final class Mine_Game_StyleLabor extends JavaPlugin implements Listener, TabCompleter {

    private Player setupPlayer = null;
    private Location corner1 = null;
    private Location corner2 = null;
    private long lastClickTime = 0;
    private FileConfiguration scoreboardConfig;

    @Override
    public void onEnable() {
        // Ensures that a config.yml file exists. If it doesn't, the plugin copies the default one included in the JAR file.
        saveDefaultConfig();

        // Load corners from data.yml
        File dataFile = new File(getDataFolder(), "data.yml");
        if (dataFile.exists()) {
            YamlConfiguration dataConfig = YamlConfiguration.loadConfiguration(dataFile);
            String corner1String = dataConfig.getString("corner1");
            String corner2String = dataConfig.getString("corner2");
            if (corner1String != null) {
                corner1 = stringToLocation(corner1String);
            }
            if (corner2String != null) {
                corner2 = stringToLocation(corner2String);
            }
        }

        // Plugin startup logic
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("stylelabormine")).setExecutor(this);
        Objects.requireNonNull(getCommand("stylelabormine")).setTabCompleter(this);

        // Load scoreboard.yml
        File scoreboardFile = new File(getDataFolder(), "scoreboard.yml");
        if (!scoreboardFile.exists()) {
            saveResource("scoreboard.yml", false);
        }
        scoreboardConfig = YamlConfiguration.loadConfiguration(scoreboardFile);

        // Register the PlayerJoinEvent
        getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onPlayerJoin(PlayerJoinEvent event) {
                System.out.println("Player joined: " + event.getPlayer().getName()); // Debug message
                updateScoreboard(event.getPlayer());
            }
        }, this);

    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, Command command, @NotNull String label, String[] args) {
        if (command.getName().equalsIgnoreCase("stylelabormine")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("This command can only be run by a player.");
                return true;
            }

            Player player = (Player) sender;

            if (args.length > 0) {
                if (args[0].equalsIgnoreCase("setup")) {
                    if (player.isOp()) {
                        setupPlayer = player;
                        ItemStack item = new ItemStack(Material.DIAMOND_AXE);
                        ItemMeta meta = item.getItemMeta();

                        if (meta != null) {
                            meta.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + "StyleLabor Mine - Setup");
                            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
                            item.setItemMeta(meta);
                        }

                        if (player.getInventory().firstEmpty() != -1) {
                            player.getInventory().addItem(item);
                            player.sendMessage("You received the setup tool. Left and right click to define the corners of the mine.");
                        } else {
                            player.sendMessage("Your inventory is full. Please clear some space and try again.");
                        }
                    } else {
                        player.sendMessage("You do not have permission to perform this command.");
                    }
                    return true;
                }

                if (args[0].equalsIgnoreCase("finish")) {
                    if (setupPlayer == null) {
                        player.sendMessage("You need to start the setup process first.");
                        return true;
                    }

                    if (player.equals(setupPlayer)) {
                        // Clear the setup tool
                        ItemStack itemInHand = player.getInventory().getItemInMainHand();
                        if (itemInHand.getType() == Material.DIAMOND_AXE && Objects.requireNonNull(itemInHand.getItemMeta()).getDisplayName().equals(ChatColor.GOLD + "" + ChatColor.BOLD + "StyleLabor Mine - Setup")) {
                            player.getInventory().remove(itemInHand);
                        }

                        // Set the region defined by the corners to air and then fill it with blocks based on the percentages in config.yml
                        if (corner1 != null && corner2 != null) {
                            // Get the block types and their percentages from config.yml
                            ConfigurationSection blocksSection = getConfig().getConfigurationSection("blocks");
                            if (blocksSection != null) {
                                @SuppressWarnings("DuplicatedCode") List<Material> materials = new ArrayList<>();
                                for (String key : blocksSection.getKeys(false)) {
                                    Material material = Material.getMaterial(key);
                                    int percentage = blocksSection.getInt(key);
                                    for (int i = 0; i < percentage; i++) {
                                        materials.add(material);
                                    }
                                }

                                // Fill the region with blocks
                                for (int x = Math.min(corner1.getBlockX(), corner2.getBlockX()); x <= Math.max(corner1.getBlockX(), corner2.getBlockX()); x++) {
                                    for (int y = Math.min(corner1.getBlockY(), corner2.getBlockY()); y <= Math.max(corner1.getBlockY(), corner2.getBlockY()); y++) {
                                        for (int z = Math.min(corner1.getBlockZ(), corner2.getBlockZ()); z <= Math.max(corner1.getBlockZ(), corner2.getBlockZ()); z++) {
                                            Material material = materials.get(new Random().nextInt(materials.size()));
                                            new Location(corner1.getWorld(), x, y, z).getBlock().setType(material);
                                        }
                                    }
                                }
                            }
                        }
                    }
                    return true;
                }
            }
        }

        return false;
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getPlayer().equals(setupPlayer)) {
            ItemStack itemInHand = event.getPlayer().getInventory().getItemInMainHand();
            if (itemInHand.getType() != Material.DIAMOND_AXE || !Objects.requireNonNull(itemInHand.getItemMeta()).getDisplayName().equals(ChatColor.GOLD + "" + ChatColor.BOLD + "StyleLabor Mine - Setup")) {
                return;
            }

            long clickDelay = getConfig().getLong("clickDelay", 20); // Default to 20 ticks if not set in config.yml
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastClickTime < clickDelay * 50) { // Convert ticks to milliseconds
                return;
            }
            lastClickTime = currentTime;

            new BukkitRunnable() {
                @Override
                public void run() {
                    if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
                        corner1 = Objects.requireNonNull(event.getClickedBlock()).getLocation();
                        setupPlayer.sendMessage("First corner set at " + corner1.getBlockX() + ", " + corner1.getBlockY() + ", " + corner1.getBlockZ());
                    } else if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
                        corner2 = Objects.requireNonNull(event.getClickedBlock()).getLocation();
                        setupPlayer.sendMessage("Second corner set at " + corner2.getBlockX() + ", " + corner2.getBlockY() + ", " + corner2.getBlockZ());
                    }

                    // Save corners to data.yml
                    File dataFile = new File(getDataFolder(), "data.yml");
                    if (!dataFile.exists()) {
                        try {
                            boolean fileCreated = dataFile.createNewFile();
                            if (!fileCreated) {
                                getLogger().info("File already exists: " + dataFile.getPath());
                            }
                        } catch (IOException e) {
                            getLogger().severe("Could not create file: " + e.getMessage());
                        }
                    }

                    YamlConfiguration dataConfig = YamlConfiguration.loadConfiguration(dataFile);
                    if (corner1 != null) {
                        dataConfig.set("corner1", locationToString(corner1));
                    }
                    if (corner2 != null) {
                        dataConfig.set("corner2", locationToString(corner2));
                    }

                    try {
                        dataConfig.save(dataFile);
                    } catch (IOException e) {
                        getLogger().severe("Could not save to file: " + e.getMessage());
                    }

                    // If both corners are set, send the finish message
                    if (corner1 != null && corner2 != null) {
                        TextComponent message = new TextComponent(ChatColor.GREEN + "" + ChatColor.BOLD + "Click here to finish setup. | AREA WILL BE CLEARED!");
                        message.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/stylelabormine finish"));
                        setupPlayer.spigot().sendMessage(message);
                    }
                }
            }.runTaskLater(this, clickDelay);

            // Set cooldown for the axe
            setupPlayer.setCooldown(Material.DIAMOND_AXE, (int) clickDelay);
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player && event.getCause() == EntityDamageEvent.DamageCause.SUFFOCATION) {
            Player player = (Player) event.getEntity();
            World world = player.getWorld();
            Location location = player.getLocation();
            int x = location.getBlockX();
            int y = location.getBlockY();
            int z = location.getBlockZ();

            // Find the next higher possible Y level
            for (int i = y; i < world.getMaxHeight(); i++) {
                if (!world.getBlockAt(x, i, z).getType().isSolid() && !world.getBlockAt(x, i + 1, z).getType().isSolid()) {
                    // Teleport the player to the new location
                    player.teleport(new Location(world, x, i, z, location.getYaw(), location.getPitch()));
                    break;
                }
            }
        }
    }

    public void updateScoreboard(Player player) {
        System.out.println("Updating scoreboard for player: " + player.getName()); // Debug message
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager != null) {
            Scoreboard scoreboard = manager.getNewScoreboard();
            Objective objective = scoreboard.registerNewObjective("StyleLaborMine", Criteria.DUMMY, scoreboardConfig.getString("title", "Scoreboard"), RenderType.INTEGER);
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);

            List<Map<?, ?>> lines = scoreboardConfig.getMapList("lines");
            for (int i = 0; i < lines.size(); i++) {
                Map<?, ?> lineMap = lines.get(i);
                String text = ChatColor.translateAlternateColorCodes('&', (String) lineMap.get("text"));
                int updateFrequency = (int) lineMap.get("updateFrequency");
                Score score = objective.getScore(text);
                score.setScore(lines.size() - i);

                // Schedule a repeating task to update the line text
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        // Update the line text here
                    }
                }.runTaskTimer(this, 0, updateFrequency);
            }

            player.setScoreboard(scoreboard);
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        getLogger().info("Block break event triggered");

        Player player = event.getPlayer();
        ItemStack itemInHand = player.getInventory().getItemInMainHand();
        if (itemInHand.getType() == Material.DIAMOND_AXE && Objects.requireNonNull(itemInHand.getItemMeta()).getDisplayName().equals(ChatColor.GOLD + "" + ChatColor.BOLD + "StyleLabor Mine - Setup")) {
            event.setCancelled(true);
        }

        Location blockLocation = event.getBlock().getLocation();
        getLogger().info("Block location: " + blockLocation);

        // Check if the broken block is within the specified region
        if (corner1 != null && corner2 != null) {
            getLogger().info("Corners are not null");
            if (blockLocation.getX() >= Math.min(corner1.getX(), corner2.getX()) && blockLocation.getX() <= Math.max(corner1.getX(), corner2.getX())
                    && blockLocation.getY() >= Math.min(corner1.getY(), corner2.getY()) && blockLocation.getY() <= Math.max(corner1.getY(), corner2.getY())
                    && blockLocation.getZ() >= Math.min(corner1.getZ(), corner2.getZ()) && blockLocation.getZ() <= Math.max(corner1.getZ(), corner2.getZ())) {

                getLogger().info("Block is within the specified region");

                // Get the block types and their percentages from config.yml
                ConfigurationSection blocksSection = getConfig().getConfigurationSection("blocks");
                if (blocksSection != null) {
                    getLogger().info("Block section is not null");
                    List<Material> materials = new ArrayList<>();
                    for (String key : blocksSection.getKeys(false)) {
                        Material material = Material.getMaterial(key);
                        int percentage = blocksSection.getInt(key);
                        for (int i = 0; i < percentage; i++) {
                            materials.add(material);
                        }
                    }

                    // Replace the broken block with a new block based on the percentages
                    Material material = materials.get(new Random().nextInt(materials.size()));
                    getLogger().info("New block material: " + material);

                    // Schedule a task to set the block after a delay
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            event.getBlock().setType(material);
                            getLogger().info("Block type set to new material");
                        }
                    }.runTaskLater(this, getConfig().getLong("regenDelay", 5) * 20); // Convert seconds to ticks
                }
            }
        }
    }

    private String locationToString(Location location) {
        return Objects.requireNonNull(location.getWorld()).getName() + "," + location.getX() + "," + location.getY() + "," + location.getZ() + "," + location.getPitch() + "," + location.getYaw();
    }

    private Location stringToLocation(String string) {
        String[] parts = string.split(",");
        World world = getServer().getWorld(parts[0]);
        double x = Double.parseDouble(parts[1]);
        double y = Double.parseDouble(parts[2]);
        double z = Double.parseDouble(parts[3]);
        float pitch = Float.parseFloat(parts[4]);
        float yaw = Float.parseFloat(parts[5]);
        return new Location(world, x, y, z, yaw, pitch);
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("stylelabormine")) {
            if (args.length == 1) {
                List<String> list = new ArrayList<>();
                list.add("setup");
                return list;
            }
        }
        return null;
    }
}