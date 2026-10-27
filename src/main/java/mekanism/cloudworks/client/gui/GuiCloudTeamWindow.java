package mekanism.cloudworks.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import mekanism.client.gui.GuiMekanism;
import mekanism.client.gui.GuiUtils;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiColorPickerSlot;
import mekanism.client.gui.element.GuiInsetElement;
import mekanism.client.gui.element.button.MekanismButton;
import mekanism.client.gui.element.button.MekanismImageButton;
import mekanism.client.gui.element.text.GuiTextField;
import mekanism.client.gui.element.window.GuiColorWindow;
import mekanism.client.gui.element.window.GuiWindow;
import mekanism.client.render.MekanismRenderer;
import mekanism.cloudworks.client.gui.team.CloudworksTeamGui;
import mekanism.cloudworks.client.gui.team.GuiCloudTeamPlayerList;
import mekanism.cloudworks.client.gui.team.GuiCloudTeamPlayerList.Entry;
import mekanism.cloudworks.common.content.CloudworksWindowTypes;
import mekanism.cloudworks.common.inventory.ContainerCloudworks;
import mekanism.cloudworks.common.network.CloudworksNetwork.Operation;
import mekanism.cloudworks.common.network.data.CloudworksGuiData.Member;
import mekanism.cloudworks.common.network.data.CloudworksGuiData.Team;
import mekanism.cloudworks.common.network.data.CloudworksGuiData.TeamRequest;
import mekanism.common.lib.Color;
import mekanism.common.util.LangUtils;
import mekanism.common.util.MekanismUtils;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextComponentString;

import static mekanism.cloudworks.client.gui.team.CloudworksTeamGui.text;

/** Team identity and members, with leader-only actions in the two left tabs. */
public final class GuiCloudTeamWindow extends GuiWindow {
    public static final int WIDTH = 150, HEIGHT = 240;
    private static final int CONTENT_WIDTH = WIDTH - 16;
    private static final int MEMBER_WINDOW_HEIGHT = 210;
    private static final int FIELD_X = 58;
    private final ContainerCloudworks container;
    private final GuiTextField teamName;
    private final MekanismButton rename, mode, remove;
    private final GuiColorPickerSlot color;
    private final TeamTab requestsTab, operationsTab;
    private Team team = Team.EMPTY;
    private UUID selectedMember;
    private int selectedColor;
    private long seenVersion = -1;
    private RequestsWindow requestsWindow;
    private OperationsWindow operationsWindow;
    private TransferWindow transferWindow;
    private GuiColorWindow colorWindow;

    public GuiCloudTeamWindow(IGuiWrapper gui, int x, int y, ContainerCloudworks container) {
        super(gui, x, y, WIDTH, HEIGHT, CloudworksWindowTypes.CLOUD_TEAM);
        this.container = container;
        teamName = addChild(new GuiTextField(gui, relativeX + FIELD_X, relativeY + 22,
                WIDTH - FIELD_X - 30, 18).setMaxLength(64));
        rename = addChild(new MekanismImageButton(gui, relativeX + WIDTH - 26, relativeY + 22, 18,
                new ResourceLocation("mekanism", "gui/button/checkmark.png"),
                () -> send(Operation.TEAM_RENAME, teamName.getText().trim())).setTooltip(text("rename")));
        color = addChild(new GuiColorPickerSlot(gui, relativeX + FIELD_X, relativeY + 44, true,
                () -> Color.argb(selectedColor), value -> selectedColor = value.argb()) {
            @Override public void onClick(double mouseX, double mouseY) { openColor(); }
        });
        mode = button(FIELD_X, 66, WIDTH - FIELD_X - 8, "mode_approval", this::cycleMode);
        addChild(new GuiCloudTeamPlayerList(gui, relativeX + 8, relativeY + 124, CONTENT_WIDTH, 86,
                this::members, () -> selectedMember, entry -> {
                    if (isOwner()) selectedMember = entry == null ? null : entry.id;
                }, text("no_members")));
        remove = button(8, 214, CONTENT_WIDTH, "remove", () -> {
            Member member = selected();
            if (member != null && isOwner() && !member.id.equals(team.owner))
                container.sendAction(Operation.TEAM_REMOVE, member.id, "");
        });
        requestsTab = addChild(new TeamTab(gui, relativeX - 26, relativeY + 24,
                "amplifier_entity.png", "requests", this::openRequests));
        operationsTab = addChild(new TeamTab(gui, relativeX - 26, relativeY + 54,
                "stats.png", "operations", this::openOperations));
        refresh();
    }

