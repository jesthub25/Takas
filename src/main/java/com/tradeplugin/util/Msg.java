package com.tradeplugin.util;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

/**
 * config.yml -> messages.* okuyan mesaj yardımcısı.
 * Anahtar config'de yoksa koddaki varsayılan metin kullanılır.
 * Yer tutucular: {player}, {distance}, {seconds} ... (key, value) çiftleri olarak verilir.
 */
public final class Msg {

    private static Plugin plugin;

    private Msg() {
    }

    public static void init(Plugin p) {
        plugin = p;
    }

    public static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }

    public static String prefix() {
        return color(plugin.getConfig().getString("messages.prefix", "&8[&6Takas&8] "));
    }

    /** Prefix'siz, renkli mesaj. */
    public static String raw(String key, String def, String... kv) {
        String s = plugin.getConfig().getString("messages." + key, def);
        if (s == null) s = def;
        for (int i = 0; i + 1 < kv.length; i += 2) {
            s = s.replace("{" + kv[i] + "}", kv[i + 1] == null ? "" : kv[i + 1]);
        }
        return color(s);
    }

    /** Prefix'li, renkli mesaj. */
    public static String get(String key, String def, String... kv) {
        return prefix() + raw(key, def, kv);
    }

    public static void send(CommandSender to, String key, String def, String... kv) {
        if (to != null) to.sendMessage(get(key, def, kv));
    }
}
