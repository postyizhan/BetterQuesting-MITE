package com.github.postyizhan.betterquesting.api.questing.party;

import com.github.postyizhan.betterquesting.api.enums.EnumPartyStatus;
import com.github.postyizhan.betterquesting.api.properties.IPropertyContainer;
import com.github.postyizhan.betterquesting.api.storage.INBTSaveLoad;
import java.util.List;
import java.util.UUID;
import net.minecraft.NBTTagCompound;

public interface IParty extends INBTSaveLoad<NBTTagCompound> {
    IPropertyContainer getProperties();

    void kickUser(UUID uuid);

    void setStatus(UUID uuid, EnumPartyStatus status);

    EnumPartyStatus getStatus(UUID uuid);

    List<UUID> getMembers();

    NBTTagCompound writeProperties(NBTTagCompound nbt);

    void readProperties(NBTTagCompound nbt);
}
