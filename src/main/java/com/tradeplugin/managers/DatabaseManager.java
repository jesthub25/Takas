package com.tradeplugin.managers;

import com.tradeplugin.TradePlugin;
import com.tradeplugin.models.TradeRecord;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * SQLite veritabanı yöneticisi.
 *
 * - SQLite sürücüsü Spigot/Paper/Purpur sunucu jar'ında zaten bulunur (shade/relocate YOK;
 *   relocate edilen sqlite-jdbc native JNI bağlantısını bozar).
 * - Yazmalar tek thread'li executor'da, tüm erişim connectionLock ile senkron.
 */
public class DatabaseManager {

    private final TradePlugin plugin;
    private final String dbPath;
    private Connection connection;

    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "TradePlugin-DB");
        t.setDaemon(true);
        return t;
    });

    private final Object connectionLock = new Object();

    public DatabaseManager(TradePlugin plugin) {
        this.plugin = plugin;
        this.dbPath = plugin.getDataFolder().getAbsolutePath() + File.separator + "trades.db";
    }

    // ── Başlatma ────────────────────────────────────────────────────────

    /** @return başarılıysa true */
    public boolean initialize() {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            plugin.getLogger().severe("SQLite JDBC sürücüsü bulunamadı! (org.sqlite.JDBC)");
            return false;
        }
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().severe("Eklenti klasörü oluşturulamadı: " + plugin.getDataFolder());
            return false;
        }
        synchronized (connectionLock) {
            try {
                connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
                applyPragmas();
                createTables();
                plugin.getLogger().info("Veritabanı bağlandı: " + dbPath);
                return true;
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Veritabanı bağlantı hatası:", e);
                return false;
            }
        }
    }

    private void applyPragmas() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL;");
            st.execute("PRAGMA synchronous=NORMAL;");
            st.execute("PRAGMA busy_timeout=5000;");
            st.execute("PRAGMA temp_store=MEMORY;");
        }
    }

    private void createTables() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS trades ("
                    + "id            INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "player1_uuid  TEXT    NOT NULL,"
                    + "player1_name  TEXT    NOT NULL,"
                    + "player2_uuid  TEXT    NOT NULL,"
                    + "player2_name  TEXT    NOT NULL,"
                    + "player1_items BLOB,"
                    + "player2_items BLOB,"
                    + "timestamp     INTEGER NOT NULL"
                    + ");");
            st.execute("CREATE INDEX IF NOT EXISTS idx_p1_name   ON trades(LOWER(player1_name));");
            st.execute("CREATE INDEX IF NOT EXISTS idx_p2_name   ON trades(LOWER(player2_name));");
            st.execute("CREATE INDEX IF NOT EXISTS idx_timestamp ON trades(timestamp);");
        }
    }

    /** connectionLock içinde çağrılmalı. */
    private boolean ensureConnection() {
        try {
            if (connection != null && !connection.isClosed()) return true;
            plugin.getLogger().warning("DB bağlantısı kopmuş, yeniden bağlanılıyor...");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            applyPragmas();
            createTables();
            return true;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "DB yeniden bağlantı hatası:", e);
            return false;
        }
    }

    // ── Yazma ───────────────────────────────────────────────────────────

    /** Main thread'de serialize eder, yazmayı DB thread'ine bırakır. */
    public void saveTrade(final String p1UUID, final String p1Name, final String p2UUID, final String p2Name,
                          ItemStack[] p1Items, ItemStack[] p2Items) {
        final byte[] p1Bytes = serializeItems(p1Items);
        final byte[] p2Bytes = serializeItems(p2Items);
        final long ts = System.currentTimeMillis();

        try {
            dbExecutor.submit(() -> {
                synchronized (connectionLock) {
                    if (!ensureConnection()) return;
                    String sql = "INSERT INTO trades "
                            + "(player1_uuid,player1_name,player2_uuid,player2_name,player1_items,player2_items,timestamp) "
                            + "VALUES (?,?,?,?,?,?,?)";
                    try (PreparedStatement ps = connection.prepareStatement(sql)) {
                        ps.setString(1, p1UUID);
                        ps.setString(2, p1Name);
                        ps.setString(3, p2UUID);
                        ps.setString(4, p2Name);
                        ps.setBytes(5, p1Bytes);
                        ps.setBytes(6, p2Bytes);
                        ps.setLong(7, ts);
                        ps.executeUpdate();
                    } catch (SQLException e) {
                        plugin.getLogger().log(Level.SEVERE, "Takas kaydedilemedi:", e);
                    }
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            plugin.getLogger().warning("DB kapanıyor, takas kaydı atlandı: " + p1Name + " <-> " + p2Name);
        }
    }

    // ── Okuma (async thread'den çağrılır) ───────────────────────────────

    public List<TradeRecord> getAllTrades(int page, int pageSize) {
        List<TradeRecord> records = new ArrayList<>();
        synchronized (connectionLock) {
            if (!ensureConnection()) return records;
            String sql = "SELECT id,player1_uuid,player1_name,player2_uuid,player2_name,timestamp "
                    + "FROM trades ORDER BY timestamp DESC, id DESC LIMIT ? OFFSET ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setInt(1, pageSize);
                ps.setInt(2, page * pageSize);
                fillRecords(ps, records);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Takas listesi alınamadı:", e);
            }
        }
        return records;
    }

    public List<TradeRecord> getPlayerTrades(String playerName, int page, int pageSize) {
        List<TradeRecord> records = new ArrayList<>();
        synchronized (connectionLock) {
            if (!ensureConnection()) return records;
            String sql = "SELECT id,player1_uuid,player1_name,player2_uuid,player2_name,timestamp "
                    + "FROM trades WHERE LOWER(player1_name)=LOWER(?) OR LOWER(player2_name)=LOWER(?) "
                    + "ORDER BY timestamp DESC, id DESC LIMIT ? OFFSET ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, playerName);
                ps.setString(2, playerName);
                ps.setInt(3, pageSize);
                ps.setInt(4, page * pageSize);
                fillRecords(ps, records);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Oyuncu takas listesi alınamadı:", e);
            }
        }
        return records;
    }

    private void fillRecords(PreparedStatement ps, List<TradeRecord> out) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new TradeRecord(
                        rs.getInt("id"),
                        rs.getString("player1_name"),
                        rs.getString("player2_name"),
                        rs.getString("player1_uuid"),
                        rs.getString("player2_uuid"),
                        rs.getLong("timestamp")));
            }
        }
    }

    public TradeRecord getTradeDetail(int tradeId) {
        byte[] p1 = null;
        byte[] p2 = null;
        TradeRecord r = null;
        synchronized (connectionLock) {
            if (!ensureConnection()) return null;
            String sql = "SELECT id,player1_uuid,player1_name,player2_uuid,player2_name,"
                    + "player1_items,player2_items,timestamp FROM trades WHERE id=?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setInt(1, tradeId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        r = new TradeRecord(
                                rs.getInt("id"),
                                rs.getString("player1_name"),
                                rs.getString("player2_name"),
                                rs.getString("player1_uuid"),
                                rs.getString("player2_uuid"),
                                rs.getLong("timestamp"));
                        p1 = rs.getBytes("player1_items");
                        p2 = rs.getBytes("player2_items");
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Takas detayı alınamadı:", e);
                return null;
            }
        }
        if (r != null) {
            r.setPlayer1Items(deserializeItems(p1));
            r.setPlayer2Items(deserializeItems(p2));
        }
        return r;
    }

    public int getTotalTradeCount() {
        synchronized (connectionLock) {
            if (!ensureConnection()) return 0;
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM trades")) {
                if (rs.next()) return rs.getInt(1);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Toplam sayım hatası:", e);
            }
        }
        return 0;
    }

    public int getPlayerTradeCount(String playerName) {
        synchronized (connectionLock) {
            if (!ensureConnection()) return 0;
            String sql = "SELECT COUNT(*) FROM trades WHERE LOWER(player1_name)=LOWER(?) OR LOWER(player2_name)=LOWER(?)";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, playerName);
                ps.setString(2, playerName);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return rs.getInt(1);
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Oyuncu sayım hatası:", e);
            }
        }
        return 0;
    }

    // ── Serialization ───────────────────────────────────────────────────

    public byte[] serializeItems(ItemStack[] items) {
        if (items == null) return new byte[0];
        int count = 0;
        for (ItemStack it : items) if (it != null) count++;

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             BukkitObjectOutputStream boos = new BukkitObjectOutputStream(baos)) {
            boos.writeInt(count);
            for (int i = 0; i < items.length; i++) {
                if (items[i] != null) {
                    boos.writeInt(i);
                    boos.writeObject(items[i]);
                }
            }
            boos.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Item serialize hatası:", e);
            return new byte[0];
        }
    }

    public List<ItemStack> deserializeItems(byte[] data) {
        List<ItemStack> items = new ArrayList<>();
        if (data == null || data.length == 0) return items;
        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
             BukkitObjectInputStream bois = new BukkitObjectInputStream(bais)) {
            int count = bois.readInt();
            for (int i = 0; i < count; i++) {
                bois.readInt(); // slot index
                Object obj = bois.readObject();
                if (obj instanceof ItemStack) items.add((ItemStack) obj);
            }
        } catch (IOException | ClassNotFoundException e) {
            plugin.getLogger().log(Level.WARNING, "Item deserialize hatası:", e);
        }
        return items;
    }

    // ── Kapatma ─────────────────────────────────────────────────────────

    public void close() {
        dbExecutor.shutdown();
        try {
            if (!dbExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("DB executor 5 saniyede kapanmadı, zorla kapatılıyor.");
                dbExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            dbExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        synchronized (connectionLock) {
            try {
                if (connection != null && !connection.isClosed()) {
                    connection.close();
                    plugin.getLogger().info("Veritabanı bağlantısı kapatıldı.");
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "DB kapatma hatası:", e);
            }
        }
    }
}
