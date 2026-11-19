package com.tradeplugin.gui;

import com.tradeplugin.TradePlugin;
import com.tradeplugin.models.TradeSession;
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

import java.util.Arrays;
import java.util.UUID;

/**
 * 54 slotluk takas menüsü. Dolgu camı YOK — boş slotlar gerçekten boş.
 *
 * Satır 0 : [_][Sen][_][_][_][_][_][Karşı][_]
 * Satır 1-3: Sol 3x3 = senin itemlerin | Sağ 3x3 = karşı tarafın itemleri
 * Satır 5 : [İptal][_][_][Onay][Bilgi][Durum][_][_][_]
 *
 * Menü, kendi InventoryHolder'ı olarak tanınır (başlık / InventoryView kullanılmaz,
 * böylece 1.16.5 – 1.21.x arası tüm sürümlerde güvenle çalışır).
 */
public final class TradeGUI implements InventoryHolder {

    public static final int[] LEFT_ITEM_SLOTS = {9, 10, 11, 18, 19, 20, 27, 28, 29};
    public static final int[] RIGHT_ITEM_SLOTS = {15, 16, 17, 24, 25, 26, 33, 34, 35};

    public static final int MY_INFO_SLOT = 1;
    public static final int PARTNER_INFO_SLOT = 7;

    public static final int CANCEL_SLOT = 45;
    public static final int ACCEPT_SLOT = 48;
    public static final int COUNTDOWN_SLOT = 49;
    public static final int STATUS_SLOT = 50;

    private static NamespacedKey decorationKey;

    public static void initDecorationKey(NamespacedKey key) {
        decorationKey = key;
    }

    private final TradePlugin plugin;
    private final TradeSession session;
    private final Player viewer;
    private final Inventory inventory;
    private volatile boolean silentClose = false;

    public TradeGUI(TradePlugin plugin, TradeSession session, Player viewer) {
        this.plugin = plugin;
        this.session = session;
        this.viewer = viewer;
        this.inventory = Bukkit.createInventory(this, 54, buildTitle());
        buildLayout();
    }

    private String buildTitle() {
        return ChatColor.DARK_GRAY + "» " + ChatColor.GOLD + "Takas"
                + ChatColor.DARK_GRAY + " | " + ChatColor.YELLOW
                + session.getPartnerName(viewer.getUniqueId())
                + ChatColor.DARK_GRAY + " «";
    }

    /** Menüyü sıfırdan kurar. Main thread. */
    public void buildLayout() {
        inventory.clear();

        inventory.setItem(MY_INFO_SLOT, makeButton(Material.OAK_SIGN,
                ChatColor.GOLD + "✦ " + viewer.getName() + " ✦",
                ChatColor.YELLOW + "Senin bölgen (sol 3x3)",
                ChatColor.GRAY + "Vermek istediğin itemleri buraya koy"));
        inventory.setItem(PARTNER_INFO_SLOT, makeButton(Material.OAK_SIGN,
                ChatColor.AQUA + "✦ " + session.getPartnerName(viewer.getUniqueId()) + " ✦",
                ChatColor.YELLOW + "Karşı tarafın bölgesi (sağ 3x3)",
                ChatColor.GRAY + "Bu bölgeye dokunamazsın"));

        loadLeftItems();
        loadRightItems();
        buildControlBar();
    }

    private void loadLeftItems() {
        ItemStack[] mine = session.getOfferedItems(viewer.getUniqueId());
        for (int i = 0; i < LEFT_ITEM_SLOTS.length; i++) {
            ItemStack item = i < mine.length ? mine[i] : null;
            inventory.setItem(LEFT_ITEM_SLOTS[i], item != null ? item.clone() : null);
        }
    }

    private void loadRightItems() {
        UUID partner = session.getPartner(viewer.getUniqueId());
        ItemStack[] theirs = session.getOfferedItems(partner);
        for (int i = 0; i < RIGHT_ITEM_SLOTS.length; i++) {
            ItemStack item = i < theirs.length ? theirs[i] : null;
            inventory.setItem(RIGHT_ITEM_SLOTS[i], item != null ? item.clone() : null);
        }
    }

