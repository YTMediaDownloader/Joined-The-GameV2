package net.joinedthegame.ai.task;

import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.Inv;
import net.joinedthegame.ai.Planner;
import net.joinedthegame.ai.Senses;
import net.joinedthegame.ai.nav.Navigator;
import net.joinedthegame.ai.skill.BlockBreaker;
import net.joinedthegame.ai.skill.Placer;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Smelt items in a real furnace: find one (or put one down), load the input and fuel, wait
 * around while it cooks, and take the results out. Uses whatever furnace is around,
 * including yours, and takes your coal if it needs to. Very multiplayer.
 */
public final class SmeltTask implements Task {
    private static final int INPUT = 0;
    private static final int FUEL = 1;
    private static final int RESULT = 2;

    private final Item input;
    private final Item output;
    private final int target;
    private final String label;
    private @Nullable BlockPos furnace;
    private int ticks;
    private int idleTicks;
    private final int timeout;

    public SmeltTask(BotPlayer bot, Item input, int count, Item output, String label) {
        this.input = input;
        this.output = output;
        this.target = Inv.count(bot, output) + count;
        this.label = label;
        this.timeout = 20 * 60 + count * 220;
    }

    @Override
    public String activity() {
        return this.label;
    }

    private @org.jspecify.annotations.Nullable Task makeFurnace;
    private boolean triedMaking;

    @Override
    public Status tick(BotPlayer bot, BotBrain brain) {
        if (Inv.count(bot, this.output) >= this.target) return Status.DONE;
        if (++this.ticks > this.timeout) return Status.FAILED;

        AbstractFurnaceBlockEntity be = furnaceAt(bot, this.furnace);
        if (be == null) {
            // Our own furnaces first (one might still have our stuff in it), then any nearby.
            this.furnace = CraftTask.remembered(bot, brain, "furnace", Blocks.FURNACE);
            if (this.furnace == null || this.furnace.distSqr(bot.blockPosition()) > 64 * 64) {
                BlockPos near = Senses.findBlock(bot, s -> s.is(Blocks.FURNACE), 24, 8, false, brain::isBlacklisted);
                if (near != null) this.furnace = near;
            }
            if (this.furnace == null) {
                if (Inv.count(bot, Items.FURNACE) == 0) {
                    // No furnace we can use: make one from the cobble we're carrying.
                    if (this.makeFurnace == null && !this.triedMaking) {
                        this.triedMaking = true;
                        this.makeFurnace = Tasks.of(bot, new net.joinedthegame.ai.Planner(bot).next(Items.FURNACE, 1));
                    }
                    if (this.makeFurnace != null) {
                        Status s = this.makeFurnace.tick(bot, brain);
                        if (s == Status.RUNNING) return Status.RUNNING;
                        this.makeFurnace.stop(bot, brain);
                        this.makeFurnace = null;
                        if (s == Status.DONE && Inv.count(bot, Items.FURNACE) == 0) this.triedMaking = false; // a step on the way
                        return Status.RUNNING;
                    }
                    brain.log("smelt: no furnace around and none to place");
                    return Status.FAILED;
                }
                BlockPos spot = CraftTask.findPlacementSpot(bot);
                if (spot == null || !Placer.place(bot, spot, s -> s.is(Items.FURNACE))) {
                    brain.log("smelt: couldn't place furnace at " + spot);
                    return Status.FAILED;
                }
                this.furnace = spot;
            }
            return Status.RUNNING;
        }

        if (!BlockBreaker.canUse(bot, this.furnace)) {
            Navigator nav = brain.navigator();
            if (!nav.isMoving()) {
                nav.moveToUse(bot, this.furnace);
                if (nav.status() == Navigator.Status.FAILED) {
                    nav.stop(bot);
                    brain.blacklist(this.furnace);
                    this.furnace = null;
                }
            }
            return Status.RUNNING;
        }
        brain.navigator().stop(bot);
        brain.rememberPlace(bot, "furnace", this.furnace);
        brain.controls().lookAt(bot, Vec3.atCenterOf(this.furnace));

        // Take out anything that's done.
        ItemStack result = be.getItem(RESULT);
        if (!result.isEmpty()) {
            CraftTask.give(bot, result.copy());
            be.setItem(RESULT, ItemStack.EMPTY);
            be.awardUsedRecipesAndPopExperience(bot);
            bot.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
            return Status.RUNNING;
        }

        ItemStack in = be.getItem(INPUT);
        int loaded = in.is(this.input) ? in.getCount() : 0;
        if (in.isEmpty() || in.is(this.input)) {
            // Load the whole lot, like a player: not just what this one recipe needs. We carry
            // on once we've got enough, and come back for the rest when it's done.
            int toLoad = Math.min(this.input.getDefaultMaxStackSize() - loaded, Inv.count(bot, this.input));
            if (toLoad > 0) {
                int moved = Inv.remove(bot, s -> s.is(this.input), toLoad);
                be.setItem(INPUT, new ItemStack(this.input, loaded + moved));
                loaded += moved;
                bot.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
            }
        } else if (isOurs(bot, brain, this.furnace)
                && !bot.level().getBlockState(this.furnace).getValue(net.minecraft.world.level.block.AbstractFurnaceBlock.LIT)) {
            // Our own leftovers from last time, gone cold: take them back out and use the furnace.
            // (Still cooking? Leave it be and use another one: otherwise two jobs keep swapping
            // each other's stuff in and out of the same furnace.)
            CraftTask.give(bot, in.copy());
            be.setItem(INPUT, ItemStack.EMPTY);
            bot.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
            return Status.RUNNING;
        } else {
            // Someone else's stuff is cooking. Use a different furnace.
            brain.blacklist(this.furnace);
            this.furnace = null;
            return Status.RUNNING;
        }

        boolean lit = bot.level().getBlockState(this.furnace).getValue(net.minecraft.world.level.block.AbstractFurnaceBlock.LIT);
        if (loaded > 0 && be.getItem(FUEL).isEmpty()) {
            loadFuel(bot, be, loaded);
            if (be.getItem(FUEL).isEmpty() && !lit) {
                // Nothing to burn: take it all back rather than leave it in a cold furnace.
                CraftTask.give(bot, be.getItem(INPUT).copy());
                be.setItem(INPUT, ItemStack.EMPTY);
                brain.log("smelt: no fuel");
                return Inv.count(bot, this.output) > 0 ? Status.DONE : Status.FAILED;
            }
        }
        if (loaded == 0 && !lit) {
            if (++this.idleTicks > 60) return Inv.count(bot, this.output) > 0 ? Status.DONE : Status.FAILED;
        } else {
            this.idleTicks = 0;
        }
        return Status.RUNNING;
    }

