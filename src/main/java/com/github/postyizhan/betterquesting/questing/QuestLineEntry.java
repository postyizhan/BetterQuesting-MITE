package com.github.postyizhan.betterquesting.questing;

import com.github.postyizhan.betterquesting.api.util.NbtCompat;
import com.github.postyizhan.betterquesting.api.questing.IQuestLineEntry;
import net.minecraft.NBTTagCompound;

public class QuestLineEntry implements IQuestLineEntry {
    private int sizeX;
    private int sizeY;
    private int posX;
    private int posY;

    public QuestLineEntry(NBTTagCompound nbt) {
        readFromNBT(nbt);
    }

    public QuestLineEntry(int x, int y) {
        this(x, y, 24, 24);
    }

    @Deprecated
    public QuestLineEntry(int x, int y, int size) {
        this(x, y, size, size);
    }

    public QuestLineEntry(int x, int y, int sizeX, int sizeY) {
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.posX = x;
        this.posY = y;
    }

    @Override
    @Deprecated
    public int getSize() {
        return Math.max(getSizeX(), getSizeY());
    }

    @Override
    public int getSizeX() {
        return sizeX;
    }

    @Override
    public int getSizeY() {
        return sizeY;
    }

    @Override
    public int getPosX() {
        return posX;
    }

    @Override
    public int getPosY() {
        return posY;
    }

    @Override
    public void setPosition(int posX, int posY) {
        this.posX = posX;
        this.posY = posY;
    }

    @Override
    @Deprecated
    public void setSize(int size) {
        sizeX = size;
        sizeY = size;
    }

    @Override
    public void setSize(int sizeX, int sizeY) {
        this.sizeX = sizeX;
        this.sizeY = sizeY;
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        nbt.setInteger("sizeX", sizeX);
        nbt.setInteger("sizeY", sizeY);
        nbt.setInteger("x", posX);
        nbt.setInteger("y", posY);
        return nbt;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        if (NbtCompat.isNumeric(nbt, "size")) {
            sizeX = nbt.getInteger("size");
            sizeY = sizeX;
        } else {
            sizeX = nbt.getInteger("sizeX");
            sizeY = nbt.getInteger("sizeY");
        }
        posX = nbt.getInteger("x");
        posY = nbt.getInteger("y");
    }


    @Override
    public String toString() {
        return "QuestLineEntry{" + "sizeX=" + sizeX + ", sizeY=" + sizeY + ", posX=" + posX + ", posY=" + posY + '}';
    }
}
