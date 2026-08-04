package com.github.postyizhan.betterquesting.api.questing;

import com.github.postyizhan.betterquesting.api.storage.INBTSaveLoad;
import net.minecraft.NBTTagCompound;

public interface IQuestLineEntry extends INBTSaveLoad<NBTTagCompound> {
    @Deprecated
    int getSize();

    int getSizeX();

    int getSizeY();

    int getPosX();

    int getPosY();

    void setPosition(int posX, int posY);

    @Deprecated
    void setSize(int size);

    void setSize(int sizeX, int sizeY);
}
