package com.fretka46.fDailyRewards.UI;

import com.fretka46.fDailyRewards.Storage.DailyRewardItem;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

final class Items {
    static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private Items() {}

    static ItemStack toItemStack(DailyRewardItem cfg) {
        Material mat = cfg.material() != null ? cfg.material() : Material.PAPER;
        ItemStack stack = new ItemStack(mat);
        ItemMeta meta = stack.getItemMeta();
        if (cfg.customModelData() > 0) meta.setCustomModelData(cfg.customModelData());
        if (cfg.name() != null && !cfg.name().isEmpty())
            meta.displayName(MINI_MESSAGE.deserialize(cfg.name()).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        if (cfg.lore() != null && !cfg.lore().isEmpty()) meta.lore(splitLore(cfg.lore()));
        if (cfg.tooltipStyle() != null && !cfg.tooltipStyle().isBlank()) {
            NamespacedKey key = NamespacedKey.fromString(cfg.tooltipStyle());
            if (key != null) meta.setTooltipStyle(key);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    static ItemStack appendLore(ItemStack stack, String extra) {
        if (extra == null || extra.isBlank()) return stack;
        ItemMeta meta = stack.getItemMeta();
        List<Component> lore = meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        lore.add(Component.empty());
        lore.addAll(splitLore(extra));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    static ItemStack glint(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        meta.setEnchantmentGlintOverride(true);
        stack.setItemMeta(meta);
        return stack;
    }

    static List<Component> splitLore(String text) {
        List<Component> list = new ArrayList<>();
        for (String line : text.split("\\r?\\n")) {
            list.add(MINI_MESSAGE.deserialize(line).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        }
        return list;
    }
}
