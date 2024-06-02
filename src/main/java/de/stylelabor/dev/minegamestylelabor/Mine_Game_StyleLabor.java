package de.stylelabor.dev.minegamestylelabor;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bstats.bukkit.Metrics;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.potion.PotionEffect;
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
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.sql.*;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class Mine_Game_StyleLabor extends JavaPlugin implements Listener, TabCompleter {

    private Player setupPlayer = null;
    private Location corner1 = null;
    private Location corner2 = null;
    private long lastClickTime = 0;
    // Add a new Set to store the players who have bypass protection enabled
    private final Set<UUID> bypassProtectionPlayers = new HashSet<>();
    // Add a field for the database connection
    private Connection connection;
    private static final Logger LOGGER = Logger.getLogger(Mine_Game_StyleLabor.class.getName());
    private FileConfiguration messagesConfig;
    private BukkitTask coinUpdateTask;

    @SuppressWarnings("unused")
    @Override
    public void onEnable() {
        //bStats Metrics
        int pluginId = 22115;
        Metrics metrics = new Metrics(this, pluginId);
        // Ensures that a config.yml file exists. If it doesn't, the plugin copies the default one included in the JAR file.
        saveDefaultConfig();
        saveResource("messages.yml", false);
        // Load messages.yml
        File messagesFile = new File(getDataFolder(), "messages.yml");
        messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);
        // Load config.yml
        File configFile = new File(getDataFolder(), "config.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile);

        // Load mysql.yml
        File mysqlFile = new File(getDataFolder(), "mysql.yml");
        if (!mysqlFile.exists()) {
            saveResource("mysql.yml", false);
        }
        FileConfiguration mysqlConfig = YamlConfiguration.loadConfiguration(mysqlFile);

        // Register the PlayerJoinEvent
        getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onPlayerJoin(PlayerJoinEvent event) {
                System.out.println("Player joined: " + event.getPlayer().getName()); // Debug message

                // Check if the giveNightVision option is true
                if (config.getBoolean("giveNightVision", false)) {
                    Player player = event.getPlayer();
                    player.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, Integer.MAX_VALUE, 0, true, false, false));
                }

                // Load data.yml
                File dataFile = new File(getDataFolder(), "data.yml");
                YamlConfiguration dataConfig = YamlConfiguration.loadConfiguration(dataFile);

                // Get the spawn location from data.yml
                String spawnLocationString = dataConfig.getString("spawnLocation");
                if (spawnLocationString != null) {
                    Location spawnLocation = stringToLocation(spawnLocationString);
                    event.getPlayer().teleport(spawnLocation);
                }

            }
        }, this);

        // Disable hunger if the setting is true
        if (getConfig().getBoolean("disableHunger", false)) {
            getServer().getPluginManager().registerEvents(new Listener() {
                @EventHandler
                public void onFoodLevelChange(FoodLevelChangeEvent event) {
                    event.setCancelled(true);
                }
            }, this);
        }

        // Initialize the database connection
        try {
            String hostname = mysqlConfig.getString("hostname");
            int port = mysqlConfig.getInt("port");
            String database = mysqlConfig.getString("database");
            String username = mysqlConfig.getString("username");
            String password = mysqlConfig.getString("password");

            String url = "jdbc:mysql://" + hostname + ":" + port + "/" + database;

            connection = DriverManager.getConnection(url, username, password);
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "An exception was thrown!", e);
        }

        try {
            PreparedStatement statement = connection.prepareStatement("ALTER TABLE players MODIFY uuid VARCHAR(36)");
            statement.executeUpdate();
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "An exception was thrown!", e);
        }

        // Start the coin update task
        int coinUpdateFrequency = getConfig().getInt("coinUpdateFrequency");
        coinUpdateTask = new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    player.setLevel(getCoins(player));
                    player.setExp(0.9999f);
                }
            }
        }.runTaskTimer(this, 0, coinUpdateFrequency * 20L); // Convert seconds to ticks


        // Set startup time if the setting is present
        if (getConfig().contains("startupTime")) {
            long startupTime = getConfig().getLong("startupTime");
            for (World world : getServer().getWorlds()) {
                world.setTime(startupTime);
            }
        }

        // Set startup weather if the setting is present
        if (getConfig().contains("startupWeather")) {
            String startupWeather = getConfig().getString("startupWeather");
            for (World world : getServer().getWorlds()) {
                switch (Objects.requireNonNull(startupWeather).toLowerCase()) {
                    case "clear":
                        world.setStorm(false);
                        world.setThundering(false);
                        break;
                    case "rain":
                        world.setStorm(true);
                        world.setThundering(false);
                        break;
                    case "thunder":
                        world.setStorm(true);
                        world.setThundering(true);
                        break;
                }
            }
        }

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

        // Set up the database
        setupDatabase();

        // Plugin startup logic
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("stylelabormine")).setExecutor(this);
        Objects.requireNonNull(getCommand("stylelabormine")).setTabCompleter(this);

        // Disable weather if the setting is true
        if (getConfig().getBoolean("disableWeather", false)) {
            for (World world : getServer().getWorlds()) {
                world.setWeatherDuration(0);
                world.setThunderDuration(0);
                world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
            }
        }

        // Disable time cycle if the setting is true
        if (getConfig().getBoolean("disableTimeCycle", false)) {
            for (World world : getServer().getWorlds()) {
                world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
            }
        }

        // Disable mob spawn if the setting is true
        if (getConfig().getBoolean("disableMobSpawn", false)) {
            for (World world : getServer().getWorlds()) {
                world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
            }
        }

        // Regenerate the mine region
        regenerateMineRegion();

    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic

        // Cancel the coin update task
        if (coinUpdateTask != null) {
            coinUpdateTask.cancel();
        }
    }

    @SuppressWarnings("DuplicatedCode")
    private void regenerateMineRegion() {
        // Check if the corners are set
        if (corner1 != null && corner2 != null) {
            // Get the block types and their percentages from config.yml
            ConfigurationSection blocksSection = getConfig().getConfigurationSection("blocks");
            if (blocksSection != null) {
                List<Material> materials = new ArrayList<>();
                for (String key : blocksSection.getKeys(false)) {
                    Material material = Material.getMaterial(key);
                    double percentage = blocksSection.getDouble(key);
                    int amount = (int) (percentage * 100); // Convert the percentage to an amount out of 100
                    for (int i = 0; i < amount; i++) {
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

    public String getMessage(String path, Object... args) {
        String message = messagesConfig.getString(path);
        if (message != null) {
            return String.format(message, args);
        }
        return "";
    }

    @SuppressWarnings("DuplicatedCode")
    @Override
    public boolean onCommand(@NotNull CommandSender sender, Command command, @NotNull String label, String[] args) {
        if (command.getName().equalsIgnoreCase("stylelabormine")) {
            if (args.length > 0) {

                //setspawn command
                if (args[0].equalsIgnoreCase("setspawn")) {
                    if (sender instanceof Player) {
                        Player player = (Player) sender;
                        if (player.isOp()) {
                            // Set the spawn location to the player's current location
                            File dataFile = new File(getDataFolder(), "data.yml");
                            YamlConfiguration dataConfig = YamlConfiguration.loadConfiguration(dataFile);
                            dataConfig.set("spawnLocation", player.getLocation().toString());
                            try {
                                dataConfig.save(dataFile);
                            } catch (IOException e) {
                                player.sendMessage("Failed to save spawn location.");
                                LOGGER.log(Level.SEVERE, "Failed to save spawn location.", e);
                            }
                            player.sendMessage("Spawn location set.");
                        } else {
                            player.sendMessage("You do not have permission to perform this command.");
                        }
                    } else {
                        sender.sendMessage("This command can only be used by a player.");
                    }
                    return true;
                }

                //tpsurface command
                if (args[0].equalsIgnoreCase("tpsurface")) {
                    if (sender instanceof Player) {
                        Player player = (Player) sender;
                        Location location = player.getLocation();
                        while (location.getBlock().getType() != Material.AIR) {
                            location.add(0, 1, 0);
                        }
                        player.teleport(location);
                        player.sendMessage("Teleported to the surface.");
                    } else {
                        sender.sendMessage("This command can only be used by a player.");
                    }
                    return true;
                }
                if (args[0].equalsIgnoreCase("coins")) {
                    if (args.length < 3) {
                        sender.sendMessage(getMessage("coins.usage"));
                        return true;
                    }

                    String operation = args[1];
                    Player targetPlayer = getServer().getPlayer(args[2]);

                    if (targetPlayer == null) {
                        sender.sendMessage(getMessage("coins.player_not_found"));
                        return true;
                    }

                    int amount;

                    //switch statement for coins command
                    switch (operation.toLowerCase()) {
                        case "set":
                        case "add":
                        case "subtract":
                            if (args.length < 4) {
                                sender.sendMessage(getMessage("coins.usage"));
                                return true;
                            }

                            try {
                                amount = Integer.parseInt(args[3]);
                            } catch (NumberFormatException e) {
                                sender.sendMessage(getMessage("coins.invalid_amount"));
                                return true;
                            }

                            switch (operation.toLowerCase()) {
                                case "set":
                                    setCoins(targetPlayer, amount);
                                    sender.sendMessage(getMessage("coins.set_coins", targetPlayer.getName(), amount));
                                    break;
                                case "add":
                                    addCoins(targetPlayer, amount);
                                    sender.sendMessage(getMessage("coins.add_coins", amount, targetPlayer.getName()));
                                    break;
                                case "subtract":
                                    subtractCoins(targetPlayer, amount);
                                    sender.sendMessage(getMessage("coins.subtract_coins", amount, targetPlayer.getName()));
                                    break;
                            }
                            break;
                        // Lookup command for coins from player
                        case "lookup":
                            int coins = getCoins(targetPlayer);
                            sender.sendMessage(getMessage("coins.lookup_coins", targetPlayer.getName(), coins));
                            break;
                        // perform command on player if they have enough coins and then subtract the coins
                        case "command":
                            if (args.length < 5) {
                                sender.sendMessage("Usage: /stylelabormine coins command <player> <amount> <command>");
                                return true;
                            }

                            try {
                                amount = Integer.parseInt(args[3]);
                            } catch (NumberFormatException e) {
                                sender.sendMessage(getMessage("coins.invalid_amount"));
                                return true;
                            }

                            if (getCoins(targetPlayer) >= amount) {
                                subtractCoins(targetPlayer, amount);
                                String[] commands = String.join(" ", Arrays.copyOfRange(args, 4, args.length)).split("&&");
                                for (String commandToExecute : commands) {
                                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), commandToExecute.trim());
                                }
                            } else {
                                sender.sendMessage("Player does not have enough coins.");
                            }
                            break;
                    }

                    return true;
                }
            }

            // Test the database connection
            if (args.length > 0) {
                if (args[0].equalsIgnoreCase("database-test")) {
                    if (sender instanceof Player) {
                        Player player = (Player) sender;
                        if (player.isOp()) {
                            try {
                                if (connection != null && !connection.isClosed()) {
                                    player.sendMessage(ChatColor.GREEN + "Database connection is successful.");
                                } else {
                                    player.sendMessage(ChatColor.RED + "Database connection failed.");
                                }
                            } catch (SQLException e) {
                                player.sendMessage(ChatColor.RED + "An error occurred while checking the database connection.");
                            }
                        } else {
                            player.sendMessage(ChatColor.RED + "You do not have permission to perform this command.");
                        }
                    }
                    return true;
                }
            }

            if (args.length > 0) {
                if (args[0].equalsIgnoreCase("setup")) {
                    if (sender instanceof Player) {
                        Player player = (Player) sender;
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
                    }
                    return true;
                }

                if (args[0].equalsIgnoreCase("finish")) {
                    if (setupPlayer == null) {
                        sender.sendMessage("You need to start the setup process first.");
                        return true;
                    }

                    if (sender instanceof Player) {
                        Player player = (Player) sender;
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
                                    //noinspection DuplicatedCode
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
                    }
                    return true;
                }

                if (args[0].equalsIgnoreCase("bypassprotection")) {
                    if (sender instanceof Player) {
                        Player player = (Player) sender;
                        if (player.isOp()) {
                            if (bypassProtectionPlayers.contains(player.getUniqueId())) {
                                bypassProtectionPlayers.remove(player.getUniqueId());
                                player.sendMessage("Bypass protection disabled.");
                            } else {
                                bypassProtectionPlayers.add(player.getUniqueId());
                                player.sendMessage("Bypass protection enabled.");
                            }
                        } else {
                            player.sendMessage("You do not have permission to perform this command.");
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
        if (event.getEntity() instanceof Player) {
            if (getConfig().getBoolean("disablePlayerDamage", false)) {
                // If player damage is disabled, cancel the event
                event.setCancelled(true);

                // Check if the damage cause is suffocation
                if (event.getCause() == EntityDamageEvent.DamageCause.SUFFOCATION) {
                    // If the player is suffocating, teleport them to the surface
                    Player player = (Player) event.getEntity();
                    Location location = player.getLocation();
                    while (location.getBlock().getType() != Material.AIR) {
                        location.add(0, 1, 0);
                    }
                    player.teleport(location);
                }
            }
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        // Get the block that was broken
        Block block = event.getBlock();

        // Check if the block is an ore
        if (block.getType() == Material.DIAMOND_ORE || block.getType() == Material.GOLD_ORE || block.getType() == Material.IRON_ORE) {
            // Prevent the block from dropping items and experience
            event.setDropItems(false);
            event.setExpToDrop(0);
        }
        Player player = event.getPlayer();
        ItemStack itemInHand = player.getInventory().getItemInMainHand();
        if (itemInHand.getType() == Material.DIAMOND_AXE && Objects.requireNonNull(itemInHand.getItemMeta()).getDisplayName().equals(ChatColor.GOLD + "" + ChatColor.BOLD + "StyleLabor Mine - Setup")) {
            event.setCancelled(true);
        }

        // Prevent the block from dropping items
        event.setDropItems(false);

        // Get the block type
        Material blockType = event.getBlock().getType();

        // Get the number of coins for the block type from config.yml
        int coins = getConfig().getInt("blockCoins." + blockType.name(), 0);

        // Give coins to the player
        giveCoins(player, coins);

        Location blockLocation = event.getBlock().getLocation();

        // Check if the broken block is within the specified region
        if (corner1 != null && corner2 != null) {
            if (blockLocation.getX() >= Math.min(corner1.getX(), corner2.getX()) && blockLocation.getX() <= Math.max(corner1.getX(), corner2.getX())
                    && blockLocation.getY() >= Math.min(corner1.getY(), corner2.getY()) && blockLocation.getY() <= Math.max(corner1.getY(), corner2.getY())
                    && blockLocation.getZ() >= Math.min(corner1.getZ(), corner2.getZ()) && blockLocation.getZ() <= Math.max(corner1.getZ(), corner2.getZ())) {

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

                    // Replace the broken block with a new block based on the percentages
                    Material material = materials.get(new Random().nextInt(materials.size()));

                    // Schedule a task to set the block after a delay
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            event.getBlock().setType(material);
                        }
                    }.runTaskLater(this, getConfig().getLong("regenDelay", 5) * 20); // Convert seconds to ticks
                }
            } else {
                // If the player has bypass protection enabled, do not cancel the event
                if (bypassProtectionPlayers.contains(player.getUniqueId())) {
                    return;
                }
                // If the block is outside the region, cancel the event
                event.setCancelled(true);
            }
        } else {
            // If the corners are not set, cancel the event
            event.setCancelled(false);
        }
    }

    private String locationToString(Location location) {
        return Objects.requireNonNull(location.getWorld()).getName() + "," + location.getX() + "," + location.getY() + "," + location.getZ() + "," + location.getPitch() + "," + location.getYaw();
    }

    private int getCoins(Player player) {
        try {
            PreparedStatement statement = connection.prepareStatement("SELECT coins FROM players WHERE uuid = ?");
            statement.setString(1, player.getUniqueId().toString());
            ResultSet resultSet = statement.executeQuery();
            if (resultSet.next()) {
                return resultSet.getInt("coins");
            }
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "An exception was thrown!", e);
        }
        return 0;
    }

    public void debug(String message) {
        if (getConfig().getBoolean("debug", false)) {
            getLogger().info("[DEBUG] " + message);
        }
    }

    public Location stringToLocation(String s) {
        String[] parts = s.split(",");
        if (parts.length < 4) {
            throw new IllegalArgumentException("Invalid location string: " + s);
        }
        double x = Double.parseDouble(parts[0].split("=")[1]);
        double y = Double.parseDouble(parts[1].split("=")[1]);
        double z = Double.parseDouble(parts[2].split("=")[1]);
        World world = Bukkit.getWorld(parts[3].split("=")[1]);
        return new Location(world, x, y, z);
    }

    private void giveCoins(Player player, int amount) {
        try {
            // Check if the player exists in the database
            PreparedStatement checkStatement = connection.prepareStatement("SELECT * FROM players WHERE uuid = ?");
            checkStatement.setString(1, player.getUniqueId().toString());
            ResultSet resultSet = checkStatement.executeQuery();

            if (resultSet.next()) {
                // If the player exists, update their coins
                PreparedStatement updateStatement = connection.prepareStatement("UPDATE players SET coins = coins + ? WHERE uuid = ?");
                updateStatement.setInt(1, amount);
                updateStatement.setString(2, player.getUniqueId().toString());
                int rowsUpdated = updateStatement.executeUpdate();
                if (rowsUpdated > 0) {
                    debug("Updated coins for player: " + player.getUniqueId());
                } else {
                    LOGGER.log(Level.WARNING, "Failed to update coins for player: " + player.getUniqueId());
                }
            } else {
                // If the player does not exist, insert a new row for them
                PreparedStatement insertStatement = connection.prepareStatement("INSERT INTO players (uuid, coins) VALUES (?, ?)");
                insertStatement.setString(1, player.getUniqueId().toString());
                insertStatement.setInt(2, amount);
                int rowsInserted = insertStatement.executeUpdate();
                if (rowsInserted > 0) {
                    LOGGER.log(Level.INFO, "Inserted new player with coins: " + player.getUniqueId());
                } else {
                    LOGGER.log(Level.WARNING, "Failed to insert new player with coins: " + player.getUniqueId());
                }
            }

            // Set the player's experience level to the new amount of coins
            player.setLevel(getCoins(player));
            // Set the player's experience points to a value just below the next level
            player.setExp(0.9999f);
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "An exception was thrown!", e);
        }
    }

    private void setupDatabase() {
        try {
            // Check if the 'players' table exists
            ResultSet tables = connection.getMetaData().getTables(null, null, "players", null);
            if (!tables.next()) {
                // The 'players' table does not exist, create it
                PreparedStatement statement = connection.prepareStatement(
                        "CREATE TABLE players (" +
                                "uuid VARCHAR(36) NOT NULL," +
                                "coins INT NOT NULL," +
                                "PRIMARY KEY (uuid)" +
                                ")"
                );
                statement.executeUpdate();
            }
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "An exception was thrown!", e);
        }
    }

    private void setCoins(Player player, int amount) {
        try {
            PreparedStatement statement = connection.prepareStatement("UPDATE players SET coins = ? WHERE uuid = ?");
            statement.setInt(1, amount);
            statement.setString(2, player.getUniqueId().toString());
            statement.executeUpdate();
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "An exception was thrown!", e);
        }
    }

    private void addCoins(Player player, int amount) {
        giveCoins(player, amount);
    }

    private void subtractCoins(Player player, int amount) {
        try {
            PreparedStatement statement = connection.prepareStatement("UPDATE players SET coins = coins - ? WHERE uuid = ?");
            statement.setInt(1, amount);
            statement.setString(2, player.getUniqueId().toString());
            statement.executeUpdate();
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "An exception was thrown!", e);
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("stylelabormine")) {
            if (args.length == 1) {
                return Arrays.asList("setup", "bypassprotection", "database-test", "coins", "tpsurface", "setspawn");
            } else if (args.length == 2 && args[0].equalsIgnoreCase("coins")) {
                return Arrays.asList("set", "add", "subtract", "lookup");
            } else if (args.length == 3 && args[0].equalsIgnoreCase("coins")) {
                List<String> playerNames = new ArrayList<>();
                for (Player player : Bukkit.getOnlinePlayers()) {
                    playerNames.add(player.getName());
                }
                return playerNames;
            }
        }
        return null;
    }
}