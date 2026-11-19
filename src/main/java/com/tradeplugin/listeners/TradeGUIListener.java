package com.tradeplugin.listeners;

import com.tradeplugin.TradePlugin;
import com.tradeplugin.gui.TradeGUI;
import com.tradeplugin.gui.TradeLogGUI;
import com.tradeplugin.managers.TradeManager;
import com.tradeplugin.models.TradeSession;
import com.tradeplugin.util.Msg;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * Takas menüsü ve takas geçmişi menüsü olayları.
 *
 * Uyumluluk: InventoryView metodları HİÇ kullanılmaz (1.21'de InventoryView class -> interface
 * oldu; 1.16.5 API ile derlenen eklentide getView().xxx() çağrısı IncompatibleClassChangeError
 * verir). Üst envanter event.getInventory(), menü tespiti InventoryHolder ile yapılır.
 */
public class TradeGUIListener implements Listener {

    private final TradePlugin plugin;
    private final TradeManager tradeManager;

    public TradeGUIListener(TradePlugin plugin) {
        this.plugin = plugin;
        this.tradeManager = plugin.getTradeManager();
    }

    // ═══════════════════════════════════════════════════════════════
    //  CLICK
    // ═══════════════════════════════════════════════════════════════
    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();

        Inventory top = event.getInventory();
        InventoryHolder holder = top.getHolder();

        if (holder instanceof TradeLogGUI) {
            event.setCancelled(true);
            handleLogListClick(event, player, (TradeLogGUI) holder, top.getSize());
            return;
        }
        if (holder instanceof TradeLogGUI.DetailView) {
            event.setCancelled(true);
            handleLogDetailClick(event, player, (TradeLogGUI.DetailView) holder, top.getSize());
            return;
        }
        if (!(holder instanceof TradeGUI)) return;

        TradeGUI gui = (TradeGUI) holder;
        if (gui != tradeManager.getGUI(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }

        TradeSession session = gui.getSession();
        if (session == null || !session.isActive()) {
            event.setCancelled(true);
            return;
        }

        InventoryAction action = event.getAction();
        // Çift tıklama toplama: sağ bölgedeki (karşı tarafın) itemleri imlece çekebilir -> DUPE
        if (action == InventoryAction.COLLECT_TO_CURSOR || action == InventoryAction.UNKNOWN) {
            event.setCancelled(true);
            return;
        }

        int rawSlot = event.getRawSlot();
        int topSize = top.getSize();

        if (rawSlot >= topSize) {
            // Oyuncunun kendi envanteri
            if (event.isShiftClick()) {
                handleShiftFromPlayer(event, player, gui, session);
            }
            return;
        }
        if (rawSlot < 0) return; // menü dışına tıklama (item atma) serbest

        handleTopClick(event, player, gui, session, rawSlot);
    }