    private MekanismButton button(int x, int y, int width, String key, Runnable action) {
        return addChild(new MekanismButton(gui(), relativeX + x, relativeY + y, width, 18,
                text(key), action, null));
    }

    private void send(Operation operation, String value) {
        if (isOwner()) container.sendAction(operation, null, value);
    }

    private boolean isOwner() {
        return team.owner != null && team.owner.equals(container.player().getUniqueID());
    }

    private Member selected() {
        for (Member member : team.members) if (member.id.equals(selectedMember)) return member;
        return null;
    }

    private List<Entry> members() {
        List<Entry> entries = new ArrayList<>();
        for (Member member : team.members) {
            String role = text(member.id.equals(team.owner) ? "leader_label" : "member_label").getUnformattedText();
            String online = text(member.online ? "online" : "offline").getUnformattedText();
            entries.add(new Entry(member.id, member.id, member.name, role + " · " + online,
                    member.online ? GuiCloudTeamPlayerList.AUTO_COLOR : GuiCloudTeamPlayerList.APPROVAL_COLOR,
                    isOwner()));
        }
        return entries;
    }

    private void cycleMode() {
        if (!isOwner()) return;
        String next = "AUTO_JOIN".equals(team.joinMode) ? "APPROVAL"
                : "APPROVAL".equals(team.joinMode) ? "DISABLED" : "AUTO_JOIN";
        send(Operation.TEAM_SET_MODE, next);
    }

    private void openColor() {
        if (!isOwner() || colorWindow != null) return;
        colorWindow = new GuiColorWindow(gui(), childX(GuiColorWindow.getWindowWidth(true, false)),
                childY(GuiColorWindow.getWindowHeight(true)), true,
                Color.argb(team.color), value -> selectedColor = value.argb());
        colorWindow.setTabListeners(window -> {
            colorWindow = null;
            // GuiColorWindow restores the starting color on cancel. Commit a
            // single change on confirmation instead of writing on every drag.
            if (isOwner() && selectedColor != team.color)
                send(Operation.TEAM_SET_COLOR, Integer.toUnsignedString(selectedColor, 16));
        }, null);
        gui().addWindow(colorWindow);
    }

    private void openRequests() {
        if (!isOwner() || !"APPROVAL".equals(team.joinMode) || requestsWindow != null) return;
        requestsWindow = new RequestsWindow();
        requestsWindow.setTabListeners(window -> requestsWindow = null, null);
        gui().addWindow(requestsWindow);
        updateControls();
    }

    private void openOperations() {
        if (!isOwner() || operationsWindow != null) return;
        operationsWindow = new OperationsWindow();
        operationsWindow.setTabListeners(window -> operationsWindow = null, null);
        gui().addWindow(operationsWindow);
        updateControls();
    }

    private void openTransfer() {
        if (!isOwner() || transferWindow != null) return;
        transferWindow = new TransferWindow();
        transferWindow.setTabListeners(window -> transferWindow = null, null);
        gui().addWindow(transferWindow);
    }

    private void refresh() {
        Team previous = team;
        team = container.guiData().overview.team;
        if (!Objects.equals(previous.id, team.id) || !Objects.equals(previous.name, team.name))
            teamName.setText(team.name);
        if (colorWindow == null) selectedColor = team.color;
        if (selected() == null) selectedMember = null;
        if (!Objects.equals(previous.owner, team.owner) || !Objects.equals(previous.id, team.id)) {
            // Window ticks run while the host iterates its windows. Hide
            // immediately and queue removal to avoid modifying that iterator.
            closeLater(transferWindow);
            closeLater(operationsWindow);
            closeLater(requestsWindow);
            closeLater(colorWindow);
        } else if (!"APPROVAL".equals(team.joinMode)) {
            closeLater(requestsWindow);
        }
        seenVersion = container.viewVersion();
        updateControls();
    }

