package com.tradeplugin.commands;

import com.tradeplugin.TradePlugin;
import com.tradeplugin.gui.TradeLogGUI;
import com.tradeplugin.util.Msg;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * /tradelog [oyuncu]
 * DB sorgusu async, menü oluşturma/açma main thread.
 */
public class TradeLogCommand implements CommandExecutor, TabCompleter {

    private final TradePlugin plugin;

    public TradeLogCommand(TradePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            Msg.send(sender, "only-players", "&cBu komutu sadece oyuncular kullanabilir.");
            return true;
        }
        final Player player = (Player) sender;

        if (!player.hasPermission("tradeplugin.tradelog")) {
            Msg.send(player, "no-permission", "&cBu komutu kullanma izniniz yok.");
            return true;
        }

        final String filter = args.length >= 1 ? args[0] : null;
        Msg.send(player, "log-loading", "&eTakas geçmişi yükleniyor...");

        final TradeLogGUI gui = new TradeLogGUI(plugin, player, filter);
        gui.tryLock();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                gui.fetchData();
            } catch (Throwable t) {
                plugin.getLogger().warning("Takas geçmişi yüklenemedi: " + t.getMessage());
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                try {
                    if (!player.isOnline()) return;
                    gui.rebuildInventory();
                    gui.open();
                } finally {
                    gui.unlock();
                }
            });
        });
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> list = new ArrayList<>();
        if (args.length == 1) {
            String input = args[0].toLowerCase();
            for (Player p : plugin.getServer().getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(input)) list.add(p.getName());
            }
        }
        return list;
    }
}
