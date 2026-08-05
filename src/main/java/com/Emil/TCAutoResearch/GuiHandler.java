package com.Emil.TCAutoResearch;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;

import cpw.mods.fml.common.network.IGuiHandler;
import thaumcraft.client.gui.GuiResearchTable;
import thaumcraft.common.container.ContainerResearchTable;
import thaumcraft.common.tiles.TileResearchTable;

public class GuiHandler implements IGuiHandler {

    @Override
    public Object getServerGuiElement(int ID, EntityPlayer player, World world, int x, int y, int z) {
        if (ID != 0) return null;
        TileResearchTable table = (TileResearchTable) world.getTileEntity(x, y, z);
        return table == null ? null : new ContainerResearchTable(player.inventory, table);
    }

    @Override
    public Object getClientGuiElement(int ID, EntityPlayer player, World world, int x, int y, int z) {
        if (ID != 0) return null;
        TileResearchTable table = (TileResearchTable) world.getTileEntity(x, y, z);
        return table == null ? null : new GuiResearchTable(player, table);
    }
}