    private void updateControls() {
        boolean owner = isOwner();
        teamName.visible = teamName.active = owner;
        if (!owner) teamName.setFocused(false);
        rename.visible = owner;
        rename.active = owner && CloudworksTeamGui.validName(teamName.getText())
                && !teamName.getText().trim().equals(team.name);
        color.visible = color.active = owner;
        mode.visible = mode.active = owner;
        mode.setMessage(CloudworksTeamGui.mode(team.joinMode));
        remove.visible = owner;
        Member selected = selected();
        remove.active = owner && selected != null && !selected.id.equals(team.owner);
        requestsTab.visible = owner && "APPROVAL".equals(team.joinMode);
        requestsTab.active = requestsTab.visible && requestsWindow == null;
        operationsTab.visible = owner;
        operationsTab.active = owner && operationsWindow == null;
    }

    private void closeLater(GuiWindow window) {
        if (window == null) return;
        window.visible = window.active = false;
        if (gui() instanceof GuiMekanism<?> screen) screen.queueWindowClose(window);
        else window.close();
    }

    private int childX(int width) { return relativeX + (WIDTH - width) / 2; }
    private int childY(int height) { return relativeY + (HEIGHT - height) / 2; }

    @Override public void tick() {
        super.tick();
        if (seenVersion != container.viewVersion()) refresh();
        updateControls();
    }

    @Override public void close() {
        if (transferWindow != null) transferWindow.close();
        if (operationsWindow != null) operationsWindow.close();
        if (requestsWindow != null) requestsWindow.close();
        if (colorWindow != null) colorWindow.close();
        super.close();
    }

    private void label(String key, int x, int y, int width) {
        drawScaledScrollingString(text(key), x, y, TextAlignment.LEFT, titleTextColor(),
                width, 0, false, 0.85F, 0);
    }

    @Override public void renderForeground(int mouseX, int mouseY) {
        super.renderForeground(mouseX, mouseY);
        drawTitleText(text("management"), 5);
        label("name_label", 8, 27, FIELD_X - 12);
        label("color_label", 8, 49, FIELD_X - 12);
        label("mode_label", 8, 71, FIELD_X - 12);
        label("leader_label", 8, 94, FIELD_X - 12);
        label("members", 8, 113, CONTENT_WIDTH);
        if (!isOwner()) {
            drawScaledScrollingString(new TextComponentString(team.name), FIELD_X, 27, TextAlignment.LEFT,
                    titleTextColor(), WIDTH - FIELD_X - 8, 0, false, 0.9F, 0);
            GuiUtils.fill(relativeX + FIELD_X, relativeY + 44, relativeX + FIELD_X + 18, relativeY + 62, 0xFF707070);
            GuiUtils.fill(relativeX + FIELD_X + 1, relativeY + 45, relativeX + FIELD_X + 17, relativeY + 61, team.color);
            drawScaledScrollingString(CloudworksTeamGui.mode(team.joinMode), FIELD_X, 71, TextAlignment.LEFT,
                    titleTextColor(), WIDTH - FIELD_X - 8, 0, false, 0.9F, 0);
        }
        CloudworksTeamGui.avatar(team.owner, relativeX + FIELD_X, relativeY + 88, 18);
        String leader = "";
        for (Member member : team.members) if (member.id.equals(team.owner)) { leader = member.name; break; }
        drawScaledScrollingString(new TextComponentString(leader), FIELD_X + 24, 94, TextAlignment.LEFT,
                titleTextColor(), WIDTH - FIELD_X - 32, 0, false, 0.9F, 0);
        feedback(this, 8, isOwner() ? 233 : 219, CONTENT_WIDTH);
    }

    private void feedback(GuiWindow window, int x, int y, int width) {
        String status = container.status();
        if (status.startsWith("cloudworks.error.") || status.startsWith("cloudworks.team.error."))
            window.drawScaledScrollingString(new TextComponentString(LangUtils.localize(status)), x, y,
                    TextAlignment.LEFT, 0xFFA03030, width, 0, false, 0.7F, 0);
    }

