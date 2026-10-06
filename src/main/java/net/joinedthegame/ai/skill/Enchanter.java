package net.joinedthegame.ai.skill;

import java.util.ArrayList;
import java.util.List;
import net.joinedthegame.ai.Inv;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EnchantingTableBlock;
import org.jspecify.annotations.Nullable;

/**
 * Using an enchanting table through its real menu: item in, lapis in, pick an option, take
 * it out again. Costs levels and lapis like it does for anyone.
 */
public final class Enchanter {
    private Enchanter() {}

    /** Where a piece of gear is: worn (armour) or in the bag. */
    public record Gear(@Nullable EquipmentSlot worn, int slot) {
        public ItemStack get(BotPlayer bot) {
            return this.worn != null ? bot.getItemBySlot(this.worn) : bot.getInventory().getItem(this.slot);
        }

        void set(BotPlayer bot, ItemStack stack) {
            if (this.worn != null) bot.setItemSlot(this.worn, stack);
            else bot.getInventory().setItem(this.slot, stack);
        }
    }

    /** Most important first: what you fight with and what keeps you alive, then tools. */
    private static int importance(ItemStack s) {
        if (s.is(ItemTags.SWORDS)) return 100;
        if (s.is(Items.MACE)) return 98;
        if (s.is(net.minecraft.tags.ItemTags.SPEARS)) return 92;
        if (s.is(ItemTags.CHEST_ARMOR)) return 95;
        if (s.is(ItemTags.LEG_ARMOR)) return 90;
        if (s.is(ItemTags.PICKAXES)) return 85;
        if (s.is(ItemTags.HEAD_ARMOR)) return 80;
        if (s.is(ItemTags.FOOT_ARMOR)) return 75;
        if (s.is(Items.BOW) || s.is(Items.CROSSBOW)) return 70;
        if (s.is(ItemTags.AXES)) return 60;
        if (s.is(ItemTags.SHOVELS)) return 40;
        return 0;
    }

    /**
     * The gear most worth enchanting: not enchanted yet, iron or better (enchanting stone
     * tools is a waste of levels), most important first.
     */
    public static @Nullable Gear pick(BotPlayer bot) {
        List<Gear> all = new ArrayList<>();
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            all.add(new Gear(slot, -1));
        }
        for (int i = 0; i < Inv.MAIN_SLOTS; i++) all.add(new Gear(null, i));
        Gear best = null;
        int bestScore = 0;
        for (Gear g : all) {
            ItemStack s = g.get(bot);
            if (s.isEmpty() || s.isEnchanted() || !s.isEnchantable()) continue;
            boolean ranged = s.is(Items.BOW) || s.is(Items.CROSSBOW) || s.is(Items.MACE);
            if (!ranged && Inv.tier(s.getItem()) < 4) continue;
            int score = importance(s) * 10 + Inv.tier(s.getItem());
            if (score > bestScore) {
                bestScore = score;
                best = g;
            }
        }
        return best;
    }

    /** Bookshelves around a table that actually count (nothing in between). */
    public static int shelves(Level level, BlockPos table) {
        int n = 0;
        for (BlockPos offset : EnchantingTableBlock.BOOKSHELF_OFFSETS) {
            if (EnchantingTableBlock.isValidBookShelf(level, table, offset)) n++;
        }
        return n;
    }

    public enum Result { ENCHANTED, TOO_EXPENSIVE, FAILED }

    /**
     * Take the enchantments back off at a grindstone (the XP comes back out as orbs, like it
     * does for anyone). Returns false if it didn't work.
     */
    public static boolean grind(BotPlayer bot, BlockPos grindstone, Gear gear) {
        MenuProvider provider = bot.level().getBlockState(grindstone).getMenuProvider(bot.level(), grindstone);
        if (provider == null) return false;
        bot.openMenu(provider);
        if (!(bot.containerMenu instanceof net.minecraft.world.inventory.GrindstoneMenu menu)) {
            Containers.close(bot);
            return false;
        }
        ItemStack item = gear.get(bot).copy();
        gear.set(bot, ItemStack.EMPTY);
        menu.getSlot(net.minecraft.world.inventory.GrindstoneMenu.INPUT_SLOT).set(item);
        ItemStack result = menu.getSlot(net.minecraft.world.inventory.GrindstoneMenu.RESULT_SLOT).getItem().copy();
        boolean ok = !result.isEmpty();
        if (ok) {
            menu.getSlot(net.minecraft.world.inventory.GrindstoneMenu.RESULT_SLOT).onTake(bot, result);
            gear.set(bot, result);
        } else {
            menu.getSlot(net.minecraft.world.inventory.GrindstoneMenu.INPUT_SLOT).set(ItemStack.EMPTY);
            gear.set(bot, item);
        }
        Containers.close(bot);
        return ok;
    }

    /** Find a piece of gear again by what it is and what's on it. */
    public static @Nullable Gear find(BotPlayer bot, net.minecraft.world.item.Item item, String enchants) {
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack s = bot.getItemBySlot(slot);
            if (s.is(item) && net.joinedthegame.ai.Enchants.describe(s).equals(enchants)) return new Gear(slot, -1);
        }
        for (int i = 0; i < Inv.MAIN_SLOTS; i++) {
            ItemStack s = bot.getInventory().getItem(i);
            if (s.is(item) && net.joinedthegame.ai.Enchants.describe(s).equals(enchants)) return new Gear(null, i);
        }
        return null;
    }

    /**
     * Enchant {@code gear} at the table. Takes the best (bottom) option if we've the levels for
     * it; with {@code settle} false, a cheaper option isn't good enough and we leave it.
     */
    public static Result enchant(BotPlayer bot, BlockPos table, Gear gear, boolean settle) {
        MenuProvider provider = bot.level().getBlockState(table).getMenuProvider(bot.level(), table);
        if (provider == null) return Result.FAILED;
        bot.openMenu(provider);
        if (!(bot.containerMenu instanceof EnchantmentMenu menu)) {
            Containers.close(bot);
            return Result.FAILED;
        }
        ItemStack item = gear.get(bot).copy();
        gear.set(bot, ItemStack.EMPTY);
        int lapisSlot = Inv.find(bot, s -> s.is(Items.LAPIS_LAZULI));
        ItemStack lapis = lapisSlot < 0 ? ItemStack.EMPTY : bot.getInventory().getItem(lapisSlot).split(3);
        menu.getSlot(1).set(lapis);
        menu.getSlot(0).set(item); // works out the costs

        int choice = -1;
        for (int i = 2; i >= 0; i--) {
            int cost = menu.costs[i];
            if (cost <= 0) continue;
            if (bot.experienceLevel >= cost && lapis.getCount() >= i + 1) {
                choice = i;
                break;
            }
            if (!settle) break; // the best one's out of reach: wait for more levels
        }
        boolean done = choice >= 0 && menu.clickMenuButton(bot, choice);
        if (done) menu.broadcastChanges();

        // Everything back where it came from.
        ItemStack out = menu.getSlot(0).getItem().copy();
        menu.getSlot(0).set(ItemStack.EMPTY);
        gear.set(bot, out);
        ItemStack left = menu.getSlot(1).getItem().copy();
        menu.getSlot(1).set(ItemStack.EMPTY);
        if (!left.isEmpty()) bot.getInventory().add(left);
        Containers.close(bot);
        if (done) return Result.ENCHANTED;
        return choice < 0 && menu.costs[2] > 0 ? Result.TOO_EXPENSIVE : Result.FAILED;
    }
}
