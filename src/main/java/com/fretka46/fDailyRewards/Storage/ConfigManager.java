package com.fretka46.fDailyRewards.Storage;

import com.fretka46.fDailyRewards.Core.RewardCatalog;
import com.fretka46.fDailyRewards.Core.TimeService;
import com.fretka46.fDailyRewards.Utils.Log;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Načtená, po načtení neměnná konfigurace odměn. Při každém reloadu se vytvoří nový snímek,
 * takže menu nikdy nesdílí měnitelné objekty mezi hráči.
 */
public final class ConfigManager {
    private record Snapshot(YearMonth month, Map<Integer, DailyRewardDay> byDay, List<DailyRewardDay> custom,
                            Map<String, DailyRewardItem> templates, RewardCatalog catalog) {}

    private static final String[] TEMPLATE_KEYS = {
            "reward_claimed_item", "reward_claimed_item_vip", "reward_claim_available_item", "reward_claim_available_item_vip"
    };

    private final JavaPlugin plugin;
    private volatile Snapshot snapshot = new Snapshot(null, Map.of(), List.of(), Map.of(), new RewardCatalog(Map.of()));

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public DailyRewardDay getRewardForDay(int day) {
        return snapshot.byDay.get(day);
    }

    public Iterable<DailyRewardDay> allRewards() {
        return snapshot.byDay.values();
    }

    public List<DailyRewardDay> customItems() {
        return snapshot.custom;
    }

    public DailyRewardItem template(String key) {
        return snapshot.templates.get(key);
    }

    public RewardCatalog catalog() {
        return snapshot.catalog;
    }

    public YearMonth loadedMonth() {
        return snapshot.month;
    }

    /** Načte odměny pro měsíc odměn ({@code MM_rewards.yml}, jinak {@code rewardsTemplate.yml}). */
    public void reload(TimeService time) {
        YearMonth month = time.month(time.now());
        if (!new File(plugin.getDataFolder(), "rewardsTemplate.yml").exists())
            plugin.saveResource("rewardsTemplate.yml", false);

        File file = null;
        for (String name : new String[]{month.getMonthValue() + "_rewards.yml", String.format("%02d_rewards.yml", month.getMonthValue())}) {
            File f = new File(plugin.getDataFolder(), name);
            if (f.exists()) {
                file = f;
                break;
            }
        }
        if (file == null) {
            Log.info("Soubor " + month.getMonthValue() + "_rewards.yml neexistuje, používám rewardsTemplate.yml");
            file = new File(plugin.getDataFolder(), "rewardsTemplate.yml");
        }
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        FileConfiguration main = plugin.getConfig();

        Map<Integer, DailyRewardDay> byDay = new TreeMap<>();
        Set<Integer> usedSlots = new HashSet<>();
        ConfigurationSection rewardsSec = cfg.getConfigurationSection("rewards");
        if (rewardsSec != null) {
            for (String key : rewardsSec.getKeys(false)) {
                Integer day = parseDay(key);
                ConfigurationSection sec = rewardsSec.getConfigurationSection(key);
                if (day == null || sec == null) continue;
                if (day < 1 || day > 31) {
                    Log.warning("Den " + day + " v " + file.getName() + " je mimo 1-31, přeskakuji.");
                    continue;
                }
                int slot = sec.getInt("inventorySlot", -1);
                if (slot < 0 || slot >= 54) {
                    Log.warning("Den " + day + ": neplatný inventorySlot " + slot + ", přeskakuji.");
                    continue;
                }
                if (!usedSlots.add(slot)) Log.warning("Den " + day + ": slot " + slot + " už používá jiný den.");
                DailyRewardItem item = readItem(sec.getConfigurationSection("item"));
                if (item == null) {
                    Log.warning("Den " + day + " nemá sekci item, přeskakuji.");
                    continue;
                }
                byDay.put(day, new DailyRewardDay(day, sec.getBoolean("vip", false), item, sec.getStringList("commands"), slot));
            }
        }

        List<DailyRewardDay> custom = new ArrayList<>();
        ConfigurationSection customSec = main.getConfigurationSection("non_functional_items");
        if (customSec != null) {
            for (String key : customSec.getKeys(false)) {
                ConfigurationSection sec = customSec.getConfigurationSection(key);
                if (sec == null) continue;
                ConfigurationSection itemSec = sec.getConfigurationSection("item");
                DailyRewardItem item = readItem(itemSec);
                int slot = sec.getInt("inventorySlot", -1);
                if (item == null || slot < 0 || slot >= 54) {
                    Log.warning("non_functional_items." + key + " je neplatná, přeskakuji.");
                    continue;
                }
                custom.add(new DailyRewardDay(-1, sec.getBoolean("vip", false), item, itemSec.getStringList("commands"), slot));
            }
        }

        Map<String, DailyRewardItem> templates = new LinkedHashMap<>();
        for (String key : TEMPLATE_KEYS) {
            DailyRewardItem t = readItem(main.getConfigurationSection(key));
            if (t == null) {
                Log.warning("Chybí sekce " + key + " v config.yml, používám PAPER.");
                t = new DailyRewardItem(Material.PAPER, 0, "", "", null);
            }
            templates.put(key, t);
        }

        Map<Integer, Boolean> vipByDay = new LinkedHashMap<>();
        byDay.forEach((d, r) -> vipByDay.put(d, r.vip()));
        snapshot = new Snapshot(month, Collections.unmodifiableMap(byDay), List.copyOf(custom),
                Collections.unmodifiableMap(templates), new RewardCatalog(vipByDay));
        Log.info("Načteno " + byDay.size() + " dní odměn z " + file.getName() + ".");
    }

    public static Integer parseDay(String key) {
        try {
            return Integer.parseInt(key);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** Přečte položku z konfigurace; {@code null}, pokud sekce neexistuje. */
    public static DailyRewardItem readItem(ConfigurationSection sec) {
        if (sec == null) return null;
        Material mat = Material.matchMaterial(sec.getString("material", "PAPER"));
        String lore = sec.isList("lore") ? String.join("\n", sec.getStringList("lore")) : sec.getString("lore", "");
        return new DailyRewardItem(
                mat != null ? mat : Material.PAPER,
                sec.getInt("customModelData", sec.getInt("model_data", 0)),
                sec.getString("name", sec.getString("display_name", "")),
                lore,
                sec.getString("tooltipStyle", sec.getString("tooltip_style")));
    }
}
