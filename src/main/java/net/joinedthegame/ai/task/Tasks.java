package net.joinedthegame.ai.task;

import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.Inv;
import net.joinedthegame.ai.Planner;
import net.joinedthegame.ai.Sources;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/** Small helpers for stringing tasks together. */
public final class Tasks {
    private Tasks() {}

    /** Run these one after another; fails as soon as one does. */
    /**
     * {@code first}, then {@code then} whatever came of the first (a fetch from the chests that
     * found nothing, or couldn't reach one, shouldn't sink the job it was for).
     */
    public static Task anyway(Task first, Task then) {
        return new Task() {
            private boolean firstDone;

            @Override
            public Status tick(BotPlayer bot, BotBrain brain) {
                if (!this.firstDone) {
                    if (first.tick(bot, brain) == Status.RUNNING) return Status.RUNNING;
                    first.stop(bot, brain);
                    this.firstDone = true;
                }
                return then.tick(bot, brain);
            }

            @Override
            public void stop(BotPlayer bot, BotBrain brain) {
                (this.firstDone ? then : first).stop(bot, brain);
            }

            @Override
            public String activity() {
                return this.firstDone ? then.activity() : first.activity();
            }
        };
    }

    /** A task worked out when it's reached, not before (null from the supplier: nothing to do). */
    public static Task lazy(java.util.function.Supplier<@org.jspecify.annotations.Nullable Task> make) {
        return new Task() {
            private @org.jspecify.annotations.Nullable Task task;
            private boolean made;

            @Override
            public Status tick(BotPlayer bot, BotBrain brain) {
                if (!this.made) {
                    this.made = true;
                    this.task = make.get();
                }
                return this.task == null ? Status.DONE : this.task.tick(bot, brain);
            }

            @Override
            public void stop(BotPlayer bot, BotBrain brain) {
                if (this.task != null) this.task.stop(bot, brain);
            }

            @Override
            public String activity() {
                return this.task != null ? this.task.activity() : "Getting my things";
            }
        };
    }

    public static Task sequence(Task... tasks) {
        return new Task() {
            private int i;

            @Override
            public Status tick(BotPlayer bot, BotBrain brain) {
                while (this.i < tasks.length) {
                    Status s = tasks[this.i].tick(bot, brain);
                    if (s == Status.RUNNING) return Status.RUNNING;
                    if (s == Status.FAILED) return Status.FAILED;
                    this.i++;
                }
                return Status.DONE;
            }

            @Override
            public void stop(BotPlayer bot, BotBrain brain) {
                if (this.i < tasks.length) tasks[this.i].stop(bot, brain);
            }

            @Override
            public String activity() {
                return this.i < tasks.length ? tasks[this.i].activity() : "Moving";
            }
        };
    }

    /** The task that carries out one planner step. Null for a step that can't be done. */
    public static @Nullable Task of(BotPlayer bot, Planner.@Nullable Step step) {
        return switch (step) {
            case null -> null;
            case Planner.Mine mine -> new MineTask(bot, mine.source(), Math.min(mine.source().haul(mine.count()), mine.source() == Sources.LOGS ? 12 : 32), mine.label());
            case Planner.Craft craft -> new CraftTask(craft);
            case Planner.Smelt smelt -> new SmeltTask(bot, smelt.input(), smelt.count(), smelt.output(), smelt.label());
            case Planner.Fetch fetch -> new net.joinedthegame.ai.task.FetchFromHome(fetch);
            case Planner.Fail fail -> null;
        };
    }

    /**
     * Something we need {@code count} of, any variety that passes {@code have} (any planks, any
     * stone...). {@code make} is the one to go and get when short.
     */
    public record Need(String label, java.util.function.Predicate<ItemStack> have, int count, java.util.function.Function<BotPlayer, Item> make,
                       boolean optional) {
        public static Need of(String label, java.util.function.Predicate<ItemStack> have, int count, java.util.function.Function<BotPlayer, Item> make) {
            return new Need(label, have, count, make, false);
        }

        /** Nice to have; the build goes ahead without it if it can't be got. */
        public static Need nice(String label, java.util.function.Predicate<ItemStack> have, int count, java.util.function.Function<BotPlayer, Item> make) {
            return new Need(label, have, count, make, true);
        }

        public int missing(BotPlayer bot) {
            return Math.max(0, this.count - Inv.count(bot, this.have));
        }
    }

    /** The next step toward a need, or null if it's met. Sets {@code failed[0]} if it's impossible. */
    public static @Nullable Task toward(BotPlayer bot, Need need, boolean[] failed) {
        int missing = need.missing(bot);
        if (missing <= 0) return null;
        Item item = need.make().apply(bot);
        Planner.Step step = new Planner(bot).next(item, Inv.count(bot, item) + missing);
        if (step == null) return null;
        Task task = of(bot, step);
        if (task == null) failed[0] = true;
        return task;
    }
}
