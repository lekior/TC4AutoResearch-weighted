package com.Emil.TCAutoResearch.mixins;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.Emil.TCAutoResearch.AspectSynthesisController;
import com.Emil.TCAutoResearch.BatchResearchController;
import com.Emil.TCAutoResearch.ClientResearchTickHandler;
import com.Emil.TCAutoResearch.Config;
import com.Emil.TCAutoResearch.GuiAspectWeights;
import com.Emil.TCAutoResearch.GuiCompletionReport;
import com.Emil.TCAutoResearch.GuiResearchQueue;
import com.Emil.TCAutoResearch.GuiResearchTableHelperInterface;
import com.Emil.TCAutoResearch.ResearchCatalog;
import com.Emil.TCAutoResearch.ResearchNoteItems;
import com.Emil.TCAutoResearch.ResearchSolveController;
import com.Emil.TCAutoResearch.TargetResearchController;

import cpw.mods.fml.client.config.GuiButtonExt;
import thaumcraft.api.aspects.Aspect;
import thaumcraft.api.aspects.AspectList;
import thaumcraft.client.gui.GuiResearchTable;
import thaumcraft.client.lib.PlayerNotifications;
import thaumcraft.common.Thaumcraft;
import thaumcraft.common.lib.network.PacketHandler;
import thaumcraft.common.lib.network.playerdata.PacketAspectCombinationToServer;
import thaumcraft.common.lib.network.playerdata.PacketAspectPlaceToServer;
import thaumcraft.common.lib.research.ResearchManager;
import thaumcraft.common.lib.research.ResearchNoteData;
import thaumcraft.common.lib.utils.HexUtils;
import thaumcraft.common.tiles.TileResearchTable;

@Mixin(value = GuiResearchTable.class, remap = false)
public abstract class GuiResearchTableMixin extends GuiContainer implements GuiResearchTableHelperInterface {

    private GuiResearchTableMixin(Container p_i1072_1_) {
        super(p_i1072_1_);
    }

    @Unique
    private GuiButton tcAutoResearch$unlockAllButton;
    @Unique
    private GuiButton tcAutoResearch$autoButton;
    @Unique
    private GuiButton tcAutoResearch$solveButton;
    @Unique
    private GuiButton tcAutoResearch$retryButton;
    @Unique
    private GuiButton tcAutoResearch$weightsButton;
    @Unique
    private GuiButton tcAutoResearch$reportButton;
    @Unique
    private GuiButton tcAutoResearch$batchButton;
    @Unique
    private GuiButton tcAutoResearch$targetButton;
    @Unique
    private GuiTextField tcAutoResearch$amountField;
    @Unique
    private GuiButton tcAutoResearch$confirmButton;
    @Unique
    private Aspect tcAutoResearch$selectedAspect;
    @Unique
    private long tcAutoResearch$lastClickTime;
    @Shadow
    public ResearchNoteData note;
    @Shadow
    EntityPlayer player;
    @Shadow
    private TileResearchTable tileEntity;

