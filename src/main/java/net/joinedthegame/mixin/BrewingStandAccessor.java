package net.joinedthegame.mixin;

import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * What the brewing stand keeps to itself: ticks left on the brew under way, and fuel left
 * inside (one blaze powder is twenty brews, so the fuel slot can be empty with plenty to go).
 */
@Mixin(BrewingStandBlockEntity.class)
public interface BrewingStandAccessor {
    @Accessor("brewTime")
    int joinedthegame$brewTime();

    @Accessor("fuel")
    int joinedthegame$fuel();
}
