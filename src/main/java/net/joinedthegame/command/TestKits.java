package net.joinedthegame.command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.goal.ProjectGoal;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import org.jspecify.annotations.Nullable;

/**
 * Testing kits (testing jar only): {@code /jtg debug <bot> kit <name>} sets a bot up to test one
 * thing straight away (the nether, brewing, enchanting, fights...), skipping the hours of gearing
 * up first, and starts it where there's something to start.
 */
public final class TestKits {
    private TestKits() {}

    /** What a kit puts on and in the bag, and what it starts. */
    record Kit(String what, @Nullable Item[] armor, @Nullable Item offhand, List<ItemStack> items, int levels,
               BotBrain.@Nullable Focus focus, ProjectGoal.@Nullable Kind project, boolean clearFirst) {}

    private static final Item[] DIAMOND = {Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS};
    private static final Item[] IRON = {Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS};

    private static ItemStack s(Item item, int n) {
        return new ItemStack(item, n);
    }

    private static ItemStack s(Item item) {
        return new ItemStack(item);
    }

    private static ItemStack potion(Holder<Potion> p) {
        return PotionContents.createItemStack(Items.POTION, p);
    }

    private static ItemStack splash(Holder<Potion> p) {
        return PotionContents.createItemStack(Items.SPLASH_POTION, p);
    }

    private static List<ItemStack> list(ItemStack... stacks) {
        return new ArrayList<>(List.of(stacks));
    }

    private static List<ItemStack> potionSet() {
        return list(splash(Potions.STRONG_HEALING), splash(Potions.HEALING), potion(Potions.STRENGTH), potion(Potions.FIRE_RESISTANCE),
                potion(Potions.REGENERATION), potion(Potions.SWIFTNESS), potion(Potions.WATER_BREATHING));
    }

