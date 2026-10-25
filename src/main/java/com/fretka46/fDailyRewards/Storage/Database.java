package com.fretka46.fDailyRewards.Storage;

import com.fretka46.fDailyRewards.Core.PassMath;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Jediný přístup k SQLite. Jedno spojení, všechny metody serializované zámkem (volá se z hlavního vlákna,
 * z PlaceholderAPI i z asynchronních vláken jiných pluginů). Schéma má verzi v tabulce {@code meta};
 * před migrací se vytvoří záloha souboru.
 */
public final class Database implements AutoCloseable {
    public static final int SCHEMA_VERSION = 2;

    /** Zdroj claimu. Do cooldownu se počítá jen {@code CLAIM} (běžné vyzvednutí hráčem). */
    public enum Source {
        CLAIM, GIFT, UNLOCK;

        String db() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public record PendingGift(long id, int day, long createdAtMillis) {}

    public static final class DbException extends RuntimeException {
        public DbException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final Connection conn;
    private final Object lock = new Object();

    private Database(Connection conn) {
        this.conn = conn;
    }

    /**
     * @param url JDBC URL
     * @param file soubor databáze pro zálohu před migrací (null = bez zálohy, např. v paměti)
     */
    public static Database open(String url, Path file, Consumer<String> log) {
        try {
            Class.forName("org.sqlite.JDBC");
            Connection c = DriverManager.getConnection(url);
            Database db = new Database(c);
            db.migrate(file, log);
            return db;
        } catch (SQLException | ClassNotFoundException e) {
            throw new DbException("Nelze otevřít databázi: " + e.getMessage(), e);
        }
    }

    public static Database openFile(Path file, Consumer<String> log) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
        } catch (IOException e) {
            throw new DbException("Nelze vytvořit složku databáze", e);
        }
        return open("jdbc:sqlite:" + file.toAbsolutePath(), file, log);
    }

    public static Database openMemory() {
        return open("jdbc:sqlite::memory:", null, s -> {});
    }

    // ------------------------------------------------------------------ schema

