package com.tradeplugin.commands;

import com.tradeplugin.TradePlugin;
import com.tradeplugin.managers.TradeManager;
import com.tradeplugin.util.Msg;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * /tradereply accept|deny (sohbetteki butonlardan otomatik çağrılır)
 */
public class TradeReplyCommand implements CommandExecutor, TabCompleter {

    private final TradePlugin plugin;
    private final TradeManager tradeManager;

    public TradeReplyCommand(TradePlugin plugin) {
        this.plugin = plugin;
        this.tradeManager = plugin.getTradeManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            Msg.send(sender, "only-players", "&cBu komutu sadece oyuncular kullanabilir.");
            return true;
        }
        Player player = (Player) sender;

        if (args.length < 1) {
            Msg.send(player, "usage-reply", "&eKullanım: &f/tradereply accept|deny");
            return true;
        }

        String action = args[0].toLowerCase();
        if (!action.equals("accept") && !action.equals("deny")) {
            Msg.send(player, "usage-reply", "&eKullanım: &f/tradereply accept|deny");
            return true;
        }

        if (!tradeManager.hasPendingRequest(player)) {
            Msg.send(player, "no-pending", "&cSana gönderilmiş bekleyen takas isteği yok.");
            return true;
        }

        UUID senderUUID = tradeManager.getPendingRequestSender(player);
        Player requester = senderUUID != null ? plugin.getServer().getPlayer(senderUUID) : null;

        if (requester == null || !requester.isOnline()) {
            tradeManager.clearPendingRequest(player);
            Msg.send(player, "requester-offline", "&cTakas isteği gönderen oyuncu artık çevrimdışı.");
            return true;
        }

        if (action.equals("accept")) {
            handleAccept(player, requester);
        } else {
            handleDeny(player, requester);
        }
        return true;
    }

    private void handleAccept(Player target, Player requester) {
        tradeManager.clearPendingRequest(target);

        if (tradeManager.isInTrade(target) || tradeManager.isInTrade(requester)) {
            Msg.send(target, "someone-trading", "&cTaraflardan biri zaten başka bir takas yapıyor.");
            return;
        }

        String rangeError = tradeManager.checkRange(target, requester);
        if (rangeError != null) {
            String msg = Msg.get("moved-away", "&cOyuncu uzaklaştı, takas başlatılamadı.");
            target.sendMessage(msg);
            requester.sendMessage(msg);
            return;
        }

        Msg.send(target, "request-accepted-target", "&aTakas isteği kabul edildi!");
        Msg.send(requester, "request-accepted-sender", "&a{player} takas isteğini kabul etti!",
                "player", target.getName());
        tradeManager.startTrade(requester, target);
    }

    private void handleDeny(Player target, Player requester) {
        tradeManager.clearPendingRequest(target);
        Msg.send(target, "request-denied-target", "&cTakas isteği reddedildi.");
        Msg.send(requester, "request-denied-sender", "&c{player} takas isteğini reddetti.",
                "player", target.getName());
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> list = new ArrayList<>();
        if (args.length == 1) {
            String in = args[0].toLowerCase();
            if ("accept".startsWith(in)) list.add("accept");
            if ("deny".startsWith(in)) list.add("deny");
        }
        return list;
    }
}