    private void loadFuel(BotPlayer bot, AbstractFurnaceBlockEntity be, int items) {
        // Prefer coal; fall back to wood. Never burn the thing we're smelting.
        int slot = -1;
        double bestValue = 0;
        for (int i = 0; i < Inv.MAIN_SLOTS; i++) {
            ItemStack stack = bot.getInventory().getItem(i);
            if (stack.isEmpty() || stack.is(this.input)) continue;
            double value = Planner.fuelPerItem(stack);
            if (value > bestValue) {
                bestValue = value;
                slot = i;
            }
        }
        if (slot < 0) return;
        ItemStack fuel = bot.getInventory().getItem(slot);
        int count = Math.min(fuel.getCount(), (int) Math.ceil(items / bestValue));
        be.setItem(FUEL, fuel.split(count));
        bot.getInventory().setChanged();
    }

    private static @Nullable AbstractFurnaceBlockEntity furnaceAt(BotPlayer bot, @Nullable BlockPos pos) {
        if (pos == null) return null;
        BlockEntity be = bot.level().getBlockEntity(pos);
        return be instanceof AbstractFurnaceBlockEntity furnace && bot.level().getBlockState(pos).is(Blocks.FURNACE) ? furnace : null;
    }

    @Override
    public void stop(BotPlayer bot, BotBrain brain) {
        brain.navigator().stop(bot);
        AbstractFurnaceBlockEntity be = furnaceAt(bot, this.furnace);
        if (be != null && (!be.getItem(INPUT).isEmpty() || !be.getItem(RESULT).isEmpty())) {
            brain.rememberPlace(bot, "furnace_pending", this.furnace);
        }
    }

    /**
     * A furnace we've used before (so whatever's in it is probably ours), or one nobody's
     * around to claim: furnaces from before bots had memory still count. If a real player is
     * nearby it's probably theirs, so hands off.
     */
    public static boolean isOurs(BotPlayer bot, BotBrain brain, BlockPos pos) {
        boolean playerNear = bot.level().players().stream().anyMatch(p -> !(p instanceof BotPlayer)
                && p.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos)) < 16 * 16);
        if (!playerNear) return true;
        var here = net.minecraft.core.GlobalPos.of(bot.level().dimension(), pos);
        for (String kind : new String[]{"furnace", "furnace_pending"}) {
            var list = brain.memory().places().get(kind);
            if (list != null && list.stream().anyMatch(p -> p.pos().equals(here))) return true;
        }
        return false;
    }
}