    private void migrate(Path file, Consumer<String> log) throws SQLException {
        int version = currentVersion();
        if (version >= SCHEMA_VERSION) return;
        if (file != null && Files.exists(file) && version >= 1) {
            Path bak = file.resolveSibling(file.getFileName() + ".bak-v" + version + "-"
                    + java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")));
            try {
                Files.copy(file, bak, StandardCopyOption.COPY_ATTRIBUTES);
                log.accept("Záloha databáze před migrací v" + version + " -> v" + SCHEMA_VERSION + ": " + bak.getFileName());
            } catch (IOException e) {
                throw new SQLException("Záloha databáze selhala, migrace přerušena: " + e.getMessage(), e);
            }
        }
        conn.setAutoCommit(false);
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS rewards_claimed (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "uuid TEXT NOT NULL, date TEXT NOT NULL, day INTEGER NOT NULL)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS daily_logins (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "uuid TEXT NOT NULL, date TEXT NOT NULL)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS vip_players (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "uuid TEXT NOT NULL, month INTEGER NOT NULL)");
            if (!hasColumn("rewards_claimed", "source"))
                st.executeUpdate("ALTER TABLE rewards_claimed ADD COLUMN source TEXT NOT NULL DEFAULT 'claim'");
            if (!hasColumn("rewards_claimed", "month")) {
                st.executeUpdate("ALTER TABLE rewards_claimed ADD COLUMN month INTEGER NOT NULL DEFAULT 0");
                st.executeUpdate("UPDATE rewards_claimed SET month = CAST(substr(date,1,4) AS INTEGER)*100"
                        + " + CAST(substr(date,6,2) AS INTEGER)");
            }
            st.executeUpdate("DELETE FROM rewards_claimed WHERE id NOT IN"
                    + " (SELECT MIN(id) FROM rewards_claimed GROUP BY uuid, month, day)");
            st.executeUpdate("DELETE FROM vip_players WHERE id NOT IN (SELECT MIN(id) FROM vip_players GROUP BY uuid, month)");
            st.executeUpdate("DELETE FROM daily_logins WHERE id NOT IN (SELECT MIN(id) FROM daily_logins GROUP BY uuid, date)");
            st.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS ux_claim ON rewards_claimed (uuid, month, day)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS ix_claim_date ON rewards_claimed (uuid, date)");
            st.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS ux_vip ON vip_players (uuid, month)");
            st.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS ux_login ON daily_logins (uuid, date)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS pending_gifts (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "uuid TEXT NOT NULL, day INTEGER NOT NULL, created_at INTEGER NOT NULL)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS ix_pending ON pending_gifts (uuid)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS reminder_state (uuid TEXT PRIMARY KEY, last_at INTEGER NOT NULL)");
            st.executeUpdate("INSERT INTO meta (key, value) VALUES ('schema_version', '" + SCHEMA_VERSION + "')"
                    + " ON CONFLICT(key) DO UPDATE SET value = excluded.value");
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    private int currentVersion() throws SQLException {
        try (Statement st = conn.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT value FROM meta WHERE key = 'schema_version'")) {
                if (rs.next()) return Integer.parseInt(rs.getString(1));
            } catch (SQLException noMeta) {
                // tabulka meta ještě neexistuje
            }
            try (ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='rewards_claimed'")) {
                return rs.next() ? 1 : 0;
            }
        }
    }

    private boolean hasColumn(String table, String column) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) if (column.equalsIgnoreCase(rs.getString("name"))) return true;
        }
        return false;
    }

    /**
     * Úklid: claimy starší než 62 dní (cooldown potřebuje jen pár dní, měsíc nejvýš 31), loginy 30 dní,
     * prošlé měsíce Passu. Fronta dárků ({@code pending_gifts}) se nemaže nikdy: hráč by přišel o dárek od AT
     * (ručně přes {@code admin pending <hráč> clear}).
     */
    public void cleanup(LocalDate today, YearMonth currentMonth, Consumer<String> log) {
        synchronized (lock) {
            int a = update("DELETE FROM rewards_claimed WHERE date < ?", today.minusDays(62).toString());
            int b = update("DELETE FROM daily_logins WHERE date < ?", today.minusDays(30).toString());
            int c = update("DELETE FROM vip_players WHERE month < ?", PassMath.toInt(currentMonth));
            if (a + b + c > 0)
                log.accept("Úklid DB: claimy " + a + ", loginy " + b + ", prošlé měsíce Passu " + c);
        }
    }

    // ------------------------------------------------------------------ claims

    public Set<Integer> claimedDays(UUID uuid, YearMonth month) {
        synchronized (lock) {
            Set<Integer> out = new HashSet<>();
            query("SELECT day FROM rewards_claimed WHERE uuid = ? AND month = ?", rs -> out.add(rs.getInt(1)),
                    uuid.toString(), PassMath.toInt(month));
            return out;
        }
    }

    /** Datum posledního claimu hráče, který se počítá do cooldownu (zdroj {@code claim}). */
    public LocalDate lastCooldownClaim(UUID uuid) {
        synchronized (lock) {
            LocalDate[] r = {null};
            query("SELECT MAX(date) FROM rewards_claimed WHERE uuid = ? AND source = 'claim'",
                    rs -> {
                        String s = rs.getString(1);
                        if (s != null) r[0] = LocalDate.parse(s);
                    }, uuid.toString());
            return r[0];
        }
    }

    public int lastClaimedDay(UUID uuid, YearMonth month) {
        synchronized (lock) {
            int[] r = {0};
            query("SELECT COALESCE(MAX(day), 0) FROM rewards_claimed WHERE uuid = ? AND month = ?",
                    rs -> r[0] = rs.getInt(1), uuid.toString(), PassMath.toInt(month));
            return r[0];
        }
    }

    /** Atomický zápis claimu: {@code false}, pokud den už v měsíci vyzvednutý je. */
    public boolean tryInsertClaim(UUID uuid, YearMonth month, LocalDate date, int day, Source source) {
        synchronized (lock) {
            return update("INSERT OR IGNORE INTO rewards_claimed (uuid, date, day, source, month) VALUES (?,?,?,?,?)",
                    uuid.toString(), date.toString(), day, source.db(), PassMath.toInt(month)) > 0;
        }
    }

    public int deleteClaim(UUID uuid, YearMonth month, int day) {
        synchronized (lock) {
            return update("DELETE FROM rewards_claimed WHERE uuid = ? AND month = ? AND day = ?",
                    uuid.toString(), PassMath.toInt(month), day);
        }
    }

    // ------------------------------------------------------------------ logins (Premium Pass bonus)

    public boolean hasLoggedIn(UUID uuid, LocalDate date) {
        synchronized (lock) {
            boolean[] r = {false};
            query("SELECT 1 FROM daily_logins WHERE uuid = ? AND date = ?", rs -> r[0] = true, uuid.toString(), date.toString());
            return r[0];
        }
    }

    /** {@code true}, pokud je to první login hráče v daném dni odměn (atomicky). */
    public boolean tryMarkLogin(UUID uuid, LocalDate date) {
        synchronized (lock) {
            return update("INSERT OR IGNORE INTO daily_logins (uuid, date) VALUES (?, ?)", uuid.toString(), date.toString()) > 0;
        }
    }

    // ------------------------------------------------------------------ Login Pass

    public NavigableSet<YearMonth> passMonths(UUID uuid) {
        synchronized (lock) {
            NavigableSet<YearMonth> out = new TreeSet<>();
            query("SELECT month FROM vip_players WHERE uuid = ?", rs -> out.add(PassMath.fromInt(rs.getInt(1))), uuid.toString());
            return out;
        }
    }

    public boolean hasPass(UUID uuid, YearMonth month) {
        synchronized (lock) {
            boolean[] r = {false};
            query("SELECT 1 FROM vip_players WHERE uuid = ? AND month = ?", rs -> r[0] = true, uuid.toString(), PassMath.toInt(month));
            return r[0];
        }
    }

    public void addPassMonths(UUID uuid, List<YearMonth> months) {
        synchronized (lock) {
            for (YearMonth m : months)
                update("INSERT OR IGNORE INTO vip_players (uuid, month) VALUES (?, ?)", uuid.toString(), PassMath.toInt(m));
        }
    }

    public boolean removePassMonth(UUID uuid, YearMonth month) {
        synchronized (lock) {
            return update("DELETE FROM vip_players WHERE uuid = ? AND month = ?", uuid.toString(), PassMath.toInt(month)) > 0;
        }
    }

    // ------------------------------------------------------------------ pending gifts + reminders

    public void addPending(UUID uuid, int day) {
        synchronized (lock) {
            update("INSERT INTO pending_gifts (uuid, day, created_at) VALUES (?,?,?)", uuid.toString(), day, System.currentTimeMillis());
        }
    }

    public List<PendingGift> pending(UUID uuid) {
        synchronized (lock) {
            List<PendingGift> out = new ArrayList<>();
            query("SELECT id, day, created_at FROM pending_gifts WHERE uuid = ? ORDER BY id",
                    rs -> out.add(new PendingGift(rs.getLong(1), rs.getInt(2), rs.getLong(3))), uuid.toString());
            return out;
        }
    }

    public boolean deletePending(long id) {
        synchronized (lock) {
            return update("DELETE FROM pending_gifts WHERE id = ?", id) > 0;
        }
    }

    public long lastReminder(UUID uuid) {
        synchronized (lock) {
            long[] r = {0};
            query("SELECT last_at FROM reminder_state WHERE uuid = ?", rs -> r[0] = rs.getLong(1), uuid.toString());
            return r[0];
        }
    }

    public void setLastReminder(UUID uuid, long epochMillis) {
        synchronized (lock) {
            update("INSERT INTO reminder_state (uuid, last_at) VALUES (?, ?) ON CONFLICT(uuid) DO UPDATE SET last_at = excluded.last_at",
                    uuid.toString(), epochMillis);
        }
    }

    // ------------------------------------------------------------------ plumbing

    private interface RowHandler {
        void accept(ResultSet rs) throws SQLException;
    }

    private void query(String sql, RowHandler h, Object... args) {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) h.accept(rs);
            }
        } catch (SQLException e) {
            throw new DbException("DB dotaz selhal: " + e.getMessage(), e);
        }
    }

    private int update(String sql, Object... args) {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, args);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new DbException("DB zápis selhal: " + e.getMessage(), e);
        }
    }

    private static void bind(PreparedStatement ps, Object[] args) throws SQLException {
        for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
    }

    @Override
    public void close() {
        synchronized (lock) {
            try {
                conn.close();
            } catch (SQLException ignored) {
                // zavíráme při vypnutí
            }
        }
    }
}
