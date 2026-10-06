package net.joinedthegame.ai;

import net.minecraft.core.Holder;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;

/**
 * What a bot thinks of an enchantment roll. Everyone wants the best (Sharpness on a sword,
 * Protection on armour, Efficiency on a pickaxe, Power on a bow), but each has favourites:
 * miners love Fortune, explorers Feather Falling, the reckless Fire Aspect and Thorns, the
 * careful Unbreaking. A roll with none of what it wants goes through the grindstone.
 */
public final class Enchants {
    private Enchants() {}

    private static String id(Holder<Enchantment> e) {
        return e.unwrapKey().map(k -> k.identifier().getPath()).orElse("");
    }

    /** How much this bot likes one enchantment on this item, per level. */
    static double value(ItemStack item, String ench, Personality p) {
        boolean sword = item.is(ItemTags.SWORDS);
        boolean axe = item.is(ItemTags.AXES);
        boolean pick = item.is(ItemTags.PICKAXES);
        boolean shovel = item.is(ItemTags.SHOVELS);
        boolean bow = item.is(Items.BOW);
        boolean mace = item.is(Items.MACE);
        boolean spear = item.is(ItemTags.SPEARS);
        boolean crossbow = item.is(Items.CROSSBOW);
        boolean armor = Inv.armorSlot(item) != null;
        boolean boots = Inv.armorSlot(item) == EquipmentSlot.FEET;
        return switch (ench) {
            case "mending" -> 10;
            case "unbreaking" -> 3 + p.diligent() * 3;
            case "sharpness" -> sword ? 10 + p.brave() * 2 : spear ? 9 + p.brave() * 2 : axe ? 5 : 0;
            // Mace (26.3: density adds to the smash per block fallen; breach cuts through
            // armour; wind burst throws you back up after a smash, ready for another).
            case "density" -> mace ? 9 + p.reckless() * 3 : 0;
            case "breach" -> mace ? 8 + p.brave() * 2 : 0;
            case "wind_burst" -> mace ? 7 + p.reckless() * 4 + p.explorer() : 0;
            // Spear: lunge pushes you forward with each jab.
            case "lunge" -> spear ? 6 + p.brave() * 3 : 0;
            case "smite", "bane_of_arthropods" -> sword || axe || spear ? 2 : mace ? 4 : 0;
            case "looting" -> sword || spear ? 6 + p.explorer() * 2 : 0;
            case "fire_aspect" -> sword || spear || mace ? 3 + p.reckless() * 5 : 0;
            case "knockback" -> sword || spear ? 1 + p.brave() * 2 : 0;
            case "sweeping_edge" -> sword ? 3 : 0;
            case "efficiency" -> pick || shovel ? 9 : axe ? 6 : 0;
            case "fortune" -> pick ? 7 + p.miner() * 6 : 0;
            case "silk_touch" -> pick ? 4 + p.diligent() * 2 : 0;
            case "protection" -> armor ? 10 : 0;
            case "fire_protection", "blast_protection", "projectile_protection" -> armor ? 4 : 0;
            case "thorns" -> armor ? 1 + p.reckless() * 4 : 0;
            case "feather_falling" -> boots ? 7 + p.explorer() * 4 : 0;
            case "respiration", "aqua_affinity", "depth_strider" -> armor ? 2 + p.explorer() : 0;
            case "power" -> bow ? 10 : 0;
            case "infinity" -> bow ? 8 : 0;
            case "punch" -> bow ? 2 + p.brave() : 0;
            case "flame" -> bow ? 3 + p.reckless() * 3 : 0;
            case "quick_charge" -> crossbow ? 8 : 0;
            case "multishot", "piercing" -> crossbow ? 5 : 0;
            default -> 1;
        };
    }

    /** The one it's really hoping for on this item. */
    static boolean isKey(ItemStack item, String ench) {
        if (item.is(ItemTags.SWORDS)) return ench.equals("sharpness") || ench.equals("looting");
        if (item.is(Items.MACE)) return ench.equals("density") || ench.equals("breach") || ench.equals("wind_burst");
        if (item.is(ItemTags.SPEARS)) return ench.equals("sharpness") || ench.equals("lunge");
        if (item.is(ItemTags.PICKAXES)) return ench.equals("efficiency") || ench.equals("fortune");
        if (item.is(ItemTags.AXES) || item.is(ItemTags.SHOVELS)) return ench.equals("efficiency");
        if (Inv.armorSlot(item) == EquipmentSlot.FEET) return ench.equals("protection") || ench.equals("feather_falling");
        if (Inv.armorSlot(item) != null) return ench.equals("protection");
        if (item.is(Items.BOW)) return ench.equals("power") || ench.equals("infinity");
        if (item.is(Items.CROSSBOW)) return ench.equals("quick_charge");
        return true;
    }

    /** How good this roll is, to this bot. */
    public static double score(ItemStack item, Personality p) {
        double total = 0;
        for (var e : item.getEnchantments().entrySet()) total += value(item, id(e.getKey()), p) * e.getIntValue();
        return total;
    }

    /**
     * The bar a roll has to clear. The score is the authority: a scholar is a bit pickier, but
     * Sharpness IV + Unbreaking III (about 50) clears even the pickiest bar by miles; Knockback I
     * on its own doesn't clear anyone's.
     */
    static double bar(Personality p) {
        return 18 + p.scholar() * 10;
    }

    /** Happy enough with this roll to keep it. */
    public static boolean wanted(ItemStack item, Personality p) {
        return score(item, p) >= bar(p);
    }

    /**
     * Worth taking to the grindstone? All of: the roll is bad enough to be worth removing, the
     * item is worth enchanting again (good gear, not nearly broken), there's the lapis and the
     * levels to reasonably try again, and we haven't already ground too many this time.
     */
    public static boolean shouldGrind(net.joinedthegame.bot.BotPlayer bot, ItemStack item, Personality p, int grinds, int maxGrinds) {
        if (grinds >= maxGrinds || wanted(item, p)) return false;
        boolean ranged = item.is(Items.BOW) || item.is(Items.CROSSBOW) || item.is(Items.MACE);
        if (!ranged && Inv.tier(item.getItem()) < 4) return false;
        if (item.isDamageableItem() && item.getDamageValue() > item.getMaxDamage() / 2) return false;
        if (Inv.count(bot, Items.LAPIS_LAZULI) < 3) return false;
        return bot.experienceLevel >= 15; // most of the way back to 30 already (and the grindstone gives some back)
    }

    /** A readable list: "Sharpness IV, Unbreaking III". */
    public static String describe(ItemStack item) {
        StringBuilder names = new StringBuilder();
        for (var e : item.getEnchantments().entrySet()) {
            if (!names.isEmpty()) names.append(", ");
            names.append(Enchantment.getFullname(e.getKey(), e.getIntValue()).getString());
        }
        return names.toString();
    }
}
