package mekanism.cloudworks.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import mekanism.client.gui.GuiMekanism;
import mekanism.client.gui.GuiUtils;
import mekanism.client.gui.element.GuiColorPickerSlot;
import mekanism.client.gui.element.GuiElementHolder;
import mekanism.client.gui.element.button.MekanismButton;
import mekanism.client.gui.element.text.GuiTextField;
import mekanism.cloudworks.client.gui.team.CloudworksTeamGui;
import mekanism.cloudworks.client.gui.team.GuiCloudTeamPlayerList;
import mekanism.cloudworks.client.gui.team.GuiCloudTeamPlayerList.Entry;
import mekanism.common.lib.Color;
import mekanism.common.util.LangUtils;
import mekanism.cloudworks.common.inventory.ContainerCloudTeamJoin;
import mekanism.cloudworks.common.network.PacketCloudTeamJoin;
import mekanism.cloudworks.common.util.CloudworksMessages;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import static mekanism.cloudworks.client.gui.team.CloudworksTeamGui.text;

/** Slotless public team chooser. The server remains authoritative for every action. */
@SideOnly(Side.CLIENT)
public final class GuiCloudTeamJoin extends GuiMekanism<ContainerCloudTeamJoin> {
    private final ContainerCloudTeamJoin container;
    private GuiTextField search, name;
    private MekanismButton refresh, create;
    private final List<MekanismButton> modes = new ArrayList<>();
    private int teamColor = 0xFF4F7FFF;
    private String createMode = "APPROVAL";
    private int formX;
    private boolean requestedList, waiting;
    private long sentVersion;
    private UUID pendingTeam;

    public GuiCloudTeamJoin(InventoryPlayer inventory) {
        this(new ContainerCloudTeamJoin(inventory));
    }

    private GuiCloudTeamJoin(ContainerCloudTeamJoin container) {
        super(container);
        this.container = container;
        xSize = 360;
        ySize = 224;
    }

    @Override public void initGui() {
        xSize = Math.min(360, Math.max(280, width - 16));
        super.initGui();
    }

    @Override
    protected void addGuiElements() {
        super.addGuiElements();
        String oldName = name == null ? container.player().getName() + "'s Cloudworks" : name.getText();
        String oldSearch = search == null ? "" : search.getText();
        formX = xSize - 146;
        int listWidth = formX - 20;
        search = addButton(new GuiTextField(this, 10, 34, listWidth, 18).setMaxLength(64));
        search.setText(oldSearch);
        addButton(new GuiCloudTeamPlayerList(this, 10, 65, listWidth, 142,
                this::teamRows, () -> pendingTeam, this::joinTeam, text("no_joinable")));
        addButton(new GuiElementHolder(this, formX, 25, 136, 182));
        name = addButton(new GuiTextField(this, formX + 8, 59, 120, 18).setMaxLength(64));
        name.setText(oldName);
        addButton(new GuiColorPickerSlot(this, formX + 110, 83, true,
                () -> Color.argb(teamColor), value -> teamColor = value.argb()));
        modes.clear();
        String[] values = {"AUTO_JOIN", "APPROVAL", "DISABLED"};
        for (int i = 0; i < values.length; i++) {
            String value = values[i];
            modes.add(addButton(new MekanismButton(this, formX + 8, 117 + i * 18, 120, 16,
                    CloudworksTeamGui.mode(value), () -> createMode = value, null)));
        }
        create = addButton(new MekanismButton(this, formX + 8, 181, 120, 18,
                text("confirm_create"), () -> {
                    if (!CloudworksTeamGui.validName(name.getText()) || waiting) return;
                    startRequest();
                    container.create(name.getText().trim(), teamColor, createMode);
                }, null));
        refresh = addButton(new MekanismButton(this, 10 + listWidth - 42, 54, 42, 10,
                text("refresh_short"), this::requestTeams, null));
        updateControls();
    }

