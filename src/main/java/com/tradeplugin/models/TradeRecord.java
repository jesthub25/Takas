package com.tradeplugin.models;

import org.bukkit.inventory.ItemStack;

import java.util.Collections;
import java.util.List;

/**
 * Veritabanından gelen bir takas kaydı.
 */
public class TradeRecord {

    private final int id;
    private final String player1Name;
    private final String player2Name;
    private final String player1UUID;
    private final String player2UUID;
    private final long timestamp;

    private List<ItemStack> player1Items = Collections.emptyList();
    private List<ItemStack> player2Items = Collections.emptyList();

    public TradeRecord(int id, String player1Name, String player2Name,
                       String player1UUID, String player2UUID, long timestamp) {
        this.id = id;
        this.player1Name = player1Name;
        this.player2Name = player2Name;
        this.player1UUID = player1UUID;
        this.player2UUID = player2UUID;
        this.timestamp = timestamp;
    }

    public int getId() { return id; }
    public String getPlayer1Name() { return player1Name; }
    public String getPlayer2Name() { return player2Name; }
    public String getPlayer1UUID() { return player1UUID; }
    public String getPlayer2UUID() { return player2UUID; }
    public long getTimestamp() { return timestamp; }

    public List<ItemStack> getPlayer1Items() { return player1Items; }
    public List<ItemStack> getPlayer2Items() { return player2Items; }

    public void setPlayer1Items(List<ItemStack> items) {
        this.player1Items = items != null ? items : Collections.<ItemStack>emptyList();
    }

    public void setPlayer2Items(List<ItemStack> items) {
        this.player2Items = items != null ? items : Collections.<ItemStack>emptyList();
    }
}
