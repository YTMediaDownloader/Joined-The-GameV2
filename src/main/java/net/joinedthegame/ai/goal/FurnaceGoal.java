package net.joinedthegame.ai.goal;

import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.Senses;
import net.joinedthegame.ai.nav.Navigator;
import net.joinedthegame.ai.skill.BlockBreaker;
import net.joinedthegame.ai.task.SmeltTask;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Clearing out furnaces: anything finished gets taken out, and anything sitting in a cold
 * furnace with no fuel gets taken back. Without this a bot that got called away mid-cook
 * (a fight, nightfall) leaves its food in there forever and goes and makes another furnace.
 */
public final class FurnaceGoal extends Goal {
    private static final int INPUT = 0;
    private static final int FUEL = 1;
    private static final int RESULT = 2;

    private @Nullable BlockPos furnace;
    private int ticks;
    private long lastLook = -1000;

    public FurnaceGoal(BotBrain brain) {
        super(brain);
    }

    @Override
    public int priority(BotPlayer bot) {
        // Look around every couple of seconds (it's a block scan), and remember what we found.
        if (this.brain.now() - this.lastLook >= 40) {
            this.lastLook = this.brain.now();
            this.furnace = find(bot);
        }
        // Above everyday gearing up (max 40), so food doesn't sit in a furnace forever.
        return this.furnace == null ? 0 : 46;
    }

    /** A furnace of ours nearby with something to collect. */
    private @Nullable BlockPos find(BotPlayer bot) {
        GlobalPos here = GlobalPos.of(bot.level().dimension(), bot.blockPosition());
        GlobalPos pending = this.brain.memory().nearest("furnace_pending", here, 48);
        if (pending != null) {
            if (needsEmptying(bot, pending.pos())) return pending.pos();
            this.brain.memory().forget("furnace_pending", pending); // nothing left in it
        }
        return Senses.findBlock(bot, s -> s.is(Blocks.FURNACE), 12, 6, false,
                p -> this.brain.isBlacklisted(p) || !needsEmptying(bot, p));
    }

    private boolean needsEmptying(BotPlayer bot, BlockPos pos) {
        if (!bot.level().isLoaded(pos) || !(bot.level().getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity be)) return false;
        if (!SmeltTask.isOurs(bot, this.brain, pos)) return false;
        if (!be.getItem(RESULT).isEmpty()) return true;
        boolean lit = bot.level().getBlockState(pos).getValue(AbstractFurnaceBlock.LIT);
        return !be.getItem(INPUT).isEmpty() && !lit && be.getItem(FUEL).isEmpty();
    }

    @Override
    public void start(BotPlayer bot) {
        this.ticks = 0;
    }

    @Override
    public boolean tick(BotPlayer bot) {
        BlockPos pos = this.furnace;
        if (pos == null || ++this.ticks > 20 * 45) return false;
        if (!(bot.level().getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity be)) return false;
        if (!BlockBreaker.canUse(bot, pos)) {
            Navigator nav = this.brain.navigator();
            if (!nav.isMoving()) {
                nav.moveToUse(bot, pos);
                if (nav.status() == Navigator.Status.FAILED) {
                    this.brain.blacklist(pos);
                    this.furnace = null;
                    return false;
                }
            }
            return true;
        }
        this.brain.navigator().stop(bot);
        this.brain.controls().lookAt(bot, Vec3.atCenterOf(pos));
        take(bot, be, RESULT);
        boolean lit = bot.level().getBlockState(pos).getValue(AbstractFurnaceBlock.LIT);
        if (!lit && be.getItem(FUEL).isEmpty()) take(bot, be, INPUT);
        bot.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
        this.brain.memory().forget("furnace_pending", GlobalPos.of(bot.level().dimension(), pos));
        this.brain.rememberPlace(bot, "furnace", pos);
        this.brain.log("emptied a furnace at " + pos.toShortString());
        this.furnace = null;
        cooldown(this.brain.now(), 20 * 10);
        return false;
    }

    private static void take(BotPlayer bot, AbstractFurnaceBlockEntity be, int slot) {
        ItemStack stack = be.getItem(slot);
        if (stack.isEmpty()) return;
        ItemStack moving = stack.copy();
        bot.getInventory().add(moving);
        if (!moving.isEmpty()) bot.drop(moving, false, net.minecraft.util.Prediction.SERVER_ONLY); // bag full: whatever didn't fit
        be.setItem(slot, ItemStack.EMPTY);
        if (slot == RESULT) be.awardUsedRecipesAndPopExperience(bot);
        be.setChanged();
    }

    @Override
    public boolean waitingOnPurpose(BotPlayer bot) {
        return true;
    }

    @Override
    public String activity() {
        return "Emptying a furnace";
    }
}
