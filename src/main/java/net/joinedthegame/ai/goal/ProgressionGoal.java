package net.joinedthegame.ai.goal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.Inv;
import net.joinedthegame.ai.Planner;
import net.joinedthegame.ai.Senses;
import net.joinedthegame.ai.Sources;
import net.joinedthegame.ai.task.CraftTask;
import net.joinedthegame.ai.task.MineTask;
import net.joinedthegame.ai.task.SmeltTask;
import net.joinedthegame.ai.task.Task;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.jspecify.annotations.Nullable;

/**
 * The survival grind every player knows: punch trees, wooden pickaxe, stone tools, furnace,
 * copper, iron, diamonds. Each step comes from the {@link Planner}, so the bot works out
 * the sub-steps itself and adapts to what it finds.
 */
public final class ProgressionGoal extends Goal {
    private @Nullable Task task;
    private @Nullable Item working;
    private final Map<Item, Long> failedUntil = new HashMap<>();

    public ProgressionGoal(BotBrain brain) {
        super(brain);
    }

    @Override
    public int priority(BotPlayer bot) {
        return 30 + (int) (this.brain.personality().diligent() * 10);
    }

    /** What the bot wants next, most important first. */
    private List<Want> wants(BotPlayer bot) {
        List<Want> out = new ArrayList<>();
        int pick = Inv.pickaxeTier(bot);
        int sword = Inv.swordTier(bot);
        int axe = Inv.axeTier(bot);
        boolean fighter = this.brain.personality().brave() > 0.6;

        if (pick < 1) out.add(new Want(Items.WOODEN_PICKAXE, 1));
        if (fighter && sword < 2) out.add(new Want(Items.STONE_SWORD, 1));
        if (pick < 2) out.add(new Want(Items.STONE_PICKAXE, 1));
        if (sword < 2) out.add(new Want(Items.STONE_SWORD, 1));
        if (axe < 2) out.add(new Want(Items.STONE_AXE, 1));
        if (Inv.count(bot, Items.FURNACE) == 0 && this.brain.now() % 2 == 0
                && Senses.findBlock(bot, s -> s.is(Blocks.FURNACE), 16, 6, false, p -> false) == null
                && this.brain.memory().nearest("furnace", net.minecraft.core.GlobalPos.of(bot.level().dimension(), bot.blockPosition()), 64) == null) {
            out.add(new Want(Items.FURNACE, 1));
        }
        // Coal before the big ores: torches, and fuel for every bit of smelting after this.
        if (pick >= 2 && Inv.count(bot, Items.COAL) + Inv.count(bot, Items.CHARCOAL) < 8) out.add(new Want(Items.COAL, 16));

        // After stone, straight to iron. Copper gear isn't worth the detour; copper only gets
        // mined when a recipe actually calls for it.

        // Iron is the goal, but if we've got the diamonds for a piece already, make it diamond.
        if (pick < 4) out.add(new Want(ironOrDiamond(bot, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE, 3), 1));
        // First iron after the pickaxe goes into a shield.
        if (!bot.getOffhandItem().is(Items.SHIELD) && Inv.count(bot, Items.SHIELD) == 0) out.add(new Want(Items.SHIELD, 1));
        if (sword < 4) out.add(new Want(ironOrDiamond(bot, Items.IRON_SWORD, Items.DIAMOND_SWORD, 2), 1));
        addArmorIronOrDiamond(bot, out);
        if (axe < 4) out.add(new Want(ironOrDiamond(bot, Items.IRON_AXE, Items.DIAMOND_AXE, 3), 1));

        if (pick < 5) out.add(new Want(Items.DIAMOND_PICKAXE, 1));
        if (sword < 5) out.add(new Want(Items.DIAMOND_SWORD, 1));
        addArmor(bot, out, 5, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_HELMET, Items.DIAMOND_BOOTS);

        // Ranged: a bow (or crossbow) once we've the string for it, and arrows to go with it.
        boolean hasBow = Inv.count(bot, Items.BOW) + Inv.count(bot, Items.CROSSBOW) > 0;
        if (!hasBow && Inv.count(bot, Items.STRING) >= 3) out.add(new Want(Items.BOW, 1));
        if (hasBow && Inv.count(bot, s -> s.is(net.minecraft.tags.ItemTags.ARROWS)) < 16 && Inv.count(bot, Items.FEATHER) > 0) {
            out.add(new Want(Items.ARROW, Inv.count(bot, Items.ARROW) + 4 * Math.min(4, Inv.count(bot, Items.FEATHER))));
        }

        // A mace: a heavy core (from an ominous vault, the rarest thing in a trial chamber) and a
        // breeze rod. Once we've the core, make one (the rod's on us, or at home).
        if (Inv.count(bot, Items.MACE) == 0 && (Inv.count(bot, Items.HEAVY_CORE) > 0
                || net.joinedthegame.ai.task.FetchTask.inStash(bot, this.brain, s -> s.is(Items.HEAVY_CORE)) > 0)) out.add(new Want(Items.MACE, 1));

        // A bundle, once we've the string and leather, to keep the odds and ends in.
        if (Inv.count(bot, s -> s.is(net.minecraft.tags.ItemTags.BUNDLES)) == 0
                && Inv.count(bot, Items.STRING) >= 1 && Inv.count(bot, Items.LEATHER) >= 1) out.add(new Want(Items.BUNDLE, 1));

        // Nothing left to make: keep busy stockpiling.
        if (Inv.count(bot, s -> s.is(ItemTags.LOGS)) < 16) out.add(new Want(Items.OAK_LOG, 16));
        if (Inv.count(bot, Items.COAL) < 16 && pick >= 1) out.add(new Want(Items.COAL, 16));
        if (Inv.count(bot, Items.RAW_IRON) + Inv.count(bot, Items.IRON_INGOT) < 16 && pick >= 2) out.add(new Want(Items.RAW_IRON, 16));
        return out;
    }