    private final class RequestsWindow extends GuiWindow {
        private UUID selectedRequest;
        private final MekanismButton approve, deny;

        RequestsWindow() {
            super(GuiCloudTeamWindow.this.gui(), childX(WIDTH), childY(MEMBER_WINDOW_HEIGHT),
                    WIDTH, MEMBER_WINDOW_HEIGHT, CloudworksWindowTypes.CLOUD_TEAM_REQUESTS);
            addChild(new GuiCloudTeamPlayerList(gui(), relativeX + 8, relativeY + 26, CONTENT_WIDTH, 142,
                    () -> {
                        List<Entry> entries = new ArrayList<>();
                        for (TeamRequest request : team.requests)
                            entries.add(new Entry(request.id, request.id, request.name,
                                    text("request").getUnformattedText(), GuiCloudTeamPlayerList.APPROVAL_COLOR, isOwner()));
                        return entries;
                    }, () -> selectedRequest, entry -> selectedRequest = entry == null ? null : entry.id,
                    text("no_requests")));
            int buttonWidth = (CONTENT_WIDTH - 4) / 2;
            approve = addChild(new MekanismButton(gui(), relativeX + 8, relativeY + 176, buttonWidth, 18,
                    text("approve"), () -> act(Operation.TEAM_APPROVE_JOIN), null));
            deny = addChild(new MekanismButton(gui(), relativeX + 12 + buttonWidth, relativeY + 176, buttonWidth, 18,
                    text("deny"), () -> act(Operation.TEAM_DENY_JOIN), null));
            approve.active = deny.active = false;
        }

        private boolean hasRequest() {
            for (TeamRequest request : team.requests) if (request.id.equals(selectedRequest)) return true;
            return false;
        }

        private void act(Operation operation) {
            if (isOwner() && "APPROVAL".equals(team.joinMode) && hasRequest())
                container.sendAction(operation, selectedRequest, "");
        }

        @Override public void tick() {
            super.tick();
            if (!isOwner() || !"APPROVAL".equals(team.joinMode)) { closeLater(this); return; }
            approve.active = deny.active = hasRequest();
        }

        @Override public void renderForeground(int mouseX, int mouseY) {
            super.renderForeground(mouseX, mouseY);
            drawTitleText(text("requests"), 5);
            feedback(this, 8, 198, CONTENT_WIDTH);
        }
    }

    private final class OperationsWindow extends GuiWindow {
        private final MekanismButton importFtb, transfer, dissolve;
        private boolean confirmDissolve;

        OperationsWindow() {
            super(GuiCloudTeamWindow.this.gui(), childX(WIDTH), childY(146),
                    WIDTH, 146, CloudworksWindowTypes.CLOUD_TEAM_OPERATIONS);
            importFtb = operationButton(8, 44, CONTENT_WIDTH, "import_ftb", () -> send(Operation.TEAM_IMPORT, ""));
            transfer = operationButton(8, 76, CONTENT_WIDTH, "owner", GuiCloudTeamWindow.this::openTransfer);
            dissolve = operationButton(8, 100, CONTENT_WIDTH, "dissolve", this::dissolveTeam);
        }

        private void dissolveTeam() {
            if (!isOwner()) return;
            if (confirmDissolve) send(Operation.TEAM_DISSOLVE, "");
            else {
                confirmDissolve = true;
                dissolve.setMessage(text("confirm_dissolve"));
            }
        }

        private MekanismButton operationButton(int x, int y, int width, String key, Runnable action) {
            return addChild(new MekanismButton(gui(), relativeX + x, relativeY + y, width, 18,
                    text(key), action, null));
        }

        @Override public void tick() {
            super.tick();
            if (!isOwner()) { closeLater(this); return; }
            importFtb.active = team.ftbAvailable;
            transfer.active = team.members.size() > 1 && transferWindow == null;
        }

