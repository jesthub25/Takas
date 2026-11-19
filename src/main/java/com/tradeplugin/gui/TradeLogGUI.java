package com.tradeplugin.gui;

import com.tradeplugin.TradePlugin;
import com.tradeplugin.models.TradeRecord;
import com.tradeplugin.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sayfalı takas geçmişi menüsü + detay menüsü. Dolgu camı YOK.
 *
 * - DB sorguları async (fetchData), Bukkit API main thread (rebuildInventory / open).
 * - Menüler InventoryHolder ile tanınır (başlık karşılaştırması yok).
 * - Sayfa/detay işlemleri sırasında "loading" kilidi: spam tıklama yarışını önler.
 */
public final class TradeLogGUI implements InventoryHolder {

    public static final int PREV_SLOT = 45;
    public static final int INFO_SLOT = 49;
    public static final int NEXT_SLOT = 53;
    public static final int DETAIL_BACK_SLOT = 49;

    private static final int PAGE_SIZE = 45;

    /** Log / detay menüsü açık olan oyuncular (eklenti kapanırken menüleri kapatmak için). */
    private static final Set<UUID> OPEN_VIEWERS = ConcurrentHashMap.newKeySet();

    private static NamespacedKey decoKey;

    public static void initDecoKey(NamespacedKey key) {
        decoKey = key;
    }

    public static Set<UUID> getOpenViewers() {
        return OPEN_VIEWERS;
    }

    private final TradePlugin plugin;
    private final Player viewer;
    private final String filterPlayer;

    private volatile int currentPage = 0;
    private volatile int totalPages = 1;
    private volatile int cachedTotal = 0;
    private volatile List<TradeRecord> records = Collections.emptyList();
    private Inventory inventory;
    private final AtomicBoolean loading = new AtomicBoolean(false);