    private static void addArmor(BotPlayer bot, List<Want> out, int tier, Item chest, Item legs, Item head, Item feet) {
        if (Inv.armorTier(bot, EquipmentSlot.CHEST) < tier && Inv.count(bot, chest) == 0) out.add(new Want(chest, 1));
        if (Inv.armorTier(bot, EquipmentSlot.LEGS) < tier && Inv.count(bot, legs) == 0) out.add(new Want(legs, 1));
        if (Inv.armorTier(bot, EquipmentSlot.HEAD) < tier && Inv.count(bot, head) == 0) out.add(new Want(head, 1));
        if (Inv.armorTier(bot, EquipmentSlot.FEET) < tier && Inv.count(bot, feet) == 0) out.add(new Want(feet, 1));
    }

    /** The diamond version if we're carrying the diamonds for it, otherwise iron. */
    private static Item ironOrDiamond(BotPlayer bot, Item iron, Item diamond, int diamondsNeeded) {
        if (Inv.count(bot, diamond) > 0) return diamond; // made one already, just wear/hold it
        return Inv.count(bot, Items.DIAMOND) >= diamondsNeeded ? diamond : iron;
    }

    /** Iron armour, or diamond for any piece we have the diamonds for. */
    private static void addArmorIronOrDiamond(BotPlayer bot, List<Want> out) {
        Object[][] pieces = {
                {EquipmentSlot.CHEST, Items.IRON_CHESTPLATE, Items.DIAMOND_CHESTPLATE, 8},
                {EquipmentSlot.LEGS, Items.IRON_LEGGINGS, Items.DIAMOND_LEGGINGS, 7},
                {EquipmentSlot.HEAD, Items.IRON_HELMET, Items.DIAMOND_HELMET, 5},
                {EquipmentSlot.FEET, Items.IRON_BOOTS, Items.DIAMOND_BOOTS, 4}};
        for (Object[] p : pieces) {
            if (Inv.armorTier(bot, (EquipmentSlot) p[0]) >= 4) continue;
            Item iron = (Item) p[1];
            Item diamond = (Item) p[2];
            if (Inv.count(bot, iron) > 0 || Inv.count(bot, diamond) > 0) continue; // got one to put on
            out.add(new Want(ironOrDiamond(bot, iron, diamond, (Integer) p[3]), 1));
        }
    }

    private record Want(Item item, int count) {}

