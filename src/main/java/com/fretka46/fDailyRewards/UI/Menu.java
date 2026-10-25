package com.fretka46.fDailyRewards.UI;

import com.fretka46.fDailyRewards.Core.ClaimState;
import com.fretka46.fDailyRewards.Core.ClaimState.DayState;
import com.fretka46.fDailyRewards.FDailyRewards;
import com.fretka46.fDailyRewards.Storage.ConfigManager;
import com.fretka46.fDailyRewards.Storage.DailyRewardDay;
import com.fretka46.fDailyRewards.Storage.DailyRewardItem;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

/**
 * Menu denních odměn, vždy sestavené pro jednoho hráče. Stav každého dne přebírá z {@link ClaimState}
 * (jediný zdroj pravdy); sdílené objekty z configu se nikdy nemění, všechny úpravy jdou do kopií.
 */
public class Menu implements InventoryHolder {
    private static final int SIZE = 54;
    public static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private final Inventory inventory;
    private final Map<Integer, Integer> slotToDay = new HashMap<>();
    private final Map<Integer, DailyRewardDay> slotToCustomItem = new HashMap<>();
    private final Player viewer;

    public Menu(Player viewer) {
        this.viewer = viewer;
        String title = FDailyRewards.get().getConfig().getString("menu_title", "Daily Rewards");
        this.inventory = Bukkit.createInventory(this, SIZE, MINI_MESSAGE.deserialize(title));
        render();
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    public static void openFor(Player player) {
        new Menu(player).open();
    }

    public void open() {
        viewer.openInventory(inventory);
    }

    public Integer getDayAt(int rawSlot) {
        return slotToDay.get(rawSlot);
    }

    public DailyRewardDay getCustomItemAt(int rawSlot) {
        return slotToCustomItem.get(rawSlot);
    }

    /** Znovu vykreslí obsah podle aktuálního stavu (po vyzvednutí, bez zavření a znovuotevření okna). */
    public void render() {
        inventory.clear();
        slotToDay.clear();
        slotToCustomItem.clear();

        FDailyRewards plugin = FDailyRewards.get();
        ConfigManager cm = plugin.configManager();
        FileConfiguration config = plugin.getConfig();
        ClaimState state = plugin.rewards().evaluateFresh(viewer.getUniqueId());

        for (DailyRewardDay reward : cm.allRewards()) {
            DayState ds = state.days().getOrDefault(reward.day(), DayState.FUTURE);
            inventory.setItem(reward.inventorySlot(), build(reward, ds, config, cm));
            slotToDay.put(reward.inventorySlot(), reward.day());
        }

        for (DailyRewardDay custom : cm.customItems()) {
            inventory.setItem(custom.inventorySlot(), Items.toItemStack(custom.item()));
            slotToCustomItem.put(custom.inventorySlot(), custom);
        }

        if (config.getBoolean("fill_empty_slots", true)) {
            ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
            ItemMeta fillerMeta = filler.getItemMeta();
            fillerMeta.setHideTooltip(true);
            filler.setItemMeta(fillerMeta);
            for (int slot = 0; slot < SIZE; slot++) {
                if (inventory.getItem(slot) == null) inventory.setItem(slot, filler);
            }
        }
    }

    private static ItemStack build(DailyRewardDay reward, DayState ds, FileConfiguration config, ConfigManager cm) {
        DailyRewardItem base = reward.item();
        String vipSuffix = reward.vip() ? "_vip" : "";
        return switch (ds) {
            // celý item ze šablony (lore "už máš vyzvednutou", tooltip), z odměny jen název (jako původní plugin)
            case CLAIMED -> Items.toItemStack(cm.template("reward_claimed_item" + vipSuffix).withName(base.name()));
            case LOCKED_VIP -> Items.appendLore(Items.toItemStack(base), config.getString("reward_vip_locked_loreline"));
            case SKIPPED_VIP_NOW_CLAIMABLE -> Items.glint(Items.appendLore(
                    Items.toItemStack(base.withLook(cm.template("reward_claim_available_item_vip"))),
                    config.getString("reward_vip_can_skip")));
            case CLAIMABLE -> Items.glint(Items.appendLore(
                    Items.toItemStack(base.withLook(cm.template("reward_claim_available_item" + vipSuffix))),
                    config.getString("reward_claim_available_loreline", "ERR: reward_claim_available_loreline")));
            case WAITING_TOMORROW -> Items.appendLore(Items.toItemStack(base), config.getString("reward_claim_available_tommorow_loreline"));
            case WAITING_2_DAYS -> Items.appendLore(Items.toItemStack(base), config.getString("reward_claim_available_2_days_loreline"));
            case FUTURE -> Items.toItemStack(base);
        };
    }
}
