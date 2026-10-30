package net.joinedthegame.ai.project;

import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.BotMemory;
import net.joinedthegame.ai.Inv;
import net.joinedthegame.ai.Planner;
import net.joinedthegame.ai.Senses;
import net.joinedthegame.ai.nav.Navigator;
import net.joinedthegame.ai.skill.BlockBreaker;
import net.joinedthegame.ai.skill.Enchanter;
import net.joinedthegame.ai.skill.Placer;
import net.joinedthegame.ai.skill.Smither;
import net.joinedthegame.ai.task.CraftTask;
import net.joinedthegame.ai.task.FetchTask;
import net.joinedthegame.ai.task.Task;
import net.joinedthegame.ai.task.Tasks;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.jspecify.annotations.Nullable;

/**
 * Netherite, the late-game upgrade: the most valuable enchanted diamond gear goes to netherite
 * at a smithing table, enchantments and all, rather than being replaced. Each go round works out
 * what's missing and does the next thing:
 * <ul>
 *   <li>a template and an ingot in the bag: a smithing table (one nearby, or make and put one
 *       down) and upgrade;</li>
 *   <li>one template left and more to do: copy it (7 diamonds and a netherrack) before it's used;</li>
 *   <li>no ingot: the planner works it out (4 netherite scrap from smelting ancient debris, and
 *       4 gold ingots, mined and smelted at home if need be);</li>
 *   <li>debris, or a template, only to be had in the nether: a netherite trip (strip-mining at
 *       y 15, and a bastion, a treasure one if it knows one, for the template).</li>
 * </ul>
 * Anything put away at home (templates, ingots, scrap, debris, gold) is fetched first.
 */
public final class NetheriteProject implements Task {
    private @Nullable Task sub;
    private boolean fetched;
    private int trips;
    private int upgrades;
    private int rounds;
    private int pause;
    private @Nullable BlockPos table;
    private String note = "Working on netherite";

    @Override
    public String activity() {
        return this.sub != null ? this.sub.activity() : this.note;
    }

    /** Worth starting: a diamond pickaxe (debris needs one) and some diamond gear to upgrade. */
    public static boolean worthIt(BotPlayer bot) {
        return Inv.pickaxeTier(bot) >= 5 && Smither.pick(bot) != null;
    }

    /** How many pieces of the real loadout are still to do. */
    private static int left(BotPlayer bot) {
        return Smither.candidates(bot).size();
    }

    private static boolean netheriteStuff(ItemStack s) {
        return s.is(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE) || s.is(Items.NETHERITE_INGOT) || s.is(Items.NETHERITE_SCRAP)
                || s.is(Items.ANCIENT_DEBRIS) || s.is(Items.NETHERRACK);
    }

    @Override
    public Status tick(BotPlayer bot, BotBrain brain) {
        if (this.sub != null) {
            Status s = this.sub.tick(bot, brain);
            if (s == Status.RUNNING) return Status.RUNNING;
            this.sub.stop(bot, brain);
            Task done = this.sub;
            this.sub = null;
            if (done instanceof NetherTrip && s == Status.FAILED) {
                brain.log("netherite: the nether trip didn't work out");
                return Status.FAILED;
            }
            // A step that didn't work (nowhere to put the furnace down mid-jump, say): give it a
            // moment before trying again, rather than burning through every try in one tick.
            if (s == Status.FAILED) this.pause = 40;
        }
        if (this.pause > 0) {
            this.pause--;
            return Status.RUNNING;
        }
        if (++this.rounds > 60) return this.upgrades > 0 ? Status.DONE : Status.FAILED;

        Enchanter.Gear gear = Smither.pick(bot);
        if (gear == null) {
            brain.log("netherite: nothing left to upgrade");
            return Status.DONE;
        }
        // What's put away at home first: templates, ingots, scrap, debris, gold.
        if (!this.fetched) {
            this.fetched = true;
            Task fetch = FetchTask.fromStash(bot, brain, s -> netheriteStuff(s) || s.is(Items.GOLD_INGOT), 64, "Getting my netherite things");
            if (fetch != null) {
                this.sub = fetch;
                return Status.RUNNING;
            }
        }
        int templates = Inv.count(bot, Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE);
        int ingots = Inv.count(bot, Items.NETHERITE_INGOT);
        int left = left(bot);

        // Down to the last template with more to do: copy it before it's gone (7 diamonds and
        // a netherrack make two out of one).
        if (templates == 1 && left > 1 && Inv.count(bot, Items.DIAMOND) >= 7 && Inv.count(bot, Items.NETHERRACK) > 0) {
            Planner.Step copy = new Planner(bot).next(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 2);
            if (copy instanceof Planner.Craft craft) {
                this.note = "Copying a smithing template";
                this.sub = new CraftTask(craft);
                return Status.RUNNING;
            }
        }

        if (templates > 0 && ingots > 0) return smith(bot, brain, gear);

        // No ingot: the planner knows the way (scrap and gold), as far as it goes over here.
        if (ingots == 0) {
            Planner.Step step = new Planner(bot).next(Items.NETHERITE_INGOT, 1);
            if (step instanceof Planner.Fail fail) {
                if (!fail.label().endsWith(Planner.NETHER_ONLY)) {
                    brain.log("netherite: " + fail.label());
                    return Status.FAILED;
                }
                return netherTrip(bot, brain, templates, left);
            }
            Task task = Tasks.of(bot, step);
            if (task == null) return Status.FAILED;
            this.note = step.label();
            this.sub = task;
            return Status.RUNNING;
        }
        // An ingot but no template: that's a bastion.
        return netherTrip(bot, brain, templates, left);
    }

