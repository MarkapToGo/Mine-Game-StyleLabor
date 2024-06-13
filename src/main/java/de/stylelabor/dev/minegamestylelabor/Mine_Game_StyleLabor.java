package de.stylelabor.dev.minegamestylelabor;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bstats.bukkit.Metrics;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.sql.*;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class Mine_Game_StyleLabor extends JavaPlugin implements Listener, TabCompleter {

    private static final Logger LOGGER = Logger.getLogger(Mine_Game_StyleLabor.class.getName());
    // Add a new Set to store the players who have bypass protection enabled
    private final Set<UUID> bypassProtectionPlayers = new HashSet<>();
    private final Map<UUID, float[]> settingSpawnPlayers = new HashMap<>();
    private Player setupPlayer = null;
    private Location corner1 = null;
    private Location corner2 = null;
    private long lastClickTime = 0;
    // Add a field for the database connection
    private Connection connection;
    private FileConfiguration messagesConfig;
    private BukkitTask coinUpdateTask;
    // pickaxe
    private Map<String, Map<String, Object>> pickaxes;

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

        // Load pickaxe.yml
        File pickaxeFile = new File(getDataFolder(), "pickaxe.yml");
        if (!pickaxeFile.exists()) {
            saveResource("pickaxe.yml", false);
        }
        FileConfiguration pickaxeConfig = YamlConfiguration.loadConfiguration(pickaxeFile);

        // Initialize pickaxes
        pickaxes = new HashMap<>();

        // Get the pickaxes
        ConfigurationSection pickaxeSection = pickaxeConfig.getConfigurationSection("pickaxes");
        if (pickaxeSection != null) {
            for (String key : pickaxeSection.getKeys(false)) {
                pickaxes.put(key, Objects.requireNonNull(pickaxeSection.getConfigurationSection(key)).getValues(true));
            }
        }

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

                // Save the spawn location to data.yml
                Location spawnLocation = event.getPlayer().getLocation();
                dataConfig.set("spawnLocation", Objects.requireNonNull(spawnLocation.getWorld()).getName() + "," + spawnLocation.getX() + "," + spawnLocation.getY() + "," + spawnLocation.getZ());
                try {
                    dataConfig.save(dataFile);
                } catch (IOException e) {
                    LOGGER.log(Level.SEVERE, "An exception was thrown! - Save the spawn location to data.yml", e);
                }
                // Check if the player has joined before
                if (!hasPlayerJoinedBefore(event.getPlayer())) {
                    // This is the player's first join, delay the giving of the starter pickaxe
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            givePickaxe(event.getPlayer(), "starter_pickaxe");
                        }
                    }.runTaskLater(Mine_Game_StyleLabor.this, 20L * 4); // 4 seconds delay
                }
            }
        }, this);

        // Register the BlockPlaceEvent
        getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onBlockPlace(BlockPlaceEvent event) {
                Player player = event.getPlayer();

                // Check if the player has bypass protection enabled
                if (!bypassProtectionPlayers.contains(player.getUniqueId())) {
                    // If they do not, cancel the event
                    event.setCancelled(true);
                }
            }
        }, this);

        // Register the PlayerItemDamageEvent
        getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onPlayerItemDamage(PlayerItemDamageEvent event) {
                // Check if the item is a pickaxe and if the preventPickaxeDurabilityLoss option is true
                if (isPickaxe(event.getItem()) && getConfig().getBoolean("preventPickaxeDurabilityLoss", true)) {
                    // Cancel the event to prevent the pickaxe from losing durability
                    event.setCancelled(true);
                }
            }
        }, this);



        // Register the PlayerDropItemEvent
        getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onPlayerDropItem(PlayerDropItemEvent event) {
                Player player = event.getPlayer();

                // Check if the player has bypass protection enabled
                if (!bypassProtectionPlayers.contains(player.getUniqueId())) {
                    // If they do not, cancel the event
                    event.setCancelled(true);
                }
            }
        }, this);

        // Register the InventoryClickEvent
        getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onInventoryClick(InventoryClickEvent event) {
                // Check if the event was triggered by a player
                if (event.getWhoClicked() instanceof Player) {
                    Player player = (Player) event.getWhoClicked();

                    // Check if the player has bypass protection enabled
                    if (!bypassProtectionPlayers.contains(player.getUniqueId())) {
                        // If they do not, cancel the event
                        event.setCancelled(true);
                    }
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


        // Create the inventory table
        try {
            PreparedStatement statement = connection.prepareStatement(
                    "CREATE TABLE IF NOT EXISTS inventories (" +
                            "uuid VARCHAR(36) NOT NULL," +
                            "slot INT NOT NULL," +
                            "item_type VARCHAR(255) NOT NULL," +
                            "item_amount INT NOT NULL," +
                            "item_meta BLOB," +
                            "PRIMARY KEY (uuid, slot)" +
                            ")"
            );
            statement.executeUpdate();
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "An exception was thrown!", e);
        }


        if (getConfig().getBoolean("saveInventory", false)) {
            getServer().getPluginManager().registerEvents(new Listener() {
                @EventHandler
                public void onPlayerJoin(PlayerJoinEvent event) {
                    Player player = event.getPlayer();
                    // Clear the player's inventory before loading the new one
                    player.getInventory().clear();
                    // Load the player's inventory from the database
                    try {
                        PreparedStatement statement = connection.prepareStatement("SELECT slot, item_type, item_amount, item_meta FROM inventories WHERE uuid = ?");
                        statement.setString(1, player.getUniqueId().toString());
                        ResultSet resultSet = statement.executeQuery();
                        while (resultSet.next()) {
                            int slot = resultSet.getInt("slot");
                            Material type = Material.getMaterial(resultSet.getString("item_type"));
                            int amount = resultSet.getInt("item_amount");
                            byte[] itemMeta = resultSet.getBytes("item_meta");
                            ByteArrayInputStream bais = new ByteArrayInputStream(itemMeta);
                            BukkitObjectInputStream bois = new BukkitObjectInputStream(bais);
                            ItemStack item = (ItemStack) bois.readObject();
                            player.getInventory().setItem(slot, item);
                        }
                    } catch (SQLException | IOException | ClassNotFoundException e) {
                        LOGGER.log(Level.SEVERE, "An exception was thrown!", e);
                    }
                }

                @EventHandler
                public void onPlayerQuit(PlayerQuitEvent event) {
                    // Save the player's inventory
                    saveInventory(event.getPlayer());
                }

                @EventHandler
                public void onPlayerKick(PlayerKickEvent event) {
                    // Save the player's inventory when they are kicked
                    saveInventory(event.getPlayer());
                }
            }, this);

            getServer().getPluginManager().registerEvents(new Listener() {
                @EventHandler
                public void onPluginDisable(PluginDisableEvent event) {
                    // Step 7: Save all online players' inventories when the server stops
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        saveInventory(player);
                    }
                }
            }, this);
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

            // Check if the 'player_tiers' table exists
            tables = connection.getMetaData().getTables(null, null, "player_tiers", null);
            if (!tables.next()) {
                // The 'player_tiers' table does not exist, create it
                PreparedStatement statement = connection.prepareStatement(
                        "CREATE TABLE player_tiers (" +
                                "uuid VARCHAR(36) NOT NULL," +
                                "tier INT NOT NULL," +
                                "PRIMARY KEY (uuid)" +
                                ")"
                );
                statement.executeUpdate();
            }
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "An exception was thrown!", e);
        }
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic

        // Cancel the coin update task
        if (coinUpdateTask != null) {
            coinUpdateTask.cancel();
        }
    }

    private boolean isPickaxe(ItemStack item) {
        Material type = item.getType();
        return type == Material.WOODEN_PICKAXE || type == Material.STONE_PICKAXE || type == Material.IRON_PICKAXE || type == Material.GOLDEN_PICKAXE || type == Material.DIAMOND_PICKAXE || type == Material.NETHERITE_PICKAXE;
    }

    private boolean hasPlayerJoinedBefore(Player player) {
        try {
            // Prepare a SQL statement to select the player's data from the database
            PreparedStatement statement = connection.prepareStatement("SELECT * FROM players WHERE uuid = ?");
            statement.setString(1, player.getUniqueId().toString());

            // Execute the SQL statement and get the result
            ResultSet resultSet = statement.executeQuery();

            // If the result set is not empty, the player has joined before
            return resultSet.next();
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "An exception was thrown!", e);
        }

        // If an exception is thrown, assume the player has not joined before
        return false;
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


    @SuppressWarnings("SameParameterValue")
    private void givePickaxe(Player player, String pickaxeKey) {
        // Get the pickaxe configuration
        Map<String, Object> pickaxeMap = pickaxes.get(pickaxeKey);
        if (pickaxeMap == null) {
            // The pickaxe does not exist in the configuration
            return;
        }

        // Create the ItemStack
        Material material = Material.valueOf((String) pickaxeMap.get("material"));
        ItemStack itemStack = new ItemStack(material);

        // Set the display name and lore
        ItemMeta itemMeta = itemStack.getItemMeta();
        if (itemMeta != null) {
            itemMeta.setDisplayName(ChatColor.translateAlternateColorCodes('&', (String) pickaxeMap.get("displayName")));
            Object loreObject = pickaxeMap.get("lore");
            if (loreObject instanceof List<?>) {
                List<?> lore = (List<?>) loreObject;
                List<String> coloredLore = new ArrayList<>();
                for (Object line : lore) {
                    if (line instanceof String) {
                        coloredLore.add(ChatColor.translateAlternateColorCodes('&', (String) line));
                    }
                }
                itemMeta.setLore(coloredLore);
            }

            // Set the enchantments
            Object enchantmentsObject = pickaxeMap.get("enchantments");
            if (enchantmentsObject instanceof List<?>) {
                List<?> enchantments = (List<?>) enchantmentsObject;
                for (Object enchantment : enchantments) {
                    if (enchantment instanceof String) {
                        String[] parts = ((String) enchantment).split(":");
                        NamespacedKey key = NamespacedKey.minecraft(parts[0].toLowerCase());
                        Enchantment enchant = Enchantment.getByKey(key);
                        int level = Integer.parseInt(parts[1]);
                        if (enchant != null) {
                            itemMeta.addEnchant(enchant, level, true);
                        }
                    }
                }
            }

            itemStack.setItemMeta(itemMeta);
        }

        // Give the ItemStack to the player
        player.getInventory().addItem(itemStack);
    }


    public String getMessage(String path, Object... args) {
        String message = messagesConfig.getString(path);
        if (message != null) {
            return String.format(message, args);
        }
        return "";
    }


    @Override
    public boolean onCommand(@NotNull CommandSender sender, Command command, @NotNull String label, String[] args) {
        if (command.getName().equalsIgnoreCase("stylelabormine")) {
            if (args.length > 0) {
                switch (args[0].toLowerCase()) {
                    case "setspawn":
                        handleSetSpawnCommand(sender, args);
                        return true;
                    case "tpsurface":
                        handleTpSurfaceCommand(sender, args);
                        return true;
                    case "coins":
                        handleCoinsCommand(sender, args);
                        return true;
                    case "database-test":
                        handleDatabaseTestCommand(sender, args);
                        return true;
                    case "setup":
                        handleSetupCommand(sender, args);
                        return true;
                    case "finish":
                        handleFinishCommand(sender, args);
                        return true;
                    case "buy":
                        buyPickaxe(sender, args);
                        return true;
                    case "bypassprotection":
                        handleBypassProtectionCommand(sender, args);
                        return true;
                    case "setplayertier":
                        handlePlayerTier(sender, args);
                        return true;
                    default:
                        sender.sendMessage("Unknown command.");
                        return false;
                }
            }
        }
        return false;
    }

    private void handlePlayerTier(CommandSender sender, String[] args) {
        if (sender.hasPermission("minegame.admin")) {
            if (args.length != 2) {
                sender.sendMessage("Usage: /setplayertier <player> <tier>");
                return;
            }
            Player target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                sender.sendMessage("Player not found.");
                return;
            }
            int tier;
            try {
                tier = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                sender.sendMessage("Invalid tier.");
                return;
            }
            setPlayerTier(target, tier);
            sender.sendMessage("Set tier of " + target.getName() + " to " + tier + ".");
        } else {
            sender.sendMessage("You do not have permission to use this command.");
        }
    }



    private void buyPickaxe(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("Usage: /stylelabormine buy <pickaxe>");
            return;
        }

        String pickaxeName = args[1];
        Map<String, Object> pickaxeMap = pickaxes.get(pickaxeName);
        if (pickaxeMap == null) {
            sender.sendMessage("Invalid pickaxe. Available pickaxes: " + String.join(", ", pickaxes.keySet()));
            return;
        }

        YamlConfiguration pickaxe = new YamlConfiguration();
        pickaxe.addDefaults(pickaxeMap);

        Player player = (Player) sender;
        int cost = pickaxe.getInt("cost");
        int tier = pickaxe.getInt("tier");
        int playerTier = getPlayerTier(player);
        if (playerTier >= tier) {
            sender.sendMessage("You can only buy a pickaxe with a higher tier.");
            return;
        }

        if (getCoins(player) < cost) {
            sender.sendMessage("You do not have enough coins to buy this pickaxe.");
            return;
        }

        subtractCoins(player, cost);
        setPlayerTier(player, tier);

        // Create the ItemStack
        Material material = Material.valueOf(pickaxe.getString("material"));
        ItemStack itemStack = new ItemStack(material);

        // Set the display name and lore
        ItemMeta itemMeta = itemStack.getItemMeta();
        if (itemMeta != null) {
            String displayName = ChatColor.translateAlternateColorCodes('&', Objects.requireNonNull(pickaxe.getString("displayName")));
            itemMeta.setDisplayName(displayName);

            List<String> lore = pickaxe.getStringList("lore");
            List<String> coloredLore = new ArrayList<>();
            for (String loreLine : lore) {
                coloredLore.add(ChatColor.translateAlternateColorCodes('&', loreLine));
            }
            itemMeta.setLore(coloredLore);
        }

        // Give the ItemStack to the player
        player.getInventory().addItem(itemStack);

        // Execute the additional commands
        List<String> commands = pickaxe.getStringList("commands");
        for (int i = 0; i < commands.size(); i++) {
            String command = commands.get(i);
            Bukkit.getScheduler().scheduleSyncDelayedTask(this, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.replace("%player%", player.getName())), i * 5L); // Delay of 5 ticks between each command
        }

        // Send a title to the player
        String title = ChatColor.translateAlternateColorCodes('&', Objects.requireNonNull(messagesConfig.getString("titleMessage.title")));
        String subtitle = ChatColor.translateAlternateColorCodes('&', String.format(Objects.requireNonNull(messagesConfig.getString("titleMessage.subtitle")), pickaxe.getString("name"), cost));
        int fadeIn = getConfig().getInt("titleMessage.fadeIn");
        int stay = getConfig().getInt("titleMessage.stay");
        int fadeOut = getConfig().getInt("titleMessage.fadeOut");
        player.sendTitle(title, subtitle, fadeIn, stay, fadeOut);

        // Play a sound to the player
        String soundName = getConfig().getString("titleMessage.sound");
        float volume = (float) getConfig().getDouble("titleMessage.volume");
        float pitch = (float) getConfig().getDouble("titleMessage.pitch");
        if (soundName != null) {
            Sound sound = Sound.valueOf(soundName);
            player.playSound(player.getLocation(), sound, volume, pitch);
        }

        // Use the customized message from messages.yml
        String boughtPickaxeMessage = String.format((Objects.requireNonNull(messagesConfig.getString("coins.boughtPickaxe"))), pickaxeName, cost);
        sender.sendMessage(boughtPickaxeMessage);
    }

    private int getPlayerTier(Player player) {
        try {
            PreparedStatement statement = connection.prepareStatement("SELECT tier FROM player_tiers WHERE uuid = ?");
            statement.setString(1, player.getUniqueId().toString());
            ResultSet resultSet = statement.executeQuery();
            if (resultSet.next()) {
                return resultSet.getInt("tier");
            }
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "An exception was thrown!", e);
        }
        return 0; // Return 0 if the player's tier is not found in the database
    }

    private void setPlayerTier(Player player, int tier) {
        try {
            PreparedStatement statement = connection.prepareStatement("INSERT INTO player_tiers (uuid, tier) VALUES (?, ?) ON DUPLICATE KEY UPDATE tier = ?");
            statement.setString(1, player.getUniqueId().toString());
            statement.setInt(2, tier);
            statement.setInt(3, tier);
            statement.executeUpdate();
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "An exception was thrown!", e);
        }
    }

    private void handleSetSpawnCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("This command can only be used by a player.");
            return;
        }
        if (args.length < 3) {
            sender.sendMessage("You need to add a yaw and a pitch. Usage: /stylelabormine setspawn <pitch> <yaw>");
            return;
        }
        Player player = (Player) sender;
        float pitch = Float.parseFloat(args[1]);
        float yaw = Float.parseFloat(args[2]);
        settingSpawnPlayers.put(player.getUniqueId(), new float[]{pitch, yaw});
        player.sendMessage("Click on a block to set the spawn location.");
    }

    private void handleTpSurfaceCommand(CommandSender sender, String[] ignoredArgs1) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("This command can only be used by a player.");
            return;
        }
        Player player = (Player) sender;
        Location location = player.getLocation();
        while (location.getBlock().getType() != Material.AIR) {
            location.add(0, 1, 0);
        }
        player.teleport(location);
        player.sendMessage("Teleported to the surface.");
    }

    private void handleCoinsCommand(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(getMessage("coins.usage"));
            return;
        }

        String operation = args[1];
        Player targetPlayer = getServer().getPlayer(args[2]);

        if (targetPlayer == null) {
            sender.sendMessage(getMessage("coins.player_not_found"));
            return;
        }

        int amount;

        switch (operation.toLowerCase()) {
            case "set":
            case "add":
            case "subtract":
                if (args.length < 4) {
                    sender.sendMessage(getMessage("coins.usage"));
                    return;
                }

                try {
                    amount = Integer.parseInt(args[3]);
                } catch (NumberFormatException e) {
                    sender.sendMessage(getMessage("coins.invalid_amount"));
                    return;
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
            case "lookup":
                int coins = getCoins(targetPlayer);
                sender.sendMessage(getMessage("coins.lookup_coins", targetPlayer.getName(), coins));
                break;
            case "command":
                if (args.length < 5) {
                    sender.sendMessage("Usage: /stylelabormine coins command <player> <amount> <command>");
                    return;
                }

                try {
                    amount = Integer.parseInt(args[3]);
                } catch (NumberFormatException e) {
                    sender.sendMessage(getMessage("coins.invalid_amount"));
                    return;
                }

                if (getCoins(targetPlayer) >= amount) {
                    subtractCoins(targetPlayer, amount);
                    String[] commands = String.join(" ", Arrays.copyOfRange(args, 4, args.length)).split("&&");
                    for (String commandToExecute : commands) {
                        getServer().dispatchCommand(getServer().getConsoleSender(), commandToExecute.replace("%player%", targetPlayer.getName()));
                    }
                } else {
                    sender.sendMessage("Player does not have enough coins.");
                }
                break;
            default:
                sender.sendMessage(getMessage("coins.invalid_operation"));
                break;
        }
    }

    private void handleDatabaseTestCommand(CommandSender sender, String[] ignoredArgs) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("This command can only be used by a player.");
            return;
        }
        Player player = (Player) sender;
        if (player.isOp()) {
            try {
                if (connection != null && !connection.isClosed()) {
                    player.sendMessage("Database connection is active.");
                } else {
                    player.sendMessage("Database connection is not active.");
                }
            } catch (SQLException e) {
                player.sendMessage("An error occurred while checking the database connection.");
            }
        } else {
            player.sendMessage("You do not have permission to perform this command.");
        }
    }

    private void handleSetupCommand(CommandSender sender, String[] ignoredArgs1) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("This command can only be used by a player.");
            return;
        }
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
                player.sendMessage("Setup mode enabled. Use the diamond axe to select the corners of the mine.");
            } else {
                player.sendMessage("Your inventory is full. Please clear a space and try again.");
            }
        } else {
            player.sendMessage("You do not have permission to perform this command.");
        }
    }

    private void handleFinishCommand(CommandSender sender, String[] ignoredArgs) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("This command can only be used by a player.");
            return;
        }
        Player player = (Player) sender;
        if (player.isOp()) {
            if (corner1 != null && corner2 != null) {
                regenerateMineRegion();
                player.sendMessage("Mine setup finished and area cleared.");
                setupPlayer = null;
                corner1 = null;
                corner2 = null;
            } else {
                player.sendMessage("Both corners have not been set.");
            }
        } else {
            player.sendMessage("You do not have permission to perform this command.");
        }
    }

    private void handleBypassProtectionCommand(CommandSender sender, String[] ignoredArgs) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("This command can only be used by a player.");
            return;
        }
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

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {

        if (settingSpawnPlayers.containsKey(event.getPlayer().getUniqueId())) {
            if (event.getAction() == Action.LEFT_CLICK_BLOCK || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
                Location spawnLocation = Objects.requireNonNull(event.getClickedBlock()).getLocation().add(0, 1, 0);
                float[] pitchAndYaw = settingSpawnPlayers.get(event.getPlayer().getUniqueId());
                spawnLocation.setPitch(pitchAndYaw[0]);
                spawnLocation.setYaw(pitchAndYaw[1]);
                // Save spawnLocation to data.yml
                File dataFile = new File(getDataFolder(), "data.yml");
                YamlConfiguration dataConfig = YamlConfiguration.loadConfiguration(dataFile);
                dataConfig.set("spawnLocation", locationToString(spawnLocation));
                try {
                    dataConfig.save(dataFile);
                } catch (IOException e) {
                    LOGGER.log(Level.SEVERE, "An exception was thrown!", e);
                }
                event.getPlayer().sendMessage("Spawn location set at " + spawnLocation.getBlockX() + ", " + spawnLocation.getBlockY() + ", " + spawnLocation.getBlockZ());
                settingSpawnPlayers.remove(event.getPlayer().getUniqueId());
            }
            event.setCancelled(true);
        }

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
        World world = Bukkit.getWorld(parts[0].substring(parts[0].indexOf("=") + 1));
        double x = Double.parseDouble(parts[1].substring(parts[1].indexOf("=") + 1));
        double y = Double.parseDouble(parts[2].substring(parts[2].indexOf("=") + 1));
        double z = Double.parseDouble(parts[3].substring(parts[3].indexOf("=") + 1));

        // If pitch and yaw are provided, use them
        if (parts.length >= 6) {
            float pitch = Float.parseFloat(parts[4].substring(parts[4].indexOf("=") + 1, parts[4].length() - 1));
            float yaw = Float.parseFloat(parts[5].substring(parts[5].indexOf("=") + 1, parts[5].length() - 1));
            return new Location(world, x, y, z, yaw, pitch);
        }

        // Otherwise, return a location without pitch and yaw
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
                    debug("Updated coins for player: " + player.getUniqueId()); // Debug message, can be turned off in config.yml
                    // Play the custom sound only if the amount is greater than 0
                    //noinspection DuplicatedCode
                    if (amount > 0) {
                        String soundName = getConfig().getString("coinReceivedSound", "ENTITY_PLAYER_LEVELUP");
                        float volume = (float) getConfig().getDouble("coinReceivedSoundVolume", 1.0);
                        float pitch = (float) getConfig().getDouble("coinReceivedSoundPitch", 1.0);
                        Sound sound = Sound.valueOf(soundName);
                        player.playSound(player.getLocation(), sound, volume, pitch);
                    }
                }
            } else {
                // If the player does not exist, insert a new row for them
                PreparedStatement insertStatement = connection.prepareStatement("INSERT INTO players (uuid, coins) VALUES (?, ?)");
                insertStatement.setString(1, player.getUniqueId().toString());
                insertStatement.setInt(2, amount);
                int rowsInserted = insertStatement.executeUpdate();
                // Play the custom sound only if the amount is greater than 0
                //noinspection DuplicatedCode
                if (amount > 0) {
                    String soundName = getConfig().getString("coinReceivedSound", "ENTITY_PLAYER_LEVELUP");
                    float volume = (float) getConfig().getDouble("coinReceivedSoundVolume", 1.0);
                    float pitch = (float) getConfig().getDouble("coinReceivedSoundPitch", 1.0);
                    Sound sound = Sound.valueOf(soundName);
                    player.playSound(player.getLocation(), sound, volume, pitch);
                }
                if (rowsInserted > 0) {
                    debug("Inserted new player into database: " + player.getUniqueId()); // Debug message, can be turned off in config.yml
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


    // Method to save a player's inventory
    private void saveInventory(Player player) {
        try {
            // Delete the player's current inventory in the database
            PreparedStatement deleteStatement = connection.prepareStatement("DELETE FROM inventories WHERE uuid = ?");
            deleteStatement.setString(1, player.getUniqueId().toString());
            deleteStatement.executeUpdate();

            // Insert the player's current inventory into the database
            PreparedStatement insertStatement = connection.prepareStatement("INSERT INTO inventories (uuid, slot, item_type, item_amount, item_meta) VALUES (?, ?, ?, ?, ?)");
            insertStatement.setString(1, player.getUniqueId().toString());
            for (int i = 0; i < player.getInventory().getSize(); i++) {
                ItemStack item = player.getInventory().getItem(i);
                if (item != null) {
                    insertStatement.setInt(2, i);
                    insertStatement.setString(3, item.getType().name());
                    insertStatement.setInt(4, item.getAmount());
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    BukkitObjectOutputStream boos = new BukkitObjectOutputStream(baos);
                    boos.writeObject(item);
                    byte[] itemMeta = baos.toByteArray();
                    insertStatement.setBytes(5, itemMeta);
                    insertStatement.executeUpdate();
                }
            }
        } catch (SQLException | IOException e) {
            LOGGER.log(Level.SEVERE, "An exception was thrown!", e);
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("stylelabormine")) {
            if (args.length == 1) {
                return Arrays.asList("setup", "bypassprotection", "database-test", "coins", "tpsurface", "setspawn", "buy");
            } else if (args.length == 2 && args[0].equalsIgnoreCase("coins")) {
                return Arrays.asList("set", "add", "subtract", "lookup");
            } else if (args.length == 3 && args[0].equalsIgnoreCase("coins")) {
                List<String> playerNames = new ArrayList<>();
                for (Player player : Bukkit.getOnlinePlayers()) {
                    playerNames.add(player.getName());
                }
                return playerNames;
            }
        } else if (command.getName().equalsIgnoreCase("setplayertier")) {
            if (sender.hasPermission("minegame.admin")) {
                if (args.length == 1) {
                    List<String> playerNames = new ArrayList<>();
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        playerNames.add(player.getName());
                    }
                    return playerNames;
                }
            }
        }
        return null;
    }
}