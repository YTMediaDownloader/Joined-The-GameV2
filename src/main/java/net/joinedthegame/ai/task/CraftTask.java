package net.joinedthegame.ai.task;

import java.util.ArrayList;
import java.util.List;
import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.Inv;
import net.joinedthegame.ai.Planner;
import net.joinedthegame.ai.Senses;
import net.joinedthegame.ai.nav.Navigator;
import net.joinedthegame.ai.nav.WorldView;
import net.joinedthegame.ai.skill.BlockBreaker;
import net.joinedthegame.ai.skill.Placer;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Craft something. Small recipes are made in the inventory grid; 3x3 ones need a crafting
 * table, so the bot walks to one nearby or puts its own down.
 */
public final class CraftTask implements Task {
    private final Planner.Craft step;
    private @Nullable BlockPos table;
    private int ticks;
    private int useTicks;

    public CraftTask(Planner.Craft step) {
        this.step = step;
    }

    @Override
    public String activity() {
        return this.step.label();
    }

    @Override
    public Status tick(BotPlayer bot, BotBrain brain) {
        if (++this.ticks > 20 * 60) return Status.FAILED;
        if (!this.step.option().needsTable()) return craftAfterDelay(bot, brain, null);

        if (this.table == null || !bot.level().getBlockState(this.table).is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE)) {
            this.table = Senses.findBlock(bot, s -> s.is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE), 24, 8, false, brain::isBlacklisted);
            if (this.table == null) this.table = remembered(bot, brain, "crafting_table", net.minecraft.world.level.block.Blocks.CRAFTING_TABLE);
            if (this.table == null) {
                if (Inv.count(bot, Items.CRAFTING_TABLE) == 0) {
                    // No table we can reach: make one from planks, like anyone would.
                    Planner.Step make = new Planner(bot, false).next(Items.CRAFTING_TABLE, 1);
                    if (!(make instanceof Planner.Craft craft) || !craftNow(bot, craft)) return Status.FAILED;
                }
                BlockPos spot = findPlacementSpot(bot);
                if (spot == null || !Placer.place(bot, spot, s -> s.is(Items.CRAFTING_TABLE))) {
                    brain.log("craft: couldn't place table at " + spot);
                    return Status.FAILED;
                }
                this.table = spot;
            }
        }
        if (!BlockBreaker.canUse(bot, this.table)) {
            Navigator nav = brain.navigator();
            if (!nav.isMoving()) {
                nav.moveToUse(bot, this.table);
                if (nav.status() == Navigator.Status.FAILED) {
                    nav.stop(bot);
                    // Can't get there: forget that table, and put our own down if we have one.
                    brain.blacklist(this.table);
                    if (Inv.count(bot, Items.CRAFTING_TABLE) > 0) this.table = null;
                    else return Status.FAILED;
                }
            }
            return Status.RUNNING;
        }
        brain.navigator().stop(bot);
        brain.rememberPlace(bot, "crafting_table", this.table);
        return craftAfterDelay(bot, brain, this.table);
    }

    private Status craftAfterDelay(BotPlayer bot, BotBrain brain, @Nullable BlockPos at) {
        if (at != null) brain.controls().lookAt(bot, Vec3.atCenterOf(at));
        if (++this.useTicks == 1 && at != null) bot.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
        if (this.useTicks < 12) return Status.RUNNING;
        for (int i = 0; i < this.step.times(); i++) {
            if (!craftOnce(bot)) {
                if (i == 0) brain.log("craft: recipe didn't match the chosen items " + this.step.grid());
                return i > 0 ? Status.DONE : Status.FAILED;
            }
        }
        return Status.DONE;
    }

    private boolean craftOnce(BotPlayer bot) {
        List<@Nullable Item> grid = this.step.grid();
        for (Item item : grid) {
            if (item != null && Inv.count(bot, item) < count(grid, item)) return false;
        }
        List<ItemStack> inputs = new ArrayList<>(grid.size());
        for (Item item : grid) inputs.add(item == null ? ItemStack.EMPTY : new ItemStack(item));
        int width = this.step.option().width();
        int height = this.step.option().height();
        while (inputs.size() < width * height) inputs.add(ItemStack.EMPTY);
        CraftingInput input = CraftingInput.of(width, height, inputs);
        CraftingRecipe recipe = this.step.option().holder().value();
        if (!recipe.matches(input, bot.level())) return false;
        ItemStack result = recipe.assemble(input);
        if (result.isEmpty()) return false;
        var remaining = recipe.getRemainingItems(input);

        for (Item item : grid) if (item != null) Inv.remove(bot, s -> s.is(item), 1);
        result.onCraftedBy(bot, result.getCount());
        give(bot, result);
        for (ItemStack rest : remaining) if (!rest.isEmpty()) give(bot, rest.copy());
        bot.getInventory().setChanged();
        return true;
    }

    /**
     * A table or furnace we've used before, not too far off, that's still there. Forgets ones
     * that have gone.
     */
    static @Nullable BlockPos remembered(BotPlayer bot, BotBrain brain, String kind, net.minecraft.world.level.block.Block block) {
        var here = net.minecraft.core.GlobalPos.of(bot.level().dimension(), bot.blockPosition());
        var pos = brain.memory().nearest(kind, here, 64);
        if (pos == null || brain.isBlacklisted(pos.pos()) || !bot.level().isLoaded(pos.pos())) return null;
        if (!bot.level().getBlockState(pos.pos()).is(block)) {
            brain.memory().forget(kind, pos);
            return null;
        }
        return pos.pos();
    }

    /** Craft immediately (inventory-grid recipes only). Returns true if anything was made. */
    public static boolean craftNow(BotPlayer bot, Planner.Craft step) {
        if (step.option().needsTable()) return false;
        CraftTask task = new CraftTask(step);
        boolean any = false;
        for (int i = 0; i < step.times(); i++) {
            if (!task.craftOnce(bot)) break;
            any = true;
        }
        return any;
    }

    private static int count(List<@Nullable Item> grid, Item item) {
        int n = 0;
        for (Item i : grid) if (i == item) n++;
        return n;
    }

    static void give(BotPlayer bot, ItemStack stack) {
        if (!bot.getInventory().add(stack) && !stack.isEmpty()) bot.drop(stack, false, net.minecraft.util.Prediction.SERVER_ONLY);
    }

    /**
     * An open spot near the bot with solid ground, for putting down a table or furnace. Skips
     * any cell the bot's own body pokes into (players are 0.6 wide, so a bot standing off-centre
     * overlaps its neighbour), since the game refuses to place a block there.
     */
    static @Nullable BlockPos findPlacementSpot(BotPlayer bot) {
        WorldView world = new WorldView(bot.level());
        BlockPos feet = Navigator.feet(bot);
        net.minecraft.world.phys.AABB body = bot.getBoundingBox().inflate(0.05);
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos spot = feet.offset(dx, dy, dz);
                    if (!world.state(spot).canBeReplaced() || !world.state(spot).getFluidState().isEmpty()) continue;
                    if (!world.solidFloor(spot.below())) continue;
                    if (body.intersects(new net.minecraft.world.phys.AABB(spot))) continue;
                    double dist = bot.getEyePosition().distanceToSqr(Vec3.atCenterOf(spot));
                    if (dist > 4.0 * 4.0) continue;
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = spot;
                    }
                }
            }
        }
        if (best != null) return best;
        // Cramped (a burrow, a 1-wide tunnel): stick it on any wall in reach instead. Blocks
        // don't need floor under them, just something next to them to click on.
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    BlockPos spot = feet.offset(dx, dy, dz);
                    if (!world.state(spot).canBeReplaced() || !world.state(spot).getFluidState().isEmpty()) continue;
                    if (body.intersects(new net.minecraft.world.phys.AABB(spot))) continue;
                    // Don't plug the way out at our own head height.
                    if (spot.equals(feet.above()) || spot.equals(feet.above(2))) continue;
                    if (Placer.findSupport(bot, spot) == null) continue;
                    double dist = bot.getEyePosition().distanceToSqr(Vec3.atCenterOf(spot));
                    if (dist > 4.0 * 4.0) continue;
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = spot;
                    }
                }
            }
        }
        return best;
    }
}
