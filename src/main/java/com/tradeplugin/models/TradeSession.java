package com.tradeplugin.models;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Aktif bir takas oturumu. Her iki oyuncu aynı nesneyi paylaşır.
 * Session'da tutulan itemler her zaman GUI'deki itemlerin KOPYASIDIR.
 */
public class TradeSession {

    public enum State {
        WAITING,
        COUNTING_DOWN,
        COMPLETED,
        CANCELLED
    }

    /** Her oyuncunun item koyabileceği slot sayısı (3x3). */
    public static final int OFFER_SLOT_COUNT = 9;

    private final UUID player1UUID;
    private final UUID player2UUID;
    private final String player1Name;
    private final String player2Name;

    private final Map<UUID, ItemStack[]> offeredItems = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> accepted = new ConcurrentHashMap<>();

    private volatile State state = State.WAITING;
    private final long startTime;
    private volatile int countdownTaskId = -1;

    public TradeSession(Player player1, Player player2) {
        this.player1UUID = player1.getUniqueId();
        this.player2UUID = player2.getUniqueId();
        this.player1Name = player1.getName();
        this.player2Name = player2.getName();
        this.startTime = System.currentTimeMillis();

        offeredItems.put(player1UUID, new ItemStack[OFFER_SLOT_COUNT]);
        offeredItems.put(player2UUID, new ItemStack[OFFER_SLOT_COUNT]);
        accepted.put(player1UUID, false);
        accepted.put(player2UUID, false);
    }

    public UUID getPlayer1UUID() { return player1UUID; }
    public UUID getPlayer2UUID() { return player2UUID; }
    public String getPlayer1Name() { return player1Name; }
    public String getPlayer2Name() { return player2Name; }
    public State getState() { return state; }
    public void setState(State s) { this.state = s; }
    public long getStartTime() { return startTime; }
    public int getCountdownTaskId() { return countdownTaskId; }
    public void setCountdownTaskId(int id) { this.countdownTaskId = id; }

    public boolean isActive() {
        return state == State.WAITING || state == State.COUNTING_DOWN;
    }

    public UUID getPartner(UUID uuid) {
        return uuid.equals(player1UUID) ? player2UUID : player1UUID;
    }

    public String getPartnerName(UUID uuid) {
        return uuid.equals(player1UUID) ? player2Name : player1Name;
    }

    public boolean isParticipant(UUID uuid) {
        return player1UUID.equals(uuid) || player2UUID.equals(uuid);
    }

    public ItemStack[] getOfferedItems(UUID uuid) {
        ItemStack[] items = offeredItems.get(uuid);
        return items != null ? items : new ItemStack[OFFER_SLOT_COUNT];
    }

    public void setOfferedItems(UUID uuid, ItemStack[] items) {
        offeredItems.put(uuid, items != null ? items : new ItemStack[OFFER_SLOT_COUNT]);
    }

    public void clearOfferedItems(UUID uuid) {
        offeredItems.put(uuid, new ItemStack[OFFER_SLOT_COUNT]);
    }

    public boolean isAccepted(UUID uuid) {
        return Boolean.TRUE.equals(accepted.get(uuid));
    }

    public void setAccepted(UUID uuid, boolean value) {
        accepted.put(uuid, value);
    }

    public boolean bothAccepted() {
        return isAccepted(player1UUID) && isAccepted(player2UUID);
    }

    public boolean anyAccepted() {
        return isAccepted(player1UUID) || isAccepted(player2UUID);
    }

    public void resetBothAccepted() {
        accepted.put(player1UUID, false);
        accepted.put(player2UUID, false);
    }
}