    private void handleTopClick(InventoryClickEvent event, Player player,
                                TradeGUI gui, TradeSession session, int slot) {
        if (slot == TradeGUI.CANCEL_SLOT) {
            event.setCancelled(true);
            // Kapatma -> InventoryCloseEvent -> iptal + iade
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (gui.getInventory().getViewers().contains(player)) player.closeInventory();
            });
            return;
        }

        if (slot == TradeGUI.ACCEPT_SLOT) {
            event.setCancelled(true);
            handleAccept(player, gui, session);
            return;
        }

        if (session.getState() == TradeSession.State.COUNTING_DOWN || gui.isProtectedSlot(slot)) {
            event.setCancelled(true);
            return;
        }

        // Sol bölge: serbest. Değişiklik bir sonraki tick'te okunur.
        scheduleSync(player, gui, session);
    }

    private void handleShiftFromPlayer(InventoryClickEvent event, Player player,
                                       TradeGUI gui, TradeSession session) {
        event.setCancelled(true);
        if (session.getState() == TradeSession.State.COUNTING_DOWN) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;

        Inventory topInv = gui.getInventory();
        for (int slot : TradeGUI.LEFT_ITEM_SLOTS) {
            ItemStack existing = topInv.getItem(slot);
            if (existing == null || existing.getType() == Material.AIR) {
                topInv.setItem(slot, clicked.clone());
                event.setCurrentItem(null);
                afterItemsChanged(player, gui, session);
                return;
            }
        }
        Msg.send(player, "offer-full", "&cTakas bölgen dolu!");
    }

    private void scheduleSync(final Player player, final TradeGUI gui, final TradeSession session) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!session.isActive() || gui != tradeManager.getGUI(player.getUniqueId())) return;
            afterItemsChanged(player, gui, session);
        });
    }

    /** Sol bölgeyi okur; gerçekten değiştiyse onayları sıfırlar ve karşı tarafı günceller. */
    private void afterItemsChanged(Player player, TradeGUI gui, TradeSession session) {
        boolean changed = gui.syncMyItemsToSession();
        if (!changed) return;
        if (session.anyAccepted() || session.getState() == TradeSession.State.COUNTING_DOWN) {
            doResetAcceptance(session, Msg.get("items-changed", "&eİtemler değişti, onaylar sıfırlandı."));
        }
        refreshPartnerGUI(player, session);
    }

    // ── Onay ────────────────────────────────────────────────────────────

    private void handleAccept(Player player, TradeGUI gui, TradeSession session) {
        UUID uuid = player.getUniqueId();

        if (session.getState() == TradeSession.State.COUNTING_DOWN) {
            doResetAcceptance(session, Msg.get("countdown-cancelled", "&eGeri sayım durduruldu, onaylar sıfırlandı."));
            return;
        }

        if (session.isAccepted(uuid)) {
            session.setAccepted(uuid, false);
            Msg.send(player, "unaccepted-self", "&eOnayın geri alındı.");
            gui.buildControlBar();
            refreshPartnerGUI(player, session);
            return;
        }

        // Onaydan önce son durumu oku (aynı tick içindeki değişiklikler dahil)
        gui.syncMyItemsToSession();
        session.setAccepted(uuid, true);
        Msg.send(player, "accepted-self", "&aTakas onaylandı, karşı taraf bekleniyor...");
        gui.buildControlBar();
        refreshPartnerGUI(player, session);

        if (session.bothAccepted() && session.getState() == TradeSession.State.WAITING) {
            startCountdown(session);
        }
    }

    private void startCountdown(final TradeSession session) {
        session.setState(TradeSession.State.COUNTING_DOWN);
        final int totalSec = Math.max(1, plugin.getConfig().getInt("trade.countdown-seconds", 5));
        final int[] remaining = {totalSec};

        String startMsg = Msg.get("countdown-start", "&eTakas &6{seconds} &esaniye içinde tamamlanacak!",
                "seconds", String.valueOf(totalSec));
        sendBoth(session, startMsg);

        TradeGUI g1 = tradeManager.getGUI(session.getPlayer1UUID());
        TradeGUI g2 = tradeManager.getGUI(session.getPlayer2UUID());
        if (g1 != null) { g1.buildControlBar(); g1.updateCountdown(totalSec); }
        if (g2 != null) { g2.buildControlBar(); g2.updateCountdown(totalSec); }

        int taskId = plugin.getServer().getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
            if (session.getState() != TradeSession.State.COUNTING_DOWN) {
                tradeManager.stopCountdown(session);
                return;
            }
            remaining[0]--;
            if (remaining[0] > 0) {
                TradeGUI a = tradeManager.getGUI(session.getPlayer1UUID());
                TradeGUI b = tradeManager.getGUI(session.getPlayer2UUID());
                if (a != null) a.updateCountdown(remaining[0]);
                if (b != null) b.updateCountdown(remaining[0]);
            } else {
                tradeManager.stopCountdown(session);
                tradeManager.completeTrade(session);
            }
        }, 20L, 20L);

        session.setCountdownTaskId(taskId);
    }

    /** Onayları sıfırlar; geri sayım varsa durdurur. */
    private void doResetAcceptance(TradeSession session, String message) {
        tradeManager.stopCountdown(session);
        if (session.getState() == TradeSession.State.COUNTING_DOWN) {
            session.setState(TradeSession.State.WAITING);
        }
        session.resetBothAccepted();

        TradeGUI g1 = tradeManager.getGUI(session.getPlayer1UUID());
        TradeGUI g2 = tradeManager.getGUI(session.getPlayer2UUID());
        if (g1 != null) g1.buildControlBar();
        if (g2 != null) g2.buildControlBar();

        sendBoth(session, message);
    }

    private void refreshPartnerGUI(Player player, TradeSession session) {
        TradeGUI partnerGUI = tradeManager.getGUI(session.getPartner(player.getUniqueId()));
        if (partnerGUI != null) partnerGUI.refreshPartnerItems();
    }

    private void sendBoth(TradeSession session, String msg) {
        if (msg == null) return;
        Player a = plugin.getServer().getPlayer(session.getPlayer1UUID());
        Player b = plugin.getServer().getPlayer(session.getPlayer2UUID());
        if (a != null) a.sendMessage(msg);
        if (b != null) b.sendMessage(msg);
    }

    // ═══════════════════════════════════════════════════════════════
    //  DRAG
    // ═══════════════════════════════════════════════════════════════
    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();

        Inventory top = event.getInventory();
        InventoryHolder holder = top.getHolder();
        int topSize = top.getSize();

        if (holder instanceof TradeLogGUI || holder instanceof TradeLogGUI.DetailView) {
            for (int slot : event.getRawSlots()) {
                if (slot < topSize) {
                    event.setCancelled(true);
                    return;
                }
            }
            return;
        }
        if (!(holder instanceof TradeGUI)) return;

        TradeGUI gui = (TradeGUI) holder;
        TradeSession session = gui.getSession();
        if (gui != tradeManager.getGUI(player.getUniqueId()) || session == null || !session.isActive()) {
            event.setCancelled(true);
            return;
        }

        boolean touchesTop = false;
        for (int slot : event.getRawSlots()) {
            if (slot < topSize) {
                touchesTop = true;
                if (gui.isProtectedSlot(slot)) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
        if (!touchesTop) return;

        if (session.getState() == TradeSession.State.COUNTING_DOWN) {
            event.setCancelled(true);
            return;
        }
        scheduleSync(player, gui, session);
    }

    // ═══════════════════════════════════════════════════════════════
    //  CLOSE
    // ═══════════════════════════════════════════════════════════════
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;
        Player player = (Player) event.getPlayer();
        InventoryHolder holder = event.getInventory().getHolder();

        if (holder instanceof TradeLogGUI || holder instanceof TradeLogGUI.DetailView) {
            TradeLogGUI.getOpenViewers().remove(player.getUniqueId());
            return;
        }
        if (!(holder instanceof TradeGUI)) return;

        TradeGUI gui = (TradeGUI) holder;
        if (gui.isSilentClose()) return;
        if (gui != tradeManager.getGUI(player.getUniqueId())) return;

        TradeSession session = gui.getSession();
        if (session == null || !session.isActive()) return;

        tradeManager.cancelTrade(session,
                Msg.get("trade-cancelled-close", "&cTakas iptal edildi (menü kapatıldı)."),
                true, player.getUniqueId());
    }

    // ═══════════════════════════════════════════════════════════════
    //  TRADELOG
    // ═══════════════════════════════════════════════════════════════
    private void handleLogListClick(InventoryClickEvent event, final Player player,
                                    final TradeLogGUI logGui, int topSize) {
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= topSize) return;
        if (player != logGui.getViewer()) return;

        if (slot == TradeLogGUI.PREV_SLOT && logGui.getCurrentPage() > 0) {
            changePage(player, logGui, -1);
        } else if (slot == TradeLogGUI.NEXT_SLOT && logGui.getCurrentPage() < logGui.getTotalPages() - 1) {
            changePage(player, logGui, 1);
        } else if (slot < 45) {
            logGui.openDetail(slot);
        }
    }

    private void changePage(final Player player, final TradeLogGUI logGui, final int delta) {
        if (!logGui.tryLock()) return;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                logGui.changePage(delta);
            } catch (Throwable t) {
                plugin.getLogger().warning("Takas geçmişi yüklenemedi: " + t.getMessage());
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                try {
                    if (!player.isOnline()) return;
                    logGui.rebuildInventory();
                    logGui.open();
                } finally {
                    logGui.unlock();
                }
            });
        });
    }

    private void handleLogDetailClick(InventoryClickEvent event, Player player,
                                      TradeLogGUI.DetailView view, int topSize) {
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= topSize) return;
        if (slot == TradeLogGUI.DETAIL_BACK_SLOT) {
            // Click event içinde menü açmak yerine bir sonraki tick
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) view.getParent().open();
            });
        }
    }
}