    /** The kits, in the order the command suggests them. */
    static Map<String, Kit> kits() {
        Map<String, Kit> k = new LinkedHashMap<>();
        k.put("nether", new Kit("full diamond, a portal's worth of obsidian and a light: off to the nether", DIAMOND, Items.SHIELD,
                list(s(Items.DIAMOND_SWORD), s(Items.DIAMOND_PICKAXE), s(Items.OBSIDIAN, 12), s(Items.FLINT_AND_STEEL),
                        s(Items.COOKED_BEEF, 32), s(Items.COBBLESTONE, 64)), 0, BotBrain.Focus.NETHER, null, false));
        k.put("brew", new Kit("everything for brewing: starting a brew", IRON, Items.SHIELD,
                list(s(Items.IRON_PICKAXE), s(Items.BLAZE_ROD, 3), s(Items.NETHER_WART, 8), s(Items.GLASS_BOTTLE, 6),
                        s(Items.SUGAR_CANE, 6), s(Items.GLOWSTONE_DUST, 4), s(Items.REDSTONE, 4), s(Items.GUNPOWDER, 4),
                        s(Items.SOUL_SAND, 2), s(Items.MAGMA_CREAM, 2), s(Items.GHAST_TEAR, 1), s(Items.COBBLESTONE, 16),
                        s(Items.BREAD, 16)), 0, null, ProjectGoal.Kind.BREW, false));
        k.put("potions", new Kit("a potion of each kind it uses, for its fights", null, null, potionSet(), 0, null, null, false));
        k.put("enchant", new Kit("the makings of an enchanting table, shelves and 30 levels: starting it", DIAMOND, Items.SHIELD,
                list(s(Items.DIAMOND_PICKAXE), s(Items.DIAMOND, 4), s(Items.OBSIDIAN, 6), s(Items.LEATHER, 16), s(Items.SUGAR_CANE, 48),
                        s(Items.LAPIS_LAZULI, 24), s(Items.BOOK, 4), s(Items.OAK_PLANKS, 64), s(Items.BREAD, 16)),
                30, null, ProjectGoal.Kind.ENCHANT, false));
        k.put("netherite", new Kit("full diamond and gold: the netherite project", DIAMOND, Items.SHIELD,
                list(s(Items.DIAMOND_SWORD), s(Items.DIAMOND_PICKAXE), s(Items.DIAMOND_AXE), s(Items.GOLD_INGOT, 16),
                        s(Items.OBSIDIAN, 12), s(Items.FLINT_AND_STEEL), s(Items.COOKED_BEEF, 32), s(Items.COBBLESTONE, 64)),
                0, null, ProjectGoal.Kind.NETHERITE, false));
        List<ItemStack> trial = list(s(Items.IRON_SWORD), s(Items.IRON_PICKAXE), s(Items.BOW), s(Items.ARROW, 64), s(Items.GOLDEN_APPLE, 4),
                s(Items.COOKED_BEEF, 32), s(Items.COBBLESTONE, 64), s(Items.TORCH, 32));
        trial.addAll(potionSet());
        k.put("trial", new Kit("iron, a bow, golden apples and potions: ready for a trial chamber", IRON, Items.SHIELD, trial, 0, null, null, false));
        k.put("pvp", new Kit("iron, sword, axe, shield, bow, strength and healing: ready to fight", IRON, Items.SHIELD,
                list(s(Items.IRON_SWORD), s(Items.IRON_AXE), s(Items.BOW), s(Items.ARROW, 32), potion(Potions.STRENGTH),
                        splash(Potions.HEALING), splash(Potions.HEALING), s(Items.COOKED_BEEF, 16)), 0, null, null, false));
        k.put("mace", new Kit("iron, a mace and wind charges: smashes", IRON, Items.SHIELD,
                list(s(Items.MACE), s(Items.IRON_SWORD), s(Items.WIND_CHARGE, 16), s(Items.COOKED_BEEF, 16)), 0, null, null, false));
        k.put("builder", new Kit("a house's worth of materials: building", IRON, null,
                list(s(Items.IRON_AXE), s(Items.IRON_PICKAXE), s(Items.OAK_LOG, 64), s(Items.OAK_PLANKS, 64), s(Items.OAK_PLANKS, 64),
                        s(Items.COBBLESTONE, 64), s(Items.COBBLESTONE, 64), s(Items.GLASS, 32), s(Items.OAK_DOOR, 2), s(Items.TORCH, 32),
                        s(Items.BED.red()), s(Items.CHEST, 2), s(Items.CRAFTING_TABLE), s(Items.FURNACE), s(Items.BREAD, 16)),
                0, BotBrain.Focus.HOUSE, null, false));
        k.put("farm", new Kit("seeds, a hoe, water and fences: a farm", IRON, null,
                list(s(Items.IRON_HOE), s(Items.WHEAT_SEEDS, 32), s(Items.WATER_BUCKET), s(Items.OAK_FENCE, 32), s(Items.OAK_FENCE_GATE, 2),
                        s(Items.TORCH, 16), s(Items.BREAD, 16)), 0, null, ProjectGoal.Kind.FARM, false));
        k.put("pen", new Kit("fences, gates and wheat: an animal pen", IRON, null,
                list(s(Items.OAK_FENCE, 48), s(Items.OAK_FENCE_GATE, 2), s(Items.WHEAT, 16), s(Items.CARROT, 16), s(Items.TORCH, 16),
                        s(Items.BREAD, 16)), 0, null, ProjectGoal.Kind.PEN, false));
        k.put("wolf", new Kit("bones: taming a wolf", IRON, null, list(s(Items.BONE, 12), s(Items.BREAD, 16)), 0, null, ProjectGoal.Kind.WOLF, false));
        k.put("bow", new Kit("string and sticks: making a bow", null, null, list(s(Items.STRING, 3), s(Items.STICK, 6), s(Items.FLINT, 4),
                s(Items.FEATHER, 4)), 0, null, ProjectGoal.Kind.BOW, false));
        k.put("scaffold", new Kit("a stack of dirt and one of cobble: pillars and bridges to tidy", null, null,
                list(s(Items.DIRT, 64), s(Items.COBBLESTONE, 64)), 0, null, null, false));
        k.put("bare", new Kit("nothing: a fresh start", null, null, list(), 0, null, null, true));
        return k;
    }

    /** Give the kit; a short line saying what was done, or null if there's no such kit. */
    static @Nullable String give(BotPlayer bot, BotBrain brain, String name) {
        Kit kit = kits().get(name);
        if (kit == null) return null;
        if (kit.clearFirst()) {
            bot.getInventory().clearContent();
            for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND}) {
                bot.setItemSlot(slot, ItemStack.EMPTY);
            }
        }
        if (kit.armor() != null) {
            EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
            for (int i = 0; i < 4; i++) bot.setItemSlot(slots[i], new ItemStack(kit.armor()[i]));
        }
        if (kit.offhand() != null) bot.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(kit.offhand()));
        for (ItemStack st : kit.items()) {
            ItemStack copy = st.copy();
            if (!bot.getInventory().add(copy)) net.joinedthegame.ai.task.CraftTask.give(bot, copy);
        }
        if (kit.levels() > 0) bot.giveExperienceLevels(kit.levels());
        long now = bot.level().getGameTime();
        // Its needs as they are now, with the kit on: still reckoning on the empty-handed bot from
        // a moment ago, it drops a nether focus straight away for want of gear.
        brain.needs().updateNow(bot, brain);
        if (kit.project() != null) brain.projects().force(kit.project(), now);
        else if (kit.focus() != null) brain.setFocus(kit.focus(), now);
        brain.log("testing kit '" + name + "': " + kit.what());
        return kit.what();
    }
}