    private List<Entry> teamRows() {
        List<Entry> rows = new ArrayList<>();
        String filter = search.getText().trim().toLowerCase(Locale.ROOT);
        for (PacketCloudTeamJoin.TeamEntry team : container.teams()) {
            if ("DISABLED".equals(team.mode) || !team.name.toLowerCase(Locale.ROOT).contains(filter)) continue;
            boolean pending = team.requested || team.id.equals(pendingTeam);
            int color = pending ? GuiCloudTeamPlayerList.REQUESTED_COLOR
                    : "AUTO_JOIN".equals(team.mode) ? GuiCloudTeamPlayerList.AUTO_COLOR
                    : GuiCloudTeamPlayerList.APPROVAL_COLOR;
            String state = pending ? text("requested").getUnformattedText()
                    : CloudworksTeamGui.mode(team.mode).getUnformattedText();
            rows.add(new Entry(team.id, team.leader, team.name, team.leaderName + " · " + state,
                    color, !pending && !waiting));
        }
        return rows;
    }

    private void joinTeam(Entry selected) {
        if (selected == null || waiting) return;
        for (PacketCloudTeamJoin.TeamEntry entry : container.teams()) {
            if (!entry.id.equals(selected.id) || entry.requested || "DISABLED".equals(entry.mode)) continue;
            pendingTeam = entry.id;
            startRequest();
            container.join(entry.id, !"AUTO_JOIN".equals(entry.mode));
            return;
        }
    }

    private void startRequest() {
        waiting = true;
        sentVersion = container.viewVersion();
        updateControls();
    }

    private void requestTeams() {
        if (waiting) return;
        startRequest();
        container.requestList();
    }

    private void updateControls() {
        if (create == null) return;
        create.active = !waiting && CloudworksTeamGui.validName(name.getText());
        refresh.active = !waiting;
        String[] values = {"AUTO_JOIN", "APPROVAL", "DISABLED"};
        for (int i = 0; i < modes.size(); i++) {
            modes.get(i).active = !waiting && !values[i].equals(createMode);
            modes.get(i).setMessage(new TextComponentString((values[i].equals(createMode) ? "● " : "○ ")
                    + CloudworksTeamGui.mode(values[i]).getUnformattedText()));
        }
    }

    @Override public void updateScreen() {
        super.updateScreen();
        // Forge assigns the client window ID after initGui. Request on the
        // following tick so the first list packet uses the actual window.
        if (!requestedList && mc.player != null && mc.player.openContainer == container) {
            requestedList = true;
            requestTeams();
        }
        if (waiting && container.viewVersion() > sentVersion) {
            waiting = false;
            pendingTeam = null;
        }
        updateControls();
    }

    @Override protected void drawForegroundText(int mouseX, int mouseY) {
        drawTitleText(text("join_or_create"), 7);
        drawString(text("search_label"), 10, 23, titleTextColor());
        drawScaledScrollingString(text("joinable_teams"), 10, 55, TextAlignment.LEFT,
                titleTextColor(), formX - 66, 0, false, 0.8F, 0);
        drawString(text("create"), formX + 8, 31, titleTextColor());
        drawString(text("name_label"), formX + 8, 47, titleTextColor());
        drawString(text("color_label"), formX + 8, 88, titleTextColor());
        drawString(text("mode_label"), formX + 8, 105, titleTextColor());
        GuiUtils.fill(formX - 5, 26, formX - 4, 207, 0xFF8B8B8B);
        String status = container.status();
        if (waiting) {
            drawScaledScrollingString(text("waiting"), 10, 212, TextAlignment.LEFT,
                    titleTextColor(), xSize - 20, 0, false, 0.75F, 0);
        } else if (!status.isEmpty() && !CloudworksMessages.SAVED.equals(status)) {
            drawScaledScrollingString(new TextComponentString(LangUtils.localize(status)), 10, 212,
                    TextAlignment.LEFT, 0xFFA03030, xSize - 20, 0, false, 0.75F, 0);
        }
    }
}
