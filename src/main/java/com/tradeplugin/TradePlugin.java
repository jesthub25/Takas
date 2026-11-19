package com.tradeplugin;

import com.tradeplugin.commands.TradeCommand;
import com.tradeplugin.commands.TradeLogCommand;
import com.tradeplugin.commands.TradeReplyCommand;
import com.tradeplugin.gui.TradeGUI;
import com.tradeplugin.gui.TradeLogGUI;
import com.tradeplugin.listeners.TradeGUIListener;
import com.tradeplugin.listeners.TradeRequestListener;
import com.tradeplugin.managers.DatabaseManager;
import com.tradeplugin.managers.TradeManager;
import com.tradeplugin.util.Msg;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.UUID;

/**
 * TradePlugin — Spigot / Paper / Purpur 1.16.5 – 1.21.x
 */
public class TradePlugin extends JavaPlugin {

    private static TradePlugin instance;
    private DatabaseManager databaseManager;
    private TradeManager tradeManager;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        Msg.init(this);

        TradeGUI.initDecorationKey(new NamespacedKey(this, "trade_deco"));
        TradeLogGUI.initDecoKey(new NamespacedKey(this, "tradelog_deco"));

        this.databaseManager = new DatabaseManager(this);
        if (!databaseManager.initialize()) {
            getLogger().severe("Veritabanı başlatılamadı, eklenti devre dışı bırakılıyor.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.tradeManager = new TradeManager(this);

        TradeCommand tradeCmd = new TradeCommand(this);
        register("trade", tradeCmd, tradeCmd);
        TradeReplyCommand replyCmd = new TradeReplyCommand(this);
        register("tradereply", replyCmd, replyCmd);
        TradeLogCommand logCmd = new TradeLogCommand(this);
        register("tradelog", logCmd, logCmd);

        getServer().getPluginManager().registerEvents(new TradeRequestListener(this), this);
        getServer().getPluginManager().registerEvents(new TradeGUIListener(this), this);

        getLogger().info("TradePlugin v" + getDescription().getVersion() + " etkin. (1.16.5 - 1.21.x)");
    }

    private void register(String name, CommandExecutor exec, TabCompleter tab) {
        PluginCommand cmd = getCommand(name);
        if (cmd == null) {
            getLogger().severe("plugin.yml içinde '" + name + "' komutu yok!");
            return;
        }
        cmd.setExecutor(exec);
        cmd.setTabCompleter(tab);
    }

    @Override
    public void onDisable() {
        if (tradeManager != null) tradeManager.cancelAllTrades();

        // Açık takas geçmişi menülerini kapat (dinleyiciler kalkınca item alınamasın)
        for (UUID uuid : new ArrayList<>(TradeLogGUI.getOpenViewers())) {
            Player p = getServer().getPlayer(uuid);
            if (p != null) p.closeInventory();
        }
        TradeLogGUI.getOpenViewers().clear();

        if (databaseManager != null) databaseManager.close();
        getLogger().info("TradePlugin devre dışı.");
    }

    public static TradePlugin getInstance() { return instance; }
    public DatabaseManager getDatabaseManager() { return databaseManager; }
    public TradeManager getTradeManager() { return tradeManager; }
}