        @Override public void renderForeground(int mouseX, int mouseY) {
            super.renderForeground(mouseX, mouseY);
            drawTitleText(text("operations"), 5);
            drawScaledScrollingString(team.ftbBinding.isEmpty() ? text("native")
                    : new TextComponentString(LangUtils.localizeWithFormat("cloudworks.team.binding", team.ftbBinding)),
                    8, 26, TextAlignment.LEFT, titleTextColor(), CONTENT_WIDTH, 0, false, 0.8F, 0);
            feedback(this, 8, 127, CONTENT_WIDTH);
        }
    }

    private final class TransferWindow extends GuiWindow {
        private UUID selectedTarget, confirmedTarget;
        private boolean waiting;
        private long submittedVersion;
        private final MekanismButton confirm;

        TransferWindow() {
            super(GuiCloudTeamWindow.this.gui(), childX(WIDTH), childY(MEMBER_WINDOW_HEIGHT),
                    WIDTH, MEMBER_WINDOW_HEIGHT, CloudworksWindowTypes.CLOUD_TEAM_TRANSFER);
            addChild(new GuiCloudTeamPlayerList(gui(), relativeX + 8, relativeY + 26, CONTENT_WIDTH, 142,
                    () -> {
                        List<Entry> entries = new ArrayList<>();
                        for (Member member : team.members)
                            if (!member.id.equals(team.owner))
                                entries.add(new Entry(member.id, member.id, member.name,
                                        text(member.online ? "online" : "offline").getUnformattedText(),
                                        GuiCloudTeamPlayerList.APPROVAL_COLOR, isOwner() && !waiting));
                        return entries;
                    }, () -> selectedTarget, entry -> {
                        UUID target = entry == null ? null : entry.id;
                        if (!Objects.equals(target, selectedTarget)) {
                            selectedTarget = target;
                            confirmedTarget = null;
                        }
                    }, text("no_transfer_members")));
            confirm = addChild(new MekanismButton(gui(), relativeX + 8, relativeY + 176, CONTENT_WIDTH, 18,
                    text("confirm_transfer"), this::confirmTransfer, null));
            confirm.active = false;
        }

        private boolean hasTarget() {
            for (Member member : team.members)
                if (member.id.equals(selectedTarget) && !member.id.equals(team.owner)) return true;
            return false;
        }

        private void confirmTransfer() {
            if (!isOwner() || waiting || !hasTarget()) return;
            if (!selectedTarget.equals(confirmedTarget)) {
                confirmedTarget = selectedTarget;
                confirm.setMessage(text("confirm_again"));
            } else {
                waiting = true;
                submittedVersion = container.viewVersion();
                container.sendAction(Operation.TEAM_OWNER, selectedTarget, "");
            }
        }

        @Override public void tick() {
            super.tick();
            if (!isOwner()) { closeLater(this); return; }
            if (waiting && container.viewVersion() > submittedVersion) {
                waiting = false;
                confirmedTarget = null;
            }
            if (!hasTarget()) confirmedTarget = null;
            confirm.active = !waiting && hasTarget();
            confirm.setMessage(text(confirmedTarget == null ? "confirm_transfer" : "confirm_again"));
        }

        @Override public void renderForeground(int mouseX, int mouseY) {
            super.renderForeground(mouseX, mouseY);
            drawTitleText(text("owner"), 5);
            feedback(this, 8, 198, CONTENT_WIDTH);
        }
    }

    private static final class TeamTab extends GuiInsetElement<Void> {
        private final String tooltip;
        private final Runnable action;

        TeamTab(IGuiWrapper gui, int x, int y, String icon, String tooltip, Runnable action) {
            super(MekanismUtils.getResource(MekanismUtils.ResourceType.GUI, icon), gui, null, x, y, 26, 18, true);
            this.tooltip = tooltip;
            this.action = action;
        }

        @Override protected void colorTab() { MekanismRenderer.color(active ? 0xFF76A6BA : 0xFF888888); }
        @Override public void onClick(double mouseX, double mouseY, int button) { if (active) action.run(); }
        @Override public void renderToolTip(int mouseX, int mouseY) {
            super.renderToolTip(mouseX, mouseY);
            displayTooltip(text(tooltip), mouseX, mouseY);
        }
    }
}