    @Override
    public boolean tick(BotPlayer bot) {
        // Back from a long interruption (a night's sleep, a trip): the old plan's stale.
        if (this.task != null && this.stoppedAt >= 0 && this.brain.now() - this.stoppedAt > 20 * 60) this.task = null;
        this.stoppedAt = -1;
        if (this.task == null) {
            this.task = plan(bot);
            if (this.task == null) {
                cooldown(this.brain.now(), 20 * 20);
                return false;
            }
        }
        Task.Status status = this.task.tick(bot, this.brain);
        if (status == Task.Status.RUNNING) return true;
        this.task.stop(bot, this.brain);
        this.brain.log("task '" + this.task.activity() + "' " + status);
        // Milestones: the first iron pickaxe, the first diamond sword...
        if (status == Task.Status.DONE && this.working != null && Inv.count(bot, this.working) > 0
                && !(this.task instanceof net.joinedthegame.ai.task.FetchFromHome)) {
            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(this.working).toString();
            if (!this.brain.memory().ever(net.joinedthegame.ai.BotMemory.EventType.MADE, id)) this.brain.remember(net.joinedthegame.ai.BotMemory.EventType.MADE, id, null, 0.7f);
        }
        if (status == Task.Status.FAILED && this.working != null) {
            this.failedUntil.put(this.working, this.brain.now() + 20 * 90);
        }
        // Job done: step back and let the brain decide what's next (other goals waited for this).
        boolean wasMining = this.task instanceof MineTask;
        this.task = null;
        return !wasMining;
    }

    private @Nullable Task plan(BotPlayer bot) {
        Planner planner = new Planner(bot);
        long now = this.brain.now();
        for (Want want : wants(bot)) {
            Long until = this.failedUntil.get(want.item());
            if (until != null && until > now) continue;
            // The heavy core lives in the secret stash (it's treasure): dig it out to make the mace.
            if (want.item() == Items.MACE && Inv.count(bot, Items.HEAVY_CORE) == 0) {
                Task core = net.joinedthegame.ai.task.FetchTask.fromStash(bot, this.brain, s -> s.is(Items.HEAVY_CORE), 1, "Getting my heavy core");
                if (core != null) {
                    this.working = want.item();
                    return core;
                }
                continue;
            }
            Planner.Step step = planner.next(want.item(), want.count());
            if (step == null) continue;
            // Night and not geared for it: only work that keeps us underground (no trees).
            if (nightOutsideUnsafe(bot) && step instanceof Planner.Mine mine && !mine.source().isUnderground()) continue;
            if (step instanceof Planner.Fail fail) {
                this.brain.log("want " + Planner.name(want.item()) + ": " + fail.label());
                this.failedUntil.put(want.item(), now + 20 * 120);
                continue;
            }
            this.brain.log("want " + Planner.name(want.item()) + " -> " + step.label());
            this.working = want.item();
            Task task = toTask(bot, step);
            if (task != null) return task;
        }
        return null;
    }

    /** Dark out and not in full iron with a shield: the surface isn't safe. */
    private static boolean nightOutsideUnsafe(BotPlayer bot) {
        return bot.level().isDarkOutside() && !ShelterGoal.nightReady(bot);
    }

    private static @Nullable Task toTask(BotPlayer bot, Planner.Step step) {
        return switch (step) {
            case Planner.Mine mine -> {
                MineTask task = new MineTask(bot, mine.source(), Math.min(mine.count(), mine.source() == Sources.LOGS ? 8 : 12), mine.label());
                // At night, stay down here: ignore ore that's out under the open sky.
                // At night (dug in): stay down here. Nothing out under the sky, and nothing above our
                // head either: digging up through the lid lets in whatever's waiting outside.
                if (nightOutsideUnsafe(bot)) {
                    int feetY = bot.getBlockY();
                    task.avoiding(p -> bot.level().isDarkOutside() && (bot.level().canSeeSky(p.above()) || p.getY() > feetY + 1));
                }
                yield task;
            }
            case Planner.Craft craft -> new CraftTask(craft);
            case Planner.Smelt smelt -> new SmeltTask(bot, smelt.input(), smelt.count(), smelt.output(), smelt.label());
            case Planner.Fetch fetch -> new net.joinedthegame.ai.task.FetchFromHome(fetch);
            case Planner.Fail fail -> null;
        };
    }

    /** In the middle of a mining job: the bot's dead set on it (see BotBrain#choose). */
    public boolean isMining() {
        return this.task instanceof MineTask;
    }

    @Override
    public void stop(BotPlayer bot) {
        super.stop(bot);
        // Keep the task: a quick interruption (picking up what just dropped, a bite to eat)
        // shouldn't throw the plan away. It used to re-plan from scratch every time, with a new
        // tunnel direction and the ore it was after forgotten.
        if (this.task != null) this.task.stop(bot, this.brain);
        this.stoppedAt = this.brain.now();
    }

    private long stoppedAt = -1;

    @Override
    public boolean waitingOnPurpose(BotPlayer bot) {
        return this.task instanceof net.joinedthegame.ai.task.SmeltTask; // waiting on the furnace
    }

    @Override
    public String activity() {
        Task task = this.task;
        return task == null ? "Thinking" : task.activity();
    }
}
