package com.tradeplugin.managers;

import com.tradeplugin.TradePlugin;
import com.tradeplugin.gui.TradeGUI;
import com.tradeplugin.models.TradeSession;
import com.tradeplugin.util.Msg;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Aktif takasları, bekleyen istekleri ve toggle durumlarını yönetir.
 *
 * Dupe koruması: iptal/tamamlama anında iki menü de envanterden YENİDEN okunur,
 * böylece aynı tick içinde imlece alınmış bir item asla iki kez geri verilmez.
 */
public class TradeManager {

    private final TradePlugin plugin;

    private final Map<UUID, TradeSession> activeSessions = new ConcurrentHashMap<>();
    /** Alıcı UUID -> gönderen UUID */
    private final Map<UUID, UUID> pendingRequests = new ConcurrentHashMap<>();
    /** Alıcı UUID -> zaman aşımı task ID */
    private final Map<UUID, Integer> requestTimeouts = new ConcurrentHashMap<>();
    private final Set<UUID> toggledOff = Collections.newSetFromMap(new ConcurrentHashMap<UUID, Boolean>());
    private final Map<UUID, TradeGUI> openGUIs = new ConcurrentHashMap<>();

    public TradeManager(TradePlugin plugin) {
        this.plugin = plugin;
    }

    // ── Mesafe ──────────────────────────────────────────────────────────

    /** @return sorun yoksa null, varsa hazır (prefix'li) mesaj */
    public String checkRange(Player a, Player b) {
        boolean crossWorld = plugin.getConfig().getBoolean("trade.allow-cross-world", false);
        if (!a.getWorld().equals(b.getWorld())) {
            return crossWorld ? null : Msg.get("different-world", "&cOyuncu farklı bir dünyada.");
        }
        double maxDist = plugin.getConfig().getDouble("trade.max-distance", 15.0);
        if (maxDist > 0 && a.getLocation().distanceSquared(b.getLocation()) > maxDist * maxDist) {
            return Msg.get("too-far", "&cOyuncu çok uzakta! Maksimum mesafe: &e{distance} &cblok.",
                    "distance", String.valueOf((int) maxDist));
        }
        return null;
    }

    // ── İstekler ────────────────────────────────────────────────────────

    public void sendRequest(final Player sender, final Player target) {
        final UUID senderId = sender.getUniqueId();
        final UUID targetId = target.getUniqueId();

        clearPendingRequest(targetId);
        pendingRequests.put(targetId, senderId);

        long timeoutTicks = Math.max(1, plugin.getConfig().getInt("trade.request-timeout-seconds", 30)) * 20L;
        int taskId = plugin.getServer().getScheduler().scheduleSyncDelayedTask(plugin, () -> {
            if (senderId.equals(pendingRequests.get(targetId))) {
                pendingRequests.remove(targetId);
                requestTimeouts.remove(targetId);
                Player s = plugin.getServer().getPlayer(senderId);
                Player t = plugin.getServer().getPlayer(targetId);
                if (s != null) Msg.send(s, "request-expired-sender", "&cTakas isteği zaman aşımına uğradı.");
                if (t != null) Msg.send(t, "request-expired-target", "&cTakas isteğinin süresi doldu.");
            }
        }, timeoutTicks);

        requestTimeouts.put(targetId, taskId);
    }

    public boolean hasPendingRequest(Player target) {
        return pendingRequests.containsKey(target.getUniqueId());
    }

    public UUID getPendingRequestSender(Player target) {
        return pendingRequests.get(target.getUniqueId());
    }

    public void clearPendingRequest(Player target) {
        clearPendingRequest(target.getUniqueId());
    }

    public void clearPendingRequest(UUID targetId) {
        pendingRequests.remove(targetId);
        Integer tid = requestTimeouts.remove(targetId);
        if (tid != null) plugin.getServer().getScheduler().cancelTask(tid);
    }

