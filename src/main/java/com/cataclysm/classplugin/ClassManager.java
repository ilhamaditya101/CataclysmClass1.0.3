package com.cataclysm.classplugin.manager;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.configuration.ConfigurationSection;

import java.util.*;

public final class ClassManager {
    private static final String DATA_PATH = "players.";

    private final com.cataclysm.classplugin.CataclysmClassPlugin plugin;
    private final LuckPerms luckPerms;

    public ClassManager(com.cataclysm.classplugin.CataclysmClassPlugin plugin, LuckPerms luckPerms) {
        this.plugin = plugin;
        this.luckPerms = luckPerms;
    }

    public boolean hasClass(Player player) {
        return getClass(player) != null;
    }

    public String getClass(Player player) {
        return getClass(player.getUniqueId());
    }

    public String getClass(UUID uuid) {
        String stored = plugin.getData().getString(DATA_PATH + uuid + ".class");
        if (stored != null && plugin.getConfig().isConfigurationSection("classes." + stored)) {
            return stored;
        }
        return null;
    }

    public boolean setClass(Player player, String classId) {
        if (hasClass(player)) return false;
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("classes." + classId);
        if (section == null) return false;

        String group = section.getString("group", classId);
        if (group == null || group.isBlank()) return false;

        User user = luckPerms.getUserManager().getUser(player.getUniqueId());
        if (user == null) return false;

        user.data().add(Node.builder("group." + group).build());
        luckPerms.getUserManager().saveUser(user);

        plugin.getData().set(DATA_PATH + player.getUniqueId() + ".class", classId);
        plugin.getData().set("players." + player.getUniqueId() + ".pending-remove", null);
        plugin.saveData();

        executeCommands(player, plugin.getConfig().getStringList("classes." + classId + ".stat-commands"));
        giveRewards(player, classId);
        executeCommands(player, plugin.getConfig().getStringList("classes." + classId + ".commands"));
        return true;
    }

    public boolean resetClass(OfflinePlayer offlinePlayer) {
        UUID uuid = offlinePlayer.getUniqueId();
        String classId = getClass(uuid);
        if (classId == null) return false;

        ConfigurationSection section = plugin.getConfig().getConfigurationSection("classes." + classId);
        String group = section == null ? classId : section.getString("group", classId);

        User user = luckPerms.getUserManager().getUser(uuid);
        if (user == null) {
            user = luckPerms.getUserManager().loadUser(uuid).join();
        }
        if (user != null && group != null) {
            user.data().remove(Node.builder("group." + group).build());
            luckPerms.getUserManager().saveUser(user);
        }

        if (offlinePlayer.isOnline()) {
                Player player = offlinePlayer.getPlayer();
            if (player != null) {
                executeCommands(player, plugin.getConfig().getStringList("classes." + classId + ".reset-stat-commands"));
                executeCommands(player, plugin.getConfig().getStringList("classes." + classId + ".reset-commands"));
            }
            plugin.getData().set(DATA_PATH + uuid, null);
        } else {
            // Remove the class immediately and finish configured reset commands on next join.
            plugin.getData().set(DATA_PATH + uuid + ".class", null);
            plugin.getData().set(DATA_PATH + uuid + ".pending-remove", classId);
        }
        plugin.saveData();
        return true;
    }

    public void handlePendingReset(Player player) {
        String classId = plugin.getData().getString(DATA_PATH + player.getUniqueId() + ".pending-remove");
        if (classId == null) return;
        executeCommands(player, plugin.getConfig().getStringList("classes." + classId + ".reset-stat-commands"));
        executeCommands(player, plugin.getConfig().getStringList("classes." + classId + ".reset-commands"));
        plugin.getData().set(DATA_PATH + player.getUniqueId() + ".pending-remove", null);
        plugin.saveData();
    }

    private void executeCommands(Player player, List<String> commands) {
        for (String raw : commands) {
            if (raw == null || raw.isBlank()) continue;
            String command = raw.startsWith("/") ? raw.substring(1) : raw;
            command = command.replace("{player}", player.getName())
                    .replace("{uuid}", player.getUniqueId().toString());
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        }
    }

    private void giveRewards(Player player, String classId) {
        List<Map<?, ?>> rewards = plugin.getConfig().getMapList("classes." + classId + ".items");
        for (Map<?, ?> map : rewards) {
            String type = String.valueOf(value(map, "type", "VANILLA")).toUpperCase(Locale.ROOT);
            int amount = parseInt(map.get("amount"), 1);

            if (type.equals("VANILLA")) {
                String materialName = String.valueOf(map.get("material"));
                org.bukkit.Material material = org.bukkit.Material.matchMaterial(materialName);
                if (material == null) {
                    plugin.getLogger().warning("Material reward tidak dikenal: " + materialName);
                    continue;
                }
                org.bukkit.inventory.ItemStack item = new org.bukkit.inventory.ItemStack(material, Math.max(1, amount));
                player.getInventory().addItem(item);
            } else if (type.equals("MMOITEMS")) {
                String command = String.valueOf(value(map, "command", "mi give {player} {mmo-type} {id} {amount}"));
                command = command.replace("{player}", player.getName())
                        .replace("{id}", String.valueOf(value(map, "id", "")))
                        .replace("{mmo-type}", String.valueOf(value(map, "mmo-type", "CONSUMABLE")))
                        .replace("{amount}", String.valueOf(amount));
                if (command.startsWith("/")) command = command.substring(1);
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            } else if (type.equals("COMMAND")) {
                String command = String.valueOf(value(map, "command", ""))
                        .replace("{player}", player.getName())
                        .replace("{uuid}", player.getUniqueId().toString());
                if (command.startsWith("/")) command = command.substring(1);
                if (!command.isBlank()) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            }
        }
    }

    private Object value(Map<?, ?> map, String key, Object def) {
        Object v = map.get(key);
        return v == null ? def : v;
    }

    private int parseInt(Object value, int def) {
        if (value == null) return def;
        try { return Integer.parseInt(String.valueOf(value)); }
        catch (NumberFormatException e) { return def; }
    }
}
