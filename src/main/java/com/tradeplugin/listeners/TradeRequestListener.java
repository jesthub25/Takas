package com.tradeplugin.listeners;

import com.tradeplugin.TradePlugin;
import com.tradeplugin.managers.TradeManager;
import com.tradeplugin.models.TradeSession;
import com.tradeplugin.gui.TradeLogGUI;
import com.tradeplugin.util.Msg;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Oyuncu çıkınca: aktif takası iptal et (itemler iade), bekleyen istekleri temizle.
 */
public class TradeRequestListener implements Listener {

    private final TradeManager tradeManager;

    public TradeRequestListener(TradePlugin plugin) {
        this.tradeManager = plugin.getTradeManager();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        handleLeave(event.getPlayer());
    }

    private void handleLeave(Player player) {
        TradeSession session = tradeManager.getSession(player);
        if (session != null) {
            tradeManager.cancelTrade(session,
                    Msg.get("trade-cancelled-quit", "&c{player} sunucudan ayrıldı, takas iptal edildi.",
                            "player", player.getName()),
                    true);
        }
        tradeManager.clearPendingRequest(player);
        tradeManager.clearRequestsFrom(player.getUniqueId());
        TradeLogGUI.getOpenViewers().remove(player.getUniqueId());
    }
}
