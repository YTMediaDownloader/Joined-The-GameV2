package net.joinedthegame;

import java.util.function.Supplier;
import net.joinedthegame.ai.BotMemory;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/** Data the mod saves onto entities. */
public final class JtgAttachments {
    private JtgAttachments() {}

    public static final DeferredRegister<AttachmentType<?>> TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, JoinedTheGame.MODID);

    /** A bot's long-term memory. Saved with the player, kept through death. */
    public static final Supplier<AttachmentType<BotMemory>> MEMORY = TYPES.register("memory",
            () -> AttachmentType.builder(BotMemory::new).serialize(BotMemory.CODEC).copyOnDeath().build());
}