    /** Bu oyuncunun BAŞKALARINA gönderdiği tüm bekleyen istekleri siler. */
    public void clearRequestsFrom(UUID senderId) {
        Iterator<Map.Entry<UUID, UUID>> it = pendingRequests.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, UUID> e = it.next();
            if (senderId.equals(e.getValue())) {
                it.remove();
                Integer tid = requestTimeouts.remove(e.getKey());
                if (tid != null) plugin.getServer().getScheduler().cancelTask(tid);
            }
        }
    }

    // ── Oturumlar ───────────────────────────────────────────────────────

    public void startTrade(Player player1, Player player2) {
        TradeSession session = new TradeSession(player1, player2);
        activeSessions.put(player1.getUniqueId(), session);
        activeSessions.put(player2.getUniqueId(), session);

        TradeGUI gui1 = new TradeGUI(plugin, session, player1);
        TradeGUI gui2 = new TradeGUI(plugin, session, player2);
        openGUIs.put(player1.getUniqueId(), gui1);
        openGUIs.put(player2.getUniqueId(), gui2);

        gui1.open();
        gui2.open();
    }

    public TradeSession getSession(Player player) { return activeSessions.get(player.getUniqueId()); }
    public TradeSession getSession(UUID uuid) { return activeSessions.get(uuid); }
    public boolean isInTrade(Player player) { return activeSessions.containsKey(player.getUniqueId()); }
    public TradeGUI getGUI(UUID uuid) { return openGUIs.get(uuid); }

    /** İki menüyü de envanterden yeniden okuyup session'a yazar. */
    private void syncBoth(TradeSession session) {
        TradeGUI g1 = openGUIs.get(session.getPlayer1UUID());
        TradeGUI g2 = openGUIs.get(session.getPlayer2UUID());
        if (g1 != null && g1.getSession() == session) g1.syncMyItemsToSession();
        if (g2 != null && g2.getSession() == session) g2.syncMyItemsToSession();
    }

    /** Geri sayımı durdurur (varsa). */
    public void stopCountdown(TradeSession session) {
        int tid = session.getCountdownTaskId();
        if (tid != -1) {
            plugin.getServer().getScheduler().cancelTask(tid);
            session.setCountdownTaskId(-1);
        }
    }

    public void cancelTrade(TradeSession session, String message, boolean returnItems) {
        cancelTrade(session, message, returnItems, null);
    }

    /**
     * Takası iptal eder.
     *
     * @param message     iki oyuncuya gidecek hazır mesaj (null = mesaj yok)
     * @param returnItems koyulan itemler sahiplerine iade edilsin mi
     * @param closing     şu an menüsünü kendisi kapatan oyuncu (closeInventory çağrılmaz)
     */
    public void cancelTrade(TradeSession session, String message, boolean returnItems, UUID closing) {
        if (session == null || !session.isActive()) return;

        // Önce gerçek envanter içeriğini oku (dupe koruması), sonra durumu değiştir
        syncBoth(session);
        session.setState(TradeSession.State.CANCELLED);
        stopCountdown(session);

        UUID p1 = session.getPlayer1UUID();
        UUID p2 = session.getPlayer2UUID();

        activeSessions.remove(p1, session);
        activeSessions.remove(p2, session);

        if (!p1.equals(closing)) closeGUISilently(p1);
        if (!p2.equals(closing)) closeGUISilently(p2);
        openGUIs.remove(p1);
        openGUIs.remove(p2);

        if (returnItems) {
            giveItems(plugin.getServer().getPlayer(p1), session.getOfferedItems(p1));
            giveItems(plugin.getServer().getPlayer(p2), session.getOfferedItems(p2));
        }
        session.clearOfferedItems(p1);
        session.clearOfferedItems(p2);

        if (message != null) {
            Player pl1 = plugin.getServer().getPlayer(p1);
            Player pl2 = plugin.getServer().getPlayer(p2);
            if (pl1 != null) pl1.sendMessage(message);
            if (pl2 != null) pl2.sendMessage(message);
        }
    }

    /** Geri sayım bitti: itemleri karşılıklı ver ve kaydet. */
    public void completeTrade(TradeSession session) {
        if (session == null || session.getState() != TradeSession.State.COUNTING_DOWN) return;

        UUID p1UUID = session.getPlayer1UUID();
        UUID p2UUID = session.getPlayer2UUID();
        Player player1 = plugin.getServer().getPlayer(p1UUID);
        Player player2 = plugin.getServer().getPlayer(p2UUID);

        if (player1 == null || player2 == null) {
            cancelTrade(session, Msg.get("trade-cancelled-offline", "&cKarşı taraf çevrimdışı, takas iptal edildi."), true);
            return;
        }

        syncBoth(session);
        session.setState(TradeSession.State.COMPLETED);
        stopCountdown(session);

        ItemStack[] p1Items = session.getOfferedItems(p1UUID);
        ItemStack[] p2Items = session.getOfferedItems(p2UUID);

        activeSessions.remove(p1UUID, session);
        activeSessions.remove(p2UUID, session);

        closeGUISilently(p1UUID);
        closeGUISilently(p2UUID);
        openGUIs.remove(p1UUID);
        openGUIs.remove(p2UUID);

        giveItems(player1, p2Items);
        giveItems(player2, p1Items);
        session.clearOfferedItems(p1UUID);
        session.clearOfferedItems(p2UUID);

        String done = Msg.get("trade-completed", "&aTakas başarıyla tamamlandı!");
        player1.sendMessage(done);
        player2.sendMessage(done);

        plugin.getDatabaseManager().saveTrade(
                p1UUID.toString(), session.getPlayer1Name(),
                p2UUID.toString(), session.getPlayer2Name(),
                p1Items, p2Items);
    }

    /** Itemleri envantere ekler, sığmayanları oyuncunun ayağına düşürür. */
    private void giveItems(Player player, ItemStack[] items) {
        if (player == null || items == null) return;
        for (ItemStack item : items) {
            if (item == null) continue;
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(item.clone());
            for (ItemStack drop : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }
        }
    }

    public void closeGUISilently(UUID uuid) {
        TradeGUI gui = openGUIs.get(uuid);
        if (gui == null) return;
        gui.setSilentClose(true);
        Player p = plugin.getServer().getPlayer(uuid);
        if (p != null && gui.getInventory().getViewers().contains(p)) {
            p.closeInventory();
        }
    }

    /** Eklenti kapanırken tüm takasları iptal et ve itemleri iade et. */
    public void cancelAllTrades() {
        Set<TradeSession> processed = new HashSet<>(activeSessions.values());
        String msg = Msg.get("trade-cancelled-shutdown", "&cSunucu/eklenti kapatıldı, takas iptal edildi.");
        for (TradeSession session : processed) {
            cancelTrade(session, msg, true);
        }
        activeSessions.clear();
        openGUIs.clear();
        for (Integer tid : requestTimeouts.values()) {
            plugin.getServer().getScheduler().cancelTask(tid);
        }
        requestTimeouts.clear();
        pendingRequests.clear();
    }

    // ── Toggle ──────────────────────────────────────────────────────────

    public boolean isToggled(Player player) {
        return toggledOff.contains(player.getUniqueId());
    }

    /** @return true = istekler açık, false = kapalı */
    public boolean toggleTrade(Player player) {
        UUID uuid = player.getUniqueId();
        if (toggledOff.remove(uuid)) return true;
        toggledOff.add(uuid);
        return false;
    }
}
