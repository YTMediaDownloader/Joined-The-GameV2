package net.joinedthegame.ai.skill;

import java.util.ArrayList;
import java.util.List;
import net.joinedthegame.ai.Inv;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/**
 * Netherite upgrades at a smithing table, through its real menu: template, diamond gear and a
 * netherite ingot in, netherite gear out, with every enchantment kept (that's the point of
 * upgrading instead of making new).
 */
public final class Smither {
    private Smither() {}

    /**
     * Upgrade order: what you fight with and dig with first, then the armour that takes the most
     * hits. Within that, the better enchanted piece first (it's the one worth keeping).
     */
    private static int importance(ItemStack s) {
        if (s.is(ItemTags.SWORDS)) return 100;
        if (s.is(ItemTags.PICKAXES)) return 95;
        if (s.is(ItemTags.CHEST_ARMOR)) return 90;
        if (s.is(ItemTags.LEG_ARMOR)) return 85;
        if (s.is(ItemTags.HEAD_ARMOR)) return 75;
        if (s.is(ItemTags.FOOT_ARMOR)) return 70;
        if (s.is(ItemTags.AXES)) return 60;
        if (s.is(ItemTags.SPEARS)) return 55;
        if (s.is(ItemTags.SHOVELS)) return 30;
        if (s.is(ItemTags.HOES)) return 5;
        return 0;
    }

    /** Diamond gear that has a netherite version (the template's recipe says which). */
    public static boolean upgradable(ItemStack s) {
        return !s.isEmpty() && Inv.tier(s.getItem()) == 5 && importance(s) >= 55
                && net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().startsWith("diamond_");
    }

    /** The kinds of tool and weapon worth netherite, by name ("sword", "pickaxe"...). */
    private static final String[] TOOL_KINDS = {"sword", "pickaxe", "axe", "spear"};

    /**
     * What's worth upgrading, best first. Deliberately picky: only the bot's real, permanent
     * loadout. Armour it's actually wearing (not spares in the bag), and its best sword,
     * pickaxe, axe and spear (the one it'd actually use). Never a hoe or a shovel, never some
     * odd diamond thing it happens to be carrying. Enchanted pieces always come before plain
     * ones: a Sharpness V sword goes first, not the first diamond thing found.
     */
    public static List<Enchanter.Gear> candidates(BotPlayer bot) {
        List<Enchanter.Gear> out = new ArrayList<>();
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            if (upgradable(bot.getItemBySlot(slot))) out.add(new Enchanter.Gear(slot, -1));
        }
        for (String kind : TOOL_KINDS) {
            // The one it'd actually use: the best of that kind it has, whatever it's made of.
            int best = -1;
            int bestValue = -1;
            for (int i = 0; i < Inv.MAIN_SLOTS; i++) {
                ItemStack st = bot.getInventory().getItem(i);
                if (st.isEmpty() || !kindOf(st).equals(kind)) continue;
                int v = Inv.gearValue(st);
                if (v > bestValue) {
                    bestValue = v;
                    best = i;
                }
            }
            if (best >= 0 && upgradable(bot.getInventory().getItem(best))) out.add(new Enchanter.Gear(null, best));
        }
        out.sort(java.util.Comparator.comparingInt((Enchanter.Gear g) -> g.get(bot).isEnchanted() ? 0 : 1)
                .thenComparingInt(g -> -(importance(g.get(bot)) * 100 + Inv.gearValue(g.get(bot)))));
        return out;
    }

    /** "sword" for a diamond_sword, a netherite_sword, an iron_sword... */
    private static String kindOf(ItemStack s) {
        String path = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
        int u = path.indexOf('_');
        return u < 0 ? "" : path.substring(u + 1);
    }

    /** The piece to upgrade next; null if there's nothing worth it. */
    public static Enchanter.@Nullable Gear pick(BotPlayer bot) {
        List<Enchanter.Gear> all = candidates(bot);
        return all.isEmpty() ? null : all.getFirst();
    }

    /**
     * Upgrade {@code gear} at the table at {@code table}. Uses one template and one ingot from
     * the bag. Worn armour goes back on; gear in the bag goes back in its slot. False (and
     * nothing used up) if it didn't work.
     */
    public static boolean upgrade(BotPlayer bot, BlockPos table, Enchanter.Gear gear) {
        int templateSlot = Inv.find(bot, s -> s.is(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE));
        int ingotSlot = Inv.find(bot, s -> s.is(Items.NETHERITE_INGOT));
        if (templateSlot < 0 || ingotSlot < 0 || !upgradable(gear.get(bot))) return false;
        MenuProvider provider = bot.level().getBlockState(table).getMenuProvider(bot.level(), table);
        if (provider == null) return false;
        bot.openMenu(provider);
        if (!(bot.containerMenu instanceof SmithingMenu menu)) {
            Containers.close(bot);
            return false;
        }
        ItemStack base = gear.get(bot).copy();
        ItemStack template = bot.getInventory().getItem(templateSlot).split(1);
        ItemStack ingot = bot.getInventory().getItem(ingotSlot).split(1);
        gear.set(bot, ItemStack.EMPTY);
        menu.getSlot(SmithingMenu.TEMPLATE_SLOT).set(template);
        menu.getSlot(SmithingMenu.BASE_SLOT).set(base);
        menu.getSlot(SmithingMenu.ADDITIONAL_SLOT).set(ingot);
        ItemStack result = menu.getSlot(SmithingMenu.RESULT_SLOT).getItem().copy();
        boolean ok = !result.isEmpty();
        if (ok) {
            // Taking it is what uses up the inputs (and makes the anvil-ish sound).
            menu.getSlot(SmithingMenu.RESULT_SLOT).onTake(bot, result);
            gear.set(bot, result);
        } else {
            // No recipe after all: everything back where it was.
            menu.getSlot(SmithingMenu.TEMPLATE_SLOT).set(ItemStack.EMPTY);
            menu.getSlot(SmithingMenu.BASE_SLOT).set(ItemStack.EMPTY);
            menu.getSlot(SmithingMenu.ADDITIONAL_SLOT).set(ItemStack.EMPTY);
            gear.set(bot, base);
            bot.getInventory().add(template);
            bot.getInventory().add(ingot);
        }
        Containers.close(bot);
        return ok;
    }
}
