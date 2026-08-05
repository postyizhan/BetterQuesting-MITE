package com.github.postyizhan.betterquesting.questing;

import com.github.postyizhan.betterquesting.api.enums.EnumQuestVisibility;
import com.github.postyizhan.betterquesting.api.properties.IPropertyContainer;
import com.github.postyizhan.betterquesting.api.properties.IPropertyType;
import com.github.postyizhan.betterquesting.api.properties.NativeProps;
import com.github.postyizhan.betterquesting.api.questing.IQuestLine;
import com.github.postyizhan.betterquesting.api.questing.IQuestLineEntry;
import com.github.postyizhan.betterquesting.api.storage.UuidDatabase;
import com.github.postyizhan.betterquesting.api.util.NbtCompat;
import com.github.postyizhan.betterquesting.api.util.NbtUuid.UuidValueType;
import com.github.postyizhan.betterquesting.api.util.UuidConverter;
import com.github.postyizhan.betterquesting.storage.PropertyContainer;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.NBTBase;
import net.minecraft.NBTTagCompound;
import net.minecraft.NBTTagList;

/**
 * Quest-line data and layout. The upstream parent database field is intentionally omitted because it is assigned but
 * never read and would pull the unported API service locator into this data layer.
 */
public class QuestLine extends UuidDatabase<IQuestLineEntry> implements IQuestLine {
    private final IPropertyContainer info = new PropertyContainer();

    public QuestLine() {
        setupProps();
    }

    private void setupProps() {
        setupValue(NativeProps.NAME, "New Quest Line");
        setupValue(NativeProps.DESC, "No Description");
        // TODO: Restore NativeProps.ICON when BigItemStack and its property type are ported.
        setupValue(NativeProps.VISIBILITY, EnumQuestVisibility.NORMAL);
        setupValue(NativeProps.BG_IMAGE);
        setupValue(NativeProps.BG_SIZE);
    }

    private <T> void setupValue(IPropertyType<T> prop) {
        setupValue(prop, prop.getDefault());
    }

    private <T> void setupValue(IPropertyType<T> prop, T defaultValue) {
        info.setProperty(prop, info.getProperty(prop, defaultValue));
    }

    @Override
    public IQuestLineEntry createNew(UUID uuid) {
        IQuestLineEntry entry = new QuestLineEntry(0, 0, 24, 24);
        put(uuid, entry);
        return entry;
    }

    @Override
    public String getUnlocalisedName() {
        String defaultValue = "New Quest Line";
        if (!info.hasProperty(NativeProps.NAME)) {
            info.setProperty(NativeProps.NAME, defaultValue);
            return defaultValue;
        }
        return info.getProperty(NativeProps.NAME, defaultValue);
    }

    @Override
    public String getUnlocalisedDescription() {
        String defaultValue = "No Description";
        if (!info.hasProperty(NativeProps.DESC)) {
            info.setProperty(NativeProps.DESC, defaultValue);
            return defaultValue;
        }
        return info.getProperty(NativeProps.DESC, defaultValue);
    }

    @Override
    public Map.Entry<UUID, IQuestLineEntry> getEntryAt(int x, int y) {
        for (Map.Entry<UUID, IQuestLineEntry> entry : entrySet()) {
            IQuestLineEntry value = entry.getValue();
            int minX = value.getPosX();
            int minY = value.getPosY();
            if (x >= minX && x < minX + value.getSizeX() && y >= minY && y < minY + value.getSizeY()) {
                return entry;
            }
        }
        return null;
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        return writeToNBT(nbt, false);
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        readFromNBT(nbt, false);
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt, List<Integer> subset) {
        if (subset != null) {
            throw new UnsupportedOperationException("subset not supported");
        }
        return writeToNBT(nbt, false);
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt, boolean skipQuests) {
        nbt.setTag("properties", info.writeToNBT(new NBTTagCompound()));
        if (!skipQuests) {
            NBTTagList quests = new NBTTagList();
            orderedEntries().forEach(entry -> {
                NBTTagCompound quest = entry.getValue().writeToNBT(new NBTTagCompound());
                UuidValueType.QUEST.writeId(entry.getKey(), quest);
                quests.appendTag(quest);
            });
            nbt.setTag("quests", quests);
        }
        return nbt;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt, boolean merge) {
        info.readFromNBT(nbt.getCompoundTag("properties"));
        if (!merge) {
            clear();
        }

        // MITE throws when getTagList sees a present non-list tag; upstream treats that case as empty.
        if (nbt.hasKey("quests") && nbt.getTag("quests").getId() == 9) {
            NBTTagList quests = nbt.getTagList("quests");
            for (int i = 0; i < quests.tagCount(); i++) {
                NBTBase item = quests.tagAt(i);
                if (item.getId() != 10) {
                    continue;
                }
                NBTTagCompound quest = (NBTTagCompound) item;
                Optional<UUID> currentId = UuidValueType.QUEST.tryReadId(quest);
                UUID questId;
                if (currentId.isPresent()) {
                    questId = currentId.get();
                } else if (NbtCompat.isNumeric(quest, "id")) {
                    questId = UuidConverter.convertLegacyId(quest.getInteger("id"));
                } else {
                    continue;
                }
                put(questId, new QuestLineEntry(quest));
            }
        }
        setupProps();
    }


    @Override
    public <T> T getProperty(IPropertyType<T> prop) {
        return info.getProperty(prop);
    }

    @Override
    public <T> T getProperty(IPropertyType<T> prop, T defaultValue) {
        return info.getProperty(prop, defaultValue);
    }

    @Override
    public boolean hasProperty(IPropertyType<?> prop) {
        return info.hasProperty(prop);
    }

    @Override
    public void removeProperty(IPropertyType<?> prop) {
        info.removeProperty(prop);
    }

    @Override
    public <T> void setProperty(IPropertyType<T> prop, T value) {
        info.setProperty(prop, value);
    }

    @Override
    public void removeAllProps() {
        info.removeAllProps();
    }

}