    public TradeLogGUI(TradePlugin plugin, Player viewer, String filterPlayer) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.filterPlayer = (filterPlayer == null || filterPlayer.trim().isEmpty()) ? null : filterPlayer.trim();
    }

    // ── Kilit ───────────────────────────────────────────────────────────

    public boolean tryLock() { return loading.compareAndSet(false, true); }
    public void unlock() { loading.set(false); }

    // ── Veri (ASYNC) ────────────────────────────────────────────────────

    /** DB'den güncel sayfayı çeker. Async thread'de çağrılmalı, Bukkit API içermez. */
    public void fetchData() {
        int total;
        if (filterPlayer != null) {
            total = plugin.getDatabaseManager().getPlayerTradeCount(filterPlayer);
        } else {
            total = plugin.getDatabaseManager().getTotalTradeCount();
        }
        int pages = Math.max(1, (int) Math.ceil((double) total / PAGE_SIZE));
        int page = Math.max(0, Math.min(currentPage, pages - 1));

        List<TradeRecord> list = filterPlayer != null
                ? plugin.getDatabaseManager().getPlayerTrades(filterPlayer, page, PAGE_SIZE)
                : plugin.getDatabaseManager().getAllTrades(page, PAGE_SIZE);

        this.cachedTotal = total;
        this.totalPages = pages;
        this.currentPage = page;
        this.records = list;
    }

    /** Sayfayı değiştirir ve veriyi çeker. ASYNC. */
    public void changePage(int delta) {
        currentPage = Math.max(0, currentPage + delta);
        fetchData();
    }

    // ── Menü (MAIN THREAD) ──────────────────────────────────────────────

    public void rebuildInventory() {
        inventory = Bukkit.createInventory(this, 54, buildTitle());

        List<TradeRecord> list = records;
        for (int i = 0; i < PAGE_SIZE && i < list.size(); i++) {
            inventory.setItem(i, buildTradeItem(list.get(i)));
        }

        if (currentPage > 0) {
            inventory.setItem(PREV_SLOT, makeBtn(Material.ARROW,
                    ChatColor.YELLOW + "◀ Önceki Sayfa",
                    ChatColor.GRAY + "Sayfa " + currentPage + " / " + totalPages));
        }

        inventory.setItem(INFO_SLOT, makeBtn(Material.BOOK,
                ChatColor.GOLD + "Takas Geçmişi",
                ChatColor.GRAY + "Sayfa: " + ChatColor.WHITE + (currentPage + 1) + ChatColor.GRAY + " / " + totalPages,
                ChatColor.GRAY + "Toplam: " + ChatColor.WHITE + cachedTotal + " takas",
                filterPlayer != null
                        ? ChatColor.YELLOW + "Filtre: " + filterPlayer
                        : ChatColor.GRAY + "Tüm oyuncular"));

        if (currentPage < totalPages - 1) {
            inventory.setItem(NEXT_SLOT, makeBtn(Material.ARROW,
                    ChatColor.YELLOW + "Sonraki Sayfa ▶",
                    ChatColor.GRAY + "Sayfa " + (currentPage + 2) + " / " + totalPages));
        }
    }

    /** Liste menüsünü açar. Main thread. */
    public void open() {
        if (inventory == null) rebuildInventory();
        if (!viewer.isOnline()) return;
        viewer.openInventory(inventory);
        OPEN_VIEWERS.add(viewer.getUniqueId());
    }

    private String buildTitle() {
        String base = filterPlayer != null ? filterPlayer + " Takasları" : "Takas Geçmişi";
        return ChatColor.DARK_GRAY + "» " + ChatColor.GOLD + base
                + ChatColor.GRAY + " (" + (currentPage + 1) + "/" + totalPages + ")"
                + ChatColor.DARK_GRAY + " «";
    }

    // ── Detay ───────────────────────────────────────────────────────────

    /** Tıklanan kaydın detayını açar (DB async, menü main). */
    public void openDetail(final int recordIndex) {
        final List<TradeRecord> list = records;
        if (recordIndex < 0 || recordIndex >= list.size()) return;
        if (!tryLock()) return;
        final int tradeId = list.get(recordIndex).getId();

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            final TradeRecord detail = plugin.getDatabaseManager().getTradeDetail(tradeId);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                try {
                    if (!viewer.isOnline()) return;
                    if (detail == null) {
                        Msg.send(viewer, "log-not-found", "&cBu takas kaydı bulunamadı.");
                        return;
                    }
                    DetailView view = new DetailView(this, detail);
                    viewer.openInventory(view.getInventory());
                    OPEN_VIEWERS.add(viewer.getUniqueId());
                } finally {
                    unlock();
                }
            });
        });
    }

    /** Detay menüsünün holder'ı. */
    public static final class DetailView implements InventoryHolder {
        private final TradeLogGUI parent;
        private final Inventory inventory;

        DetailView(TradeLogGUI parent, TradeRecord r) {
            this.parent = parent;
            String date = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss").format(new Date(r.getTimestamp()));
            this.inventory = Bukkit.createInventory(this, 54,
                    ChatColor.DARK_GRAY + "» " + ChatColor.GOLD + "Takas #" + r.getId()
                            + ChatColor.DARK_GRAY + " Detayı «");

            inventory.setItem(4, parent.makeBtn(Material.PAPER,
                    ChatColor.GOLD + "Takas #" + r.getId(),
                    ChatColor.YELLOW + "Tarih: " + ChatColor.WHITE + date,
                    ChatColor.GREEN + r.getPlayer1Name() + ChatColor.GRAY + " ⟷ " + ChatColor.AQUA + r.getPlayer2Name()));

            inventory.setItem(1, parent.makeBtn(Material.PLAYER_HEAD,
                    ChatColor.GREEN + r.getPlayer1Name() + ChatColor.GRAY + " verdi:",
                    ChatColor.GRAY + "Soldaki itemler bu oyuncudan çıktı"));
            inventory.setItem(7, parent.makeBtn(Material.PLAYER_HEAD,
                    ChatColor.AQUA + r.getPlayer2Name() + ChatColor.GRAY + " verdi:",
                    ChatColor.GRAY + "Sağdaki itemler bu oyuncudan çıktı"));

            fill(TradeGUI.LEFT_ITEM_SLOTS, r.getPlayer1Items());
            fill(TradeGUI.RIGHT_ITEM_SLOTS, r.getPlayer2Items());

            inventory.setItem(DETAIL_BACK_SLOT, parent.makeBtn(Material.ARROW,
                    ChatColor.YELLOW + "◀ Geri Dön",
                    ChatColor.GRAY + "Takas listesine geri dön"));
        }

        private void fill(int[] slots, List<ItemStack> items) {
            if (items == null) return;
            for (int i = 0; i < slots.length && i < items.size(); i++) {
                ItemStack it = items.get(i);
                if (it != null) inventory.setItem(slots[i], it.clone());
            }
        }

        public TradeLogGUI getParent() { return parent; }

        @Override
        public Inventory getInventory() { return inventory; }
    }

    // ── Item builder ────────────────────────────────────────────────────

    private ItemStack buildTradeItem(TradeRecord record) {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        String date = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss").format(new Date(record.getTimestamp()));
        meta.setDisplayName(ChatColor.GOLD + record.getPlayer1Name()
                + ChatColor.GRAY + " ⟷ " + ChatColor.AQUA + record.getPlayer2Name());

        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.DARK_GRAY + "──────────────────");
        lore.add(ChatColor.YELLOW + "Tarih: " + ChatColor.WHITE + date);
        lore.add(ChatColor.YELLOW + "ID: " + ChatColor.GRAY + "#" + record.getId());
        lore.add(ChatColor.DARK_GRAY + "──────────────────");
        lore.add(ChatColor.GREEN + "▶ Detay için tıkla");
        meta.setLore(lore);
        if (decoKey != null) {
            meta.getPersistentDataContainer().set(decoKey, PersistentDataType.BYTE, (byte) 1);
        }
        item.setItemMeta(meta);
        return item;
    }

    ItemStack makeBtn(Material mat, String name, String... lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(Arrays.asList(lore));
            if (decoKey != null) {
                meta.getPersistentDataContainer().set(decoKey, PersistentDataType.BYTE, (byte) 1);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    // ── Getters ─────────────────────────────────────────────────────────

    @Override
    public Inventory getInventory() { return inventory; }
    public Player getViewer() { return viewer; }
    public int getCurrentPage() { return currentPage; }
    public int getTotalPages() { return totalPages; }
    public List<TradeRecord> getRecords() { return records; }
}
