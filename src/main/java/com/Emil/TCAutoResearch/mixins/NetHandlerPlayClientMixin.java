package com.Emil.TCAutoResearch.mixins;

import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.play.server.S30PacketWindowItems;
import net.minecraft.network.play.server.S32PacketConfirmTransaction;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.Emil.TCAutoResearch.ContainerTransferController;

@Mixin(NetHandlerPlayClient.class)
public abstract class NetHandlerPlayClientMixin {

    @Inject(method = "handleConfirmTransaction", at = @At("HEAD"))
    private void tcAutoResearch$confirmResearchNoteTransfer(S32PacketConfirmTransaction packet, CallbackInfo ci) {
        ContainerTransferController
            .onConfirmation(packet.func_148889_c(), packet.func_148890_d(), packet.func_148888_e());
    }

    @Inject(method = "handleWindowItems", at = @At("TAIL"))
    private void tcAutoResearch$confirmInventoryResynchronization(S30PacketWindowItems packet, CallbackInfo ci) {
        ContainerTransferController.onWindowItems(packet.func_148911_c());
    }
}