    public void buildControlBar() {
        UUID me = viewer.getUniqueId();
        boolean myOk = session.isAccepted(me);
        boolean partnerOk = session.isAccepted(session.getPartner(me));
        String partner = session.getPartnerName(me);
        int cdSec = plugin.getConfig().getInt("trade.countdown-seconds", 5);

        inventory.setItem(CANCEL_SLOT, makeButton(Material.BARRIER,
                ChatColor.RED + "✘ Takası İptal Et",
                ChatColor.GRAY + "Menüyü kapatır, itemlerin geri verilir"));

        if (session.getState() == TradeSession.State.COUNTING_DOWN) {
            inventory.setItem(ACCEPT_SLOT, makeButton(Material.YELLOW_WOOL,
                    ChatColor.YELLOW + "⏳ Geri sayım sürüyor",
                    ChatColor.GRAY + "Durdurmak için tıkla"));
        } else if (!myOk) {
            inventory.setItem(ACCEPT_SLOT, makeButton(Material.LIME_WOOL,
                    ChatColor.GREEN + "✔ Kabul Et",
                    ChatColor.GRAY + "Takası onaylamak için tıkla",
                    ChatColor.YELLOW + "İki taraf onaylayınca " + cdSec + " saniye sayılır"));
        } else {
            inventory.setItem(ACCEPT_SLOT, makeButton(Material.YELLOW_WOOL,
                    ChatColor.YELLOW + "⏳ Onayladın, bekleniyor...",
                    ChatColor.GRAY + "Onayı geri almak için tıkla"));
        }

        if (session.getState() != TradeSession.State.COUNTING_DOWN) {
            inventory.setItem(COUNTDOWN_SLOT, makeButton(Material.PAPER,
                    ChatColor.AQUA + "ℹ Takas Bilgisi",
                    ChatColor.GRAY + "İki taraf da onaylarsa sayım başlar",
                    ChatColor.GRAY + "Item değişirse onaylar sıfırlanır",
                    ChatColor.YELLOW + "Menüyü kapatmak = iptal"));
        }

        inventory.setItem(STATUS_SLOT, partnerOk
                ? makeButton(Material.GREEN_WOOL,
                        ChatColor.GREEN + "✔ " + partner + " onayladı",
                        ChatColor.GRAY + "Karşı taraf hazır")
                : makeButton(Material.RED_WOOL,
                        ChatColor.RED + "✘ " + partner + " bekliyor",
                        ChatColor.GRAY + "Henüz onaylamadı"));
    }

    public void updateCountdown(int seconds) {
        inventory.setItem(COUNTDOWN_SLOT, makeButton(Material.CLOCK,
                ChatColor.GOLD + "⏱ " + seconds + " saniye",
                ChatColor.YELLOW + "Takas tamamlanıyor...",
                ChatColor.GRAY + "Durdurmak için onay butonuna tıkla"));
    }

    /** Karşı tarafın bölgesini ve kontrol barını yeniler. */
    public void refreshPartnerItems() {
        loadRightItems();
        buildControlBar();
    }

    /**
     * Sol bölgedeki itemleri (kopya olarak) session'a yazar.
     *
     * @return itemler değiştiyse true
     */
    public boolean syncMyItemsToSession() {
        ItemStack[] items = new ItemStack[LEFT_ITEM_SLOTS.length];
        for (int i = 0; i < LEFT_ITEM_SLOTS.length; i++) {
            ItemStack raw = inventory.getItem(LEFT_ITEM_SLOTS[i]);
            if (raw != null && raw.getType() != Material.AIR && !isDecorationItem(raw)) {
                items[i] = raw.clone();
            }
        }
        ItemStack[] old = session.getOfferedItems(viewer.getUniqueId());
        boolean changed = !Arrays.equals(old, items);
        session.setOfferedItems(viewer.getUniqueId(), items);
        return changed;
    }

    public void open() {
        viewer.openInventory(inventory);
    }

    public boolean isLeftSlot(int slot) {
        for (int s : LEFT_ITEM_SLOTS) if (s == slot) return true;
        return false;
    }

    public boolean isRightSlot(int slot) {
        for (int s : RIGHT_ITEM_SLOTS) if (s == slot) return true;
        return false;
    }

    /** Sol bölge dışındaki her slot korumalı. */
    public boolean isProtectedSlot(int slot) {
        return !isLeftSlot(slot);
    }

    /** PDC etiketi ile işaretlenmiş menü itemi mi? (Oyuncunun gerçek itemleri asla etiketli olmaz.) */
    public boolean isDecorationItem(ItemStack item) {
        if (decorationKey == null || item == null || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(decorationKey, PersistentDataType.BYTE);
    }

    private ItemStack makeButton(Material mat, String name, String... lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(Arrays.asList(lore));
            if (decorationKey != null) {
                meta.getPersistentDataContainer().set(decorationKey, PersistentDataType.BYTE, (byte) 1);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    @Override
    public Inventory getInventory() { return inventory; }
    public TradeSession getSession() { return session; }
    public Player getViewer() { return viewer; }
    public boolean isSilentClose() { return silentClose; }
    public void setSilentClose(boolean v) { this.silentClose = v; }
}
