package com.tradeplugin.commands;

import com.tradeplugin.TradePlugin;
import com.tradeplugin.managers.TradeManager;
import com.tradeplugin.util.Msg;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * /trade <oyuncu> | /trade toggle | /trade reload
 */
public class TradeCommand implements CommandExecutor, TabCompleter {

    private final TradePlugin plugin;
    private final TradeManager tradeManager;

    public TradeCommand(TradePlugin plugin) {
        this.plugin = plugin;
        this.tradeManager = plugin.getTradeManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // /trade reload (konsoldan da çalışır)
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("tradeplugin.admin")) {
                Msg.send(sender, "no-permission", "&cBu komutu kullanma izniniz yok.");
                return true;
            }
            plugin.reloadConfig();
            Msg.send(sender, "reloaded", "&aConfig yeniden yüklendi.");
            return true;
        }

        if (!(sender instanceof Player)) {
            Msg.send(sender, "only-players", "&cBu komutu sadece oyuncular kullanabilir.");
            return true;
        }
        Player player = (Player) sender;

        if (args.length == 1 && args[0].equalsIgnoreCase("toggle")) {
            if (!player.hasPermission("tradeplugin.toggle")) {
                Msg.send(player, "no-permission", "&cBu komutu kullanma izniniz yok.");
                return true;
            }
            boolean nowOpen = tradeManager.toggleTrade(player);
            if (nowOpen) Msg.send(player, "toggle-enabled", "&aTakas istekleri &l✔ AÇIK&a.");
            else Msg.send(player, "toggle-disabled", "&cTakas istekleri &l✘ KAPALI&c.");
            return true;
        }

        if (!player.hasPermission("tradeplugin.trade")) {
            Msg.send(player, "no-permission", "&cBu komutu kullanma izniniz yok.");
            return true;
        }

        if (args.length != 1) {
            Msg.send(player, "usage-trade", "&eKullanım: &f/trade <oyuncu> &7| &f/trade toggle");
            return true;
        }

        Player target = plugin.getServer().getPlayerExact(args[0]);
        if (target == null || !player.canSee(target)) {
            Msg.send(player, "player-not-found", "&cOyuncu bulunamadı: &e{player}", "player", args[0]);
            return true;
        }

        if (target.equals(player)) {
            Msg.send(player, "self-trade", "&cKendine takas isteği gönderemezsin.");
            return true;
        }

        String rangeError = tradeManager.checkRange(player, target);
        if (rangeError != null) {
            player.sendMessage(rangeError);
            return true;
        }

        if (tradeManager.isToggled(target)) {
            Msg.send(player, "player-disabled", "&c{player} takas isteklerini kapatmış.", "player", target.getName());
            return true;
        }

        if (tradeManager.isInTrade(player)) {
            Msg.send(player, "already-trading-self", "&cZaten bir takas yapıyorsun.");
            return true;
        }

        if (tradeManager.isInTrade(target)) {
            Msg.send(player, "already-trading-other", "&c{player} şu an başka biriyle takas yapıyor.",
                    "player", target.getName());
            return true;
        }

        if (tradeManager.hasPendingRequest(target)) {
            Msg.send(player, "already-pending", "&cBu oyuncuya zaten bekleyen bir istek var.");
            return true;
        }

        tradeManager.sendRequest(player, target);
        Msg.send(player, "request-sent", "&a{player} &eadlı oyuncuya takas isteği gönderildi.",
                "player", target.getName());
        sendInteractiveRequest(target, player);
        return true;
    }

    @SuppressWarnings("deprecation")
    private void sendInteractiveRequest(Player target, Player sender) {
        String line = Msg.color("&8&m                                        ");
        target.sendMessage(line);
        target.sendMessage(Msg.raw("request-header", "  &6{player} &esana takas isteği gönderdi!",
                "player", sender.getName()));
        target.sendMessage("");

        TextComponent accept = new TextComponent(TextComponent.fromLegacyText(
                "        " + Msg.raw("request-accept-button", "&a&l[ ✔ Kabul Et ]")));
        accept.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tradereply accept"));
        accept.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                TextComponent.fromLegacyText(Msg.raw("request-accept-hover", "&aTakas isteğini kabul et"))));

        TextComponent space = new TextComponent("    ");

        TextComponent deny = new TextComponent(TextComponent.fromLegacyText(
                Msg.raw("request-deny-button", "&c&l[ ✘ Reddet ]")));
        deny.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tradereply deny"));
        deny.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                TextComponent.fromLegacyText(Msg.raw("request-deny-hover", "&cTakas isteğini reddet"))));

        target.spigot().sendMessage(new BaseComponent[]{accept, space, deny});
        target.sendMessage("");
        target.sendMessage(line);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> list = new ArrayList<>();
        if (args.length != 1) return list;
        String input = args[0].toLowerCase();
        if ("toggle".startsWith(input)) list.add("toggle");
        if (sender.hasPermission("tradeplugin.admin") && "reload".startsWith(input)) list.add("reload");
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (p.equals(sender)) continue;
            if (sender instanceof Player && !((Player) sender).canSee(p)) continue;
            if (p.getName().toLowerCase().startsWith(input)) list.add(p.getName());
        }
        return list;
    }
}
