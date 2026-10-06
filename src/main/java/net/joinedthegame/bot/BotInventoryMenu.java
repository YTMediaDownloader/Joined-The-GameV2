package net.joinedthegame.bot;

import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Peek into a bot's inventory and hand it gear (or take it back). Uses a vanilla 5-row
 * chest screen, so nothing is needed on the client:
 * <pre>
 *   rows 1-3  main inventory
 *   row 4     hotbar
 *   row 5     helmet, chestplate, leggings, boots, offhand, then 4 locked slots
 * </pre>
 */
public final class BotInventoryMenu extends ChestMenu {
    private static final int SIZE = 45;
    private static final int FIRST_LOCKED = 41;

    private final BotPlayer bot;

    private BotInventoryMenu(int id, Inventory viewer, BotPlayer bot) {
        super(MenuType.GENERIC_9x5, id, viewer, new View(bot), 5);
        this.bot = bot;
        // Row 5: armor slots only take armor; the filler slots take nothing.
        for (int i = 36; i < SIZE; i++) {
            Slot old = this.slots.get(i);
            int chestSlot = i;
            Slot replacement = new Slot(old.container, old.getContainerSlot(), old.x, old.y) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return View.accepts(chestSlot, stack);
                }

                @Override
                public boolean mayPickup(Player player) {
                    return chestSlot < FIRST_LOCKED;
                }

                @Override
                public int getMaxStackSize() {
                    return chestSlot < 40 ? 1 : super.getMaxStackSize();
                }
            };
            replacement.index = i;
            this.slots.set(i, replacement);
        }
    }

    public static SimpleMenuProvider provider(BotPlayer bot) {
        return new SimpleMenuProvider((id, inv, player) -> new BotInventoryMenu(id, inv, bot),
                Component.translatable("container.joinedthegame.bot_inventory", bot.getName()));
    }

    @Override
    public boolean stillValid(Player player) {
        return this.bot.isAlive() && !this.bot.isRemoved();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (index >= FIRST_LOCKED && index < SIZE) return ItemStack.EMPTY;
        return super.quickMoveStack(player, index);
    }

    /** Maps the 45 chest slots onto the bot's inventory. */
    private static final class View implements Container {
        private static final ItemStack FILLER = new ItemStack(Items.STAINED_GLASS_PANE.gray());
        private final BotPlayer bot;

        View(BotPlayer bot) {
            this.bot = bot;
            FILLER.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal(" "));
        }

        /** Chest slot to bot inventory slot, or -1 for a locked slot. */
        private static int map(int slot) {
            if (slot < 27) return slot + 9;
            if (slot < 36) return slot - 27;
            return switch (slot) {
                case 36 -> 39; // helmet
                case 37 -> 38; // chestplate
                case 38 -> 37; // leggings
                case 39 -> 36; // boots
                case 40 -> 40; // offhand
                default -> -1;
            };
        }

        @Override
        public int getContainerSize() {
            return SIZE;
        }

        @Override
        public boolean isEmpty() {
            return this.bot.getInventory().isEmpty();
        }

        @Override
        public ItemStack getItem(int slot) {
            int mapped = map(slot);
            return mapped < 0 ? FILLER : this.bot.getInventory().getItem(mapped);
        }

        @Override
        public ItemStack removeItem(int slot, int count) {
            int mapped = map(slot);
            return mapped < 0 ? ItemStack.EMPTY : this.bot.getInventory().removeItem(mapped, count);
        }

        @Override
        public ItemStack removeItemNoUpdate(int slot) {
            int mapped = map(slot);
            return mapped < 0 ? ItemStack.EMPTY : this.bot.getInventory().removeItemNoUpdate(mapped);
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            int mapped = map(slot);
            if (mapped >= 0) this.bot.getInventory().setItem(mapped, stack);
        }

        @Override
        public boolean canPlaceItem(int slot, ItemStack stack) {
            return accepts(slot, stack);
        }

        static boolean accepts(int slot, ItemStack stack) {
            if (map(slot) < 0) return false;
            net.minecraft.world.entity.EquipmentSlot armor = switch (slot) {
                case 36 -> net.minecraft.world.entity.EquipmentSlot.HEAD;
                case 37 -> net.minecraft.world.entity.EquipmentSlot.CHEST;
                case 38 -> net.minecraft.world.entity.EquipmentSlot.LEGS;
                case 39 -> net.minecraft.world.entity.EquipmentSlot.FEET;
                default -> null;
            };
            return armor == null || net.joinedthegame.ai.Inv.armorSlot(stack) == armor;
        }

        @Override
        public void setChanged() {
            this.bot.getInventory().setChanged();
        }

        @Override
        public boolean stillValid(Player player) {
            return this.bot.isAlive();
        }

        @Override
        public void clearContent() {}
    }
}
