package net.joinedthegame.client;

import java.util.List;
import net.joinedthegame.network.JtgNetwork;
import net.joinedthegame.network.RosterEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.PlayerSkin;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.jspecify.annotations.Nullable;

/**
 * The "Manage AI Players" screen (K by default). One row per bot on the roster, with the
 * buttons that make sense for its state: invite, or teleport/inventory/kick/ban, or unban.
 */
public class BotListScreen extends Screen {
    private static final int ROW = 24;
    private static final int PANEL = 340;

    private static JtgNetwork.@Nullable Roster latest;

    private int scroll;
    private int refreshIn;
    private String typedName = "";
    private @Nullable EditBox nameBox;

    public BotListScreen() {
        super(Component.translatable("joinedthegame.screen.title"));
    }

    public static void onRoster(JtgNetwork.Roster roster) {
        latest = roster;
        if (Minecraft.getInstance().gui.screen() instanceof BotListScreen screen) screen.refresh();
    }

    private void refresh() {
        if (this.nameBox != null) this.typedName = this.nameBox.getValue();
        this.rebuildWidgets();
    }

    private static void send(String action, String name) {
        ClientPacketDistributor.sendToServer(new JtgNetwork.BotAction(action, name));
    }

    private int left() {
        return (this.width - PANEL) / 2;
    }

    private int listTop() {
        return 36;
    }

    private int visibleRows() {
        return Math.max(1, (this.height - listTop() - 56) / ROW);
    }

    @Override
    protected void init() {
        JtgNetwork.Roster roster = latest;
        int left = left();
        boolean canManage = roster != null && roster.canManage();

        if (roster != null && canManage) {
            List<RosterEntry> entries = roster.entries();
            this.scroll = Math.max(0, Math.min(this.scroll, entries.size() - visibleRows()));
            int y = listTop();
            for (int i = this.scroll; i < entries.size() && i < this.scroll + visibleRows(); i++) {
                addRowButtons(entries.get(i), left, y);
                y += ROW;
            }
        }

        int bottom = this.height - 46;
        if (canManage) {
            this.nameBox = new EditBox(this.font, left, bottom, 140, 20, Component.translatable("joinedthegame.screen.name"));
            this.nameBox.setMaxLength(16);
            this.nameBox.setHint(Component.translatable("joinedthegame.screen.name"));
            this.nameBox.setValue(this.typedName);
            addRenderableWidget(this.nameBox);
            addRenderableWidget(Button.builder(Component.translatable("joinedthegame.screen.invite"), b -> {
                String name = this.nameBox.getValue().trim();
                if (!name.isEmpty()) {
                    send("invite", name);
                    this.nameBox.setValue("");
                    this.typedName = "";
                }
            }).bounds(left + 144, bottom, 60, 20).build());
        } else {
            this.nameBox = null;
        }
        addRenderableWidget(Button.builder(Component.translatable("joinedthegame.screen.guide"), b -> send("guide", ""))
                .bounds(left + PANEL - 130, bottom, 60, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> this.onClose())
                .bounds(left + PANEL - 66, bottom, 66, 20).build());
    }

    private void addRowButtons(RosterEntry entry, int left, int y) {
        int x = left + PANEL;
        String name = entry.name();
        switch (entry.state()) {
            case RosterEntry.ONLINE -> {
                x = button(x, y, 34, "ban", name, "joinedthegame.screen.ban");
                x = button(x, y, 36, "kick", name, "joinedthegame.screen.kick");
                x = button(x, y, 34, "inventory", name, "joinedthegame.screen.inv");
                button(x, y, 26, "tp", name, "joinedthegame.screen.tp");
            }
            case RosterEntry.AVAILABLE -> {
                x = button(x, y, 34, "ban", name, "joinedthegame.screen.ban");
                button(x, y, 48, "invite", name, "joinedthegame.screen.invite");
            }
            default -> button(x, y, 48, "unban", name, "joinedthegame.screen.unban");
        }
    }

    /** Adds a button ending at {@code right}; returns the x where the next one should end. */
    private int button(int right, int y, int width, String action, String name, String label) {
        int x = right - width;
        addRenderableWidget(Button.builder(Component.translatable(label), b -> send(action, name)).bounds(x, y + 2, width, 18).build());
        return x - 2;
    }

    @Override
    public void tick() {
        if (--this.refreshIn <= 0) {
            this.refreshIn = 20;
            ClientPacketDistributor.sendToServer(new JtgNetwork.RequestRoster());
        }
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        JtgNetwork.Roster roster = latest;
        if (roster == null) return false;
        int before = this.scroll;
        this.scroll = Math.max(0, Math.min(this.scroll - (int) Math.signum(scrollY), roster.entries().size() - visibleRows()));
        if (before != this.scroll) refresh();
        return true;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        int left = left();
        graphics.centeredText(this.font, this.title, this.width / 2, 14, 0xFFFFFFFF);

        JtgNetwork.Roster roster = latest;
        if (roster == null) {
            graphics.centeredText(this.font, Component.translatable("joinedthegame.screen.loading"), this.width / 2, listTop() + 10, 0xFFAAAAAA);
            return;
        }
        if (!roster.canManage()) {
            graphics.centeredText(this.font, Component.translatable("joinedthegame.screen.no_permission"), this.width / 2, listTop() + 10, 0xFFFF6666);
        }

        int online = 0;
        for (RosterEntry e : roster.entries()) if (e.state() == RosterEntry.ONLINE) online++;
        graphics.text(this.font, Component.translatable("joinedthegame.screen.count", online), left, listTop() - 12, 0xFFAAAAAA);

        if (!roster.canManage()) return;
        List<RosterEntry> entries = roster.entries();
        int y = listTop();
        for (int i = this.scroll; i < entries.size() && i < this.scroll + visibleRows(); i++) {
            RosterEntry entry = entries.get(i);
            if (i % 2 == 0) graphics.fill(left - 4, y, left + PANEL + 4, y + ROW - 2, 0x22FFFFFF);
            PlayerFaceExtractor.extractRenderState(graphics, skin(entry), left, y + 3, 16);
            int nameColor = switch (entry.state()) {
                case RosterEntry.ONLINE -> 0xFF55FF55;
                case RosterEntry.BANNED -> 0xFFFF5555;
                default -> 0xFFDDDDDD;
            };
            graphics.text(this.font, entry.name(), left + 22, y + 3, nameColor);
            String status = switch (entry.state()) {
                case RosterEntry.ONLINE -> entry.activity() + "  ❤" + Math.round(entry.health()) + " ☕" + entry.food();
                case RosterEntry.BANNED -> Component.translatable("joinedthegame.screen.banned").getString();
                default -> Component.translatable("joinedthegame.screen.offline").getString();
            };
            graphics.text(this.font, this.font.plainSubstrByWidth(status, PANEL - 190), left + 22, y + 13, 0xFF999999);
            y += ROW;
        }
        if (entries.size() > visibleRows()) {
            String more = (this.scroll + 1) + "-" + Math.min(entries.size(), this.scroll + visibleRows()) + " / " + entries.size();
            graphics.text(this.font, more, left + PANEL - this.font.width(more), listTop() - 12, 0xFF777777);
        }
    }

    private static PlayerSkin skin(RosterEntry entry) {
        var connection = Minecraft.getInstance().getConnection();
        PlayerInfo info = connection == null ? null : connection.getPlayerInfo(entry.uuid());
        return info != null ? info.getSkin() : DefaultPlayerSkin.get(entry.uuid());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