    @Override
    public void initGui() {
        super.initGui();
        AspectList discoveredAspects = Thaumcraft.proxy.getPlayerKnowledge()
            .getAspectsDiscovered(player.getCommandSenderName());
        tcAutoResearch$unlockAllButton = new GuiButtonExt(
            101,
            super.guiLeft - 80,
            super.guiTop + 255 / 2 - 50,
            80,
            25,
            StatCollector.translateToLocal("tcautores.unlock_all"));
        tcAutoResearch$unlockAllButton.visible = discoveredAspects.aspects.size() != Aspect.aspects.size();
        this.buttonList.add(tcAutoResearch$unlockAllButton);

        tcAutoResearch$autoButton = new GuiButtonExt(
            102,
            super.guiLeft - 80,
            super.guiTop + 255 / 2 - 25,
            80,
            25,
            tcAutoResearch$autoButtonText());
        tcAutoResearch$solveButton = new GuiButtonExt(
            104,
            super.guiLeft - 80,
            super.guiTop + 255 / 2,
            80,
            25,
            StatCollector.translateToLocal("tcautores.solve_current"));
        tcAutoResearch$solveButton.visible = !Config.autoResearch();
        tcAutoResearch$retryButton = new GuiButtonExt(
            105,
            super.guiLeft - 80,
            super.guiTop + 255 / 2 + 25,
            80,
            25,
            StatCollector.translateToLocal("tcautores.retry_last"));
        tcAutoResearch$retryButton.visible = !Config.autoResearch();
        tcAutoResearch$weightsButton = new GuiButtonExt(
            106,
            super.guiLeft - 80,
            super.guiTop + 255 / 2 + 50,
            80,
            25,
            StatCollector.translateToLocal("tcautores.weights"));
        tcAutoResearch$reportButton = new GuiButtonExt(
            107,
            Math.min(width - 82, super.guiLeft + super.xSize),
            super.guiTop + 255 / 2 + 50,
            80,
            25,
            StatCollector.translateToLocal("tcautores.report_button"));
        tcAutoResearch$batchButton = new GuiButtonExt(
            108,
            Math.min(width - 82, super.guiLeft + super.xSize),
            super.guiTop + 255 / 2 + 25,
            80,
            25,
            BatchResearchController.buttonText());
        tcAutoResearch$targetButton = new GuiButtonExt(
            111,
            Math.min(width - 82, super.guiLeft + super.xSize),
            super.guiTop + 255 / 2,
            80,
            25,
            TargetResearchController.buttonText());

        this.buttonList.add(tcAutoResearch$autoButton);
        this.buttonList.add(tcAutoResearch$solveButton);
        this.buttonList.add(tcAutoResearch$retryButton);
        this.buttonList.add(tcAutoResearch$weightsButton);
        this.buttonList.add(tcAutoResearch$reportButton);
        this.buttonList.add(tcAutoResearch$batchButton);
        this.buttonList.add(tcAutoResearch$targetButton);

        this.tcAutoResearch$amountField = new GuiTextField(this.fontRendererObj, 0, 0, 25, 10);
        this.tcAutoResearch$amountField.setMaxStringLength(10);
        this.tcAutoResearch$amountField.setFocused(true);
        this.tcAutoResearch$amountField.setVisible(false);
        this.tcAutoResearch$confirmButton = new GuiButtonExt(
            103,
            0,
            0,
            25,
            13,
            StatCollector.translateToLocal("gui.done"));
        this.tcAutoResearch$confirmButton.visible = false;
        this.buttonList.add(this.tcAutoResearch$confirmButton);
        ClientResearchTickHandler.watch(player, this.mc, this);
        TargetResearchController.attach(this, player, this.mc);
        BatchResearchController.attach(this, player, this.mc);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (this.tcAutoResearch$amountField != null && this.tcAutoResearch$amountField.getVisible()) {
            if (this.tcAutoResearch$amountField.textboxKeyTyped(typedChar, keyCode)) return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void actionPerformed(GuiButton targetButton) {
        super.actionPerformed(targetButton);
        long now = System.currentTimeMillis();
        if (now - tcAutoResearch$lastClickTime < 200) return;
        tcAutoResearch$lastClickTime = now;
        if (targetButton.id == 101) {
            AspectSynthesisController.discoverAll(this, player, () -> tcAutoResearch$unlockAllButton.visible = false);
        } else if (targetButton.id == 103) {
            try {
                int amount = Integer.parseInt(tcAutoResearch$amountField.getText());
                AspectSynthesisController.synthesize(this, player, tcAutoResearch$selectedAspect, amount);
                tcAutoResearch$amountField.setVisible(false);
                tcAutoResearch$confirmButton.visible = false;
            } catch (NumberFormatException ignored) {
                PlayerNotifications.addNotification(StatCollector.translateToLocal("tcautores.invalid_amount"));
            }
        } else if (targetButton.id == 102) {
            if (Config.autoResearch()) {
                Config.setAutoResearch(false);
                Config.saveSolverConfiguration();
                ResearchSolveController.cancel();
                tcAutoResearch$autoButton.displayString = tcAutoResearch$autoButtonText();
                tcAutoResearch$solveButton.visible = true;
                tcAutoResearch$retryButton.visible = true;

            } else {
                Config.setAutoResearch(true);
                Config.saveSolverConfiguration();
                tcAutoResearch$autoButton.displayString = tcAutoResearch$autoButtonText();
                tcAutoResearch$solveButton.visible = false;
                tcAutoResearch$retryButton.visible = false;
            }
        } else if (targetButton.id == 104) {
            ResearchSolveController.request(this, player, mc, false);
        } else if (targetButton.id == 105) {
            ResearchSolveController.retry(this, player, mc);
        } else if (targetButton.id == 106) {
            ResearchSolveController.prepareChildScreen();
            mc.displayGuiScreen(new GuiAspectWeights(this));
        } else if (targetButton.id == 107) {
            ResearchSolveController.prepareChildScreen();
            mc.displayGuiScreen(new GuiCompletionReport(this, ResearchSolveController.getLastReport()));
        } else if (targetButton.id == 108) {
            if (TargetResearchController.isRunning()) TargetResearchController.cancel();
            else TargetResearchController.startExistingNotes(this, player, mc);
        } else if (targetButton.id == 111) {
            if (TargetResearchController.isRunning()) {
                TargetResearchController.cancel();
            } else {
                ResearchSolveController.prepareChildScreen();
                mc.displayGuiScreen(new GuiResearchQueue(this, ResearchCatalog.currentCategory()));
            }
        }

    }

    @Override
    public void onGuiClosed() {
        ClientResearchTickHandler.stopWatching();
        TargetResearchController.onResearchTableClosed(this);
        BatchResearchController.onResearchTableClosed(this);
        AspectSynthesisController.cancel();
        if (ResearchSolveController.onResearchGuiClosed()) super.onGuiClosed();
    }

    @Invoker("getClickedAspect")
    public abstract Aspect invokeGetClickedAspect(int mx, int my, int gx, int gy, boolean ignoreZero);

    @Inject(method = "func_73864_a", at = @At("Tail"), cancellable = false)
    private void tcAutoResearch$afterMouseClicked(int mouseX, int mouseY, int mouseButton, CallbackInfo ci) {
        if (isCtrlKeyDown()) {
            int gx = (this.width - this.xSize) / 2;
            int gy = (this.height - this.ySize) / 2;
            Aspect aspect = invokeGetClickedAspect(mouseX, mouseY, gx, gy, true);
            if (aspect != null) {
                tcAutoResearch$selectedAspect = aspect;
                this.tcAutoResearch$amountField.xPosition = mouseX - 20;
                this.tcAutoResearch$amountField.yPosition = mouseY + 8;
                this.tcAutoResearch$amountField.setVisible(true);
                try {
                    int amount = Integer.parseInt(tcAutoResearch$amountField.getText());
                    this.tcAutoResearch$amountField.setText(String.valueOf(amount));
                } catch (NumberFormatException ignored) {
                    this.tcAutoResearch$amountField.setText("1");
                }
                this.tcAutoResearch$amountField.setCursorPosition(0);
                this.tcAutoResearch$amountField.setSelectionPos(
                    this.tcAutoResearch$amountField.getText()
                        .length());
                this.tcAutoResearch$confirmButton.xPosition = this.tcAutoResearch$amountField.xPosition + 27;
                this.tcAutoResearch$confirmButton.yPosition = this.tcAutoResearch$amountField.yPosition - 2;
                this.tcAutoResearch$confirmButton.visible = true;
            }
        } else if (this.tcAutoResearch$confirmButton.visible) {
            this.tcAutoResearch$amountField.setVisible(false);
            this.tcAutoResearch$confirmButton.visible = false;
        }
    }

    @Inject(method = "func_73863_a", at = @At("Tail"))
    private void tcAutoResearch$afterDrawScreen(int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        boolean batchRunning = BatchResearchController.isRunning();
        boolean targetRunning = TargetResearchController.isRunning();
        boolean busy = batchRunning || targetRunning;
        this.tcAutoResearch$batchButton.displayString = BatchResearchController.buttonText();
        this.tcAutoResearch$targetButton.displayString = TargetResearchController.buttonText();
        this.tcAutoResearch$unlockAllButton.enabled = !busy;
        this.tcAutoResearch$autoButton.enabled = !busy;
        this.tcAutoResearch$solveButton.enabled = !busy;
        this.tcAutoResearch$retryButton.enabled = !busy;
        this.tcAutoResearch$weightsButton.enabled = !busy;
        this.tcAutoResearch$reportButton.enabled = !busy;
        this.tcAutoResearch$batchButton.enabled = !batchRunning;
        this.tcAutoResearch$targetButton.enabled = !batchRunning;
        if (this.tcAutoResearch$amountField != null && this.tcAutoResearch$amountField.getVisible()) {
            this.tcAutoResearch$amountField.drawTextBox();
        }
    }

    @Override
    public void place(HexUtils.Hex hex, Aspect aspect) {
        PacketHandler.INSTANCE.sendToServer(
            new PacketAspectPlaceToServer(
                this.player,
                (byte) hex.q,
                (byte) hex.r,
                this.tileEntity.xCoord,
                this.tileEntity.yCoord,
                this.tileEntity.zCoord,
                aspect));
    }

    @Override
    public boolean hasInk() {
        return ResearchManager.consumeInkFromTable(this.tileEntity.getStackInSlot(0), false);
    }

    @Override
    public AspectList availableAspects() {
        AspectList available = new AspectList();
        String username = player.getCommandSenderName();
        for (Object value : Aspect.aspects.values()) {
            Aspect aspect = (Aspect) value;
            int amount = Thaumcraft.proxy.getPlayerKnowledge()
                .getAspectPoolFor(username, aspect) + tileEntity.bonusAspects.getAmount(aspect);
            if (amount > 0) available.add(aspect, amount);
        }
        return available;
    }

    @Override
    public ItemStack researchNoteStack() {
        return this.inventorySlots.getSlot(1)
            .getStack();
    }

    @Override
    public ItemStack scribingToolsStack() {
        return this.inventorySlots.getSlot(0)
            .getStack();
    }

    @Override
    public int findIncompleteResearchNoteSlot() {
        return findIncompleteResearchNoteSlot(null);
    }

    @Override
    public int findIncompleteResearchNoteSlot(String key) {
        int end = Math.min(38, this.inventorySlots.inventorySlots.size());
        for (int slot = 2; slot < end; slot++) {
            ItemStack stack = this.inventorySlots.getSlot(slot)
                .getStack();
            if (ResearchNoteItems.isIncomplete(stack) && (key == null || ResearchNoteItems.hasKey(stack, key)))
                return slot;
        }
        return -1;
    }

    @Override
    public int countIncompleteResearchNotes() {
        return countIncompleteResearchNotes(null);
    }

    @Override
    public int countIncompleteResearchNotes(String key) {
        ItemStack tableStack = researchNoteStack();
        int count = ResearchNoteItems.isIncomplete(tableStack)
            && (key == null || ResearchNoteItems.hasKey(tableStack, key)) ? 1 : 0;
        int end = Math.min(38, this.inventorySlots.inventorySlots.size());
        for (int slot = 2; slot < end; slot++) {
            ItemStack stack = this.inventorySlots.getSlot(slot)
                .getStack();
            if (ResearchNoteItems.isIncomplete(stack) && (key == null || ResearchNoteItems.hasKey(stack, key))) count++;
        }
        return count;
    }

    @Override
    public int findCompletedResearchNoteSlot(String key) {
        int end = Math.min(38, this.inventorySlots.inventorySlots.size());
        for (int slot = 1; slot < end; slot++) {
            ItemStack stack = this.inventorySlots.getSlot(slot)
                .getStack();
            if (ResearchNoteItems.isComplete(stack) && ResearchNoteItems.hasKey(stack, key)) return slot;
        }
        return -1;
    }

    @Override
    public List<String> researchNoteKeys() {
        List<String> completedKeys = new ArrayList<>();
        List<String> incompleteKeys = new ArrayList<>();
        int end = Math.min(38, this.inventorySlots.inventorySlots.size());
        for (int slot = 1; slot < end; slot++) {
            ItemStack stack = this.inventorySlots.getSlot(slot)
                .getStack();
            ResearchNoteData data = ResearchNoteItems.data(stack);
            if (data == null || completedKeys.contains(data.key) || incompleteKeys.contains(data.key)) continue;
            (ResearchNoteItems.isComplete(stack) ? completedKeys : incompleteKeys).add(data.key);
        }
        List<String> keys = new ArrayList<>(completedKeys.size() + incompleteKeys.size());
        keys.addAll(completedKeys);
        keys.addAll(incompleteKeys);
        return keys;
    }

    @Override
    public int findUsableScribingToolsSlot() {
        int end = Math.min(38, this.inventorySlots.inventorySlots.size());
        for (int slot = 2; slot < end; slot++) {
            if (ResearchManager.consumeInkFromTable(
                this.inventorySlots.getSlot(slot)
                    .getStack(),
                false)) return slot;
        }
        return -1;
    }

    @Override
    public void combine(Aspect aspect1, Aspect aspect2) {
        PacketHandler.INSTANCE.sendToServer(
            new PacketAspectCombinationToServer(
                this.player,
                this.tileEntity.xCoord,
                this.tileEntity.yCoord,
                this.tileEntity.zCoord,
                aspect1,
                aspect2,
                this.tileEntity.bonusAspects.getAmount(aspect1) > 0,
                this.tileEntity.bonusAspects.getAmount(aspect2) > 0,
                true));
    }

    @Unique
    private String tcAutoResearch$autoButtonText() {
        return StatCollector.translateToLocal(Config.autoResearch() ? "tcautores.auto_on" : "tcautores.auto_off");
    }

}
