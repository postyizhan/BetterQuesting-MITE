package com.github.postyizhan.betterquesting.storage;

import com.github.postyizhan.betterquesting.api.properties.IPropertyContainer;
import com.github.postyizhan.betterquesting.api.properties.IPropertyType;
import com.github.postyizhan.betterquesting.api.util.ResourceKey;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.NBTBase;
import net.minecraft.NBTTagCompound;

public class PropertyContainer implements IPropertyContainer {
    private final NBTTagCompound nbtInfo = new NBTTagCompound();

    @Override
    public <T> T getProperty(IPropertyType<T> prop) {
        return prop == null ? null : getProperty(prop, prop.getDefault());
    }

    @Override
    public <T> T getProperty(IPropertyType<T> prop, T defaultValue) {
        if (prop == null) {
            return defaultValue;
        }
        synchronized (nbtInfo) {
            NBTTagCompound domain = getDomain(prop.getKey());
            if (!domain.hasKey(prop.getKey().getResourcePath())) {
                return defaultValue;
            }
            return prop.readValue(domain.getTag(prop.getKey().getResourcePath()));
        }
    }

    @Override
    public boolean hasProperty(IPropertyType<?> prop) {
        if (prop == null) {
            return false;
        }
        synchronized (nbtInfo) {
            return getDomain(prop.getKey()).hasKey(prop.getKey().getResourcePath());
        }
    }

    @Override
    public void removeProperty(IPropertyType<?> prop) {
        if (prop == null) {
            return;
        }
        synchronized (nbtInfo) {
            NBTTagCompound domain = getDomain(prop.getKey());
            if (!domain.hasKey(prop.getKey().getResourcePath())) {
                return;
            }
            domain.removeTag(prop.getKey().getResourcePath());
            if (domain.hasNoTags()) {
                nbtInfo.removeTag(prop.getKey().getResourceDomain());
            }
        }
    }

    @Override
    public <T> void setProperty(IPropertyType<T> prop, T value) {
        if (prop == null || value == null) {
            return;
        }
        synchronized (nbtInfo) {
            NBTTagCompound domain = getDomain(prop.getKey());
            domain.setTag(prop.getKey().getResourcePath(), prop.writeValue(value));
            // Missing getCompoundTag keys yield detached compounds, so the domain must always be written back.
            nbtInfo.setTag(prop.getKey().getResourceDomain(), domain);
        }
    }

    @Override
    public void removeAllProps() {
        synchronized (nbtInfo) {
            List<String> keys = new ArrayList<>();
            for (Object value : nbtInfo.getTags()) {
                keys.add(((NBTBase) value).getName());
            }
            for (String key : keys) {
                nbtInfo.removeTag(key);
            }
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        synchronized (nbtInfo) {
            merge(nbt, nbtInfo);
            return nbt;
        }
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        synchronized (nbtInfo) {
            removeAllProps();
            merge(nbtInfo, nbt);
        }
    }

    private NBTTagCompound getDomain(ResourceKey key) {
        return nbtInfo.getCompoundTag(key.getResourceDomain());
    }

    private void merge(NBTTagCompound parent, NBTTagCompound other) {
        for (Object value : other.getTags()) {
            NBTBase tag = (NBTBase) value;
            String name = tag.getName();
            if (tag.getId() == 10 && parent.hasKey(name) && parent.getTag(name).getId() == 10) {
                merge(parent.getCompoundTag(name), (NBTTagCompound) tag);
            } else {
                parent.setTag(name, tag.copy());
            }
        }
    }
}
