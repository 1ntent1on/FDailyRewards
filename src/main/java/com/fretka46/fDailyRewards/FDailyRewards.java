package com.fretka46.fDailyRewards;

import com.fretka46.fDailyRewards.Commands.CommandTree;
import com.fretka46.fDailyRewards.Core.PassService;
import com.fretka46.fDailyRewards.Core.RewardService;
import com.fretka46.fDailyRewards.Core.TimeService;
import com.fretka46.fDailyRewards.Listeners.LeftListener;
import com.fretka46.fDailyRewards.Listeners.LoginListener;
import com.fretka46.fDailyRewards.Storage.ConfigManager;
import com.fretka46.fDailyRewards.Storage.Database;
import com.fretka46.fDailyRewards.UI.MenuListener;
import com.fretka46.fDailyRewards.Utils.Log;
import com.fretka46.fDailyRewards.Utils.PapiExpansion;
import com.fretka46.fDailyRewards.Utils.ReminderService;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneId;
import java.time.YearMonth;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class FDailyRewards extends JavaPlugin {
    private static FDailyRewards instance;

    private Database database;
    private ConfigManager configManager;
    private ReminderService reminders;
    private volatile TimeService time;
    private volatile PassService pass;
    private volatile RewardService rewards;
    private PapiExpansion papi;
    private final AtomicBoolean monthReloadScheduled = new AtomicBoolean();

    public static FDailyRewards get() {
        return instance;
    }

    public Database database() { return database; }
    public ConfigManager configManager() { return configManager; }
    public ReminderService reminders() { return reminders; }
    public TimeService time() { return time; }
    public RewardService rewards() { return rewards; }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        try {
            database = Database.openFile(Path.of(getDataFolder().getPath(), "database.db"), Log::info);
        } catch (Database.DbException ex) {
            Log.severe("Databázi nelze otevřít, plugin se vypíná: " + ex.getMessage(), ex);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        configManager = new ConfigManager(this);
        reminders = new ReminderService(this);
        buildServices();
        database.cleanup(time.today(), time.month(time.now()), Log::info);

        var manager = getServer().getPluginManager();
        manager.registerEvents(new MenuListener(), this);
        manager.registerEvents(new LoginListener(), this);
        manager.registerEvents(new LeftListener(), this);

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,
                commands -> commands.registrar().register(CommandTree.buildRoot()));

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            papi = new PapiExpansion(this);
            papi.register();
        }
        reminders.start();
    }

    /** Sestaví čas a služby z aktuálního configu (při startu a reloadu). Databáze zůstává. */
    private void buildServices() {
        var cfg = getConfig();
        ZoneId zone;
        try {
            zone = ZoneId.of(cfg.getString("timezone", "Europe/Prague"));
        } catch (RuntimeException ex) {
            Log.warning("Neplatné timezone, používám Europe/Prague");
            zone = ZoneId.of("Europe/Prague");
        }
        time = new TimeService(Clock.system(zone), zone,
                TimeService.parseResetTime(cfg.getString("day_reset_time", "04:15"), Log::warning));
        pass = new PassService(database, time, this::hasLegacyPassPermission, () -> getConfig().getInt("pass_late_purchase_days", 0));
        rewards = new RewardService(database, time, () -> configManager.catalog(), pass,
                () -> getConfig().getInt("placeholder_cache_millis", 5000), this::onRewardMonth);
        configManager.reload(time);
    }

    /** Přechodová podpora staré permission Login Passu (prázdný klíč = vypnuto). */
    private boolean hasLegacyPassPermission(UUID uuid) {
        String node = getConfig().getString("legacy_pass_permission", "");
        if (node == null || node.isBlank()) return false;
        Player p = Bukkit.getPlayer(uuid);
        return p != null && p.hasPermission(node);
    }

    public void reloadAll() {
        reloadConfig();
        buildServices();
        reminders.start();
    }

    /**
     * Volá {@link RewardService} před každým vyhodnocením. Po přechodu do dalšího měsíce odměn načte soubor
     * MM_rewards.yml (restart ve 04:00 běží ještě před resetem dne, takže start načte starý měsíc). Na hlavním
     * vlákně hned; z jiného vlákna (PlaceholderAPI) se reload naplánuje a do té doby platí stávající odměny.
     */
    private void onRewardMonth(YearMonth month) {
        if (month.equals(configManager.loadedMonth())) return;
        if (Bukkit.isPrimaryThread()) reloadMonthIfChanged();
        else if (isEnabled() && monthReloadScheduled.compareAndSet(false, true))
            Bukkit.getScheduler().runTask(this, () -> {
                monthReloadScheduled.set(false);
                reloadMonthIfChanged();
            });
    }

    private void reloadMonthIfChanged() {
        if (time.month(time.now()).equals(configManager.loadedMonth())) return;
        configManager.reload(time);
        rewards.invalidateAll();
    }

    @Override
    public void onDisable() {
        if (reminders != null) reminders.stop();
        if (database != null) database.close();
        instance = null;
    }
}