    /** Off to the nether for what can only be had there. */
    private Status netherTrip(BotPlayer bot, BotBrain brain, int templates, int left) {
        if (++this.trips > 2) {
            brain.log("netherite: two nether trips this time, that'll do for now");
            return this.upgrades > 0 ? Status.DONE : Status.FAILED;
        }
        int have = Inv.count(bot, Items.ANCIENT_DEBRIS) + Inv.count(bot, Items.NETHERITE_SCRAP);
        // Enough for two pieces a trip (eight debris is a long dig already).
        int debris = Math.max(0, 4 * Math.min(left, 2) - have - Inv.count(bot, Items.NETHERITE_INGOT) * 4);
        boolean needTemplate = templates == 0;
        boolean needNetherrack = !needTemplate && left > 1 && Inv.count(bot, Items.NETHERRACK) == 0;
        brain.log("netherite: going to the nether for " + debris + " ancient debris" + (needTemplate ? " and a template" : ""));
        this.note = "Going to the nether for netherite";
        this.sub = NetherTrip.forNetherite(debris, needTemplate, needNetherrack || needTemplate);
        return Status.RUNNING;
    }

    /** Template, ingot and the gear: a smithing table, and upgrade it. */
    private Status smith(BotPlayer bot, BotBrain brain, Enchanter.Gear gear) {
        if (this.table == null || !bot.level().getBlockState(this.table).is(Blocks.SMITHING_TABLE)) {
            this.table = Senses.findBlock(bot, s -> s.is(Blocks.SMITHING_TABLE), 24, 8, false, brain::isBlacklisted);
            if (this.table == null) {
                if (Inv.count(bot, Items.SMITHING_TABLE) == 0) {
                    Planner.Step make = new Planner(bot).next(Items.SMITHING_TABLE, 1);
                    Task task = make == null ? null : Tasks.of(bot, make);
                    if (task == null) {
                        brain.log("netherite: can't make a smithing table");
                        return Status.FAILED;
                    }
                    this.note = "Making a smithing table";
                    this.sub = task;
                    return Status.RUNNING;
                }
                BlockPos spot = CraftTask.findPlacementSpot(bot);
                if (spot == null || !Placer.place(bot, spot, s -> s.is(Items.SMITHING_TABLE))) return Status.RUNNING;
                this.table = spot;
                brain.rememberPlace(bot, "smithing_table", spot);
            }
        }
        if (!BlockBreaker.canUse(bot, this.table)) {
            this.note = "Going to the smithing table";
            Navigator nav = brain.navigator();
            if (!nav.isMoving()) {
                nav.moveToUse(bot, this.table);
                if (nav.status() == Navigator.Status.FAILED) {
                    brain.blacklist(this.table);
                    this.table = null;
                }
            }
            return Status.RUNNING;
        }
        brain.navigator().stop(bot);
        String what = Planner.name(gear.get(bot).getItem());
        if (!Smither.upgrade(bot, this.table, gear)) {
            brain.log("netherite: the smithing table wouldn't take my " + what);
            return Status.FAILED;
        }
        this.upgrades++;
        brain.log("netherite: upgraded my " + what + " to netherite");
        brain.remember(BotMemory.EventType.MADE, what.replace("diamond", "netherite"), null, 1.0f);
        return Status.RUNNING;
    }

    @Override
    public void stop(BotPlayer bot, BotBrain brain) {
        if (this.sub != null) this.sub.stop(bot, brain);
        this.sub = null;
        brain.navigator().stop(bot);
    }
}
