package com.github.postyizhan.betterquesting.api.properties.basic;

import com.github.postyizhan.betterquesting.api.util.ResourceKey;
import net.minecraft.NBTBase;
import net.minecraft.NBTTagByte;

public class PropertyTypeBoolean extends PropertyTypeBase<Boolean> {
    public PropertyTypeBoolean(ResourceKey key, Boolean defaultValue) {
        super(key, defaultValue);
    }

    @Override
    public Boolean readValue(NBTBase nbt) {
        if (!NbtNumbers.isNumeric(nbt)) {
            return getDefault();
        }
        try {
            return NbtNumbers.readAsByte(nbt) > 0;
        } catch (Exception ignored) {
            return getDefault();
        }
    }

    @Override
    public NBTBase writeValue(Boolean value) {
        boolean actual = value == null ? getDefault() : value;
        // NBTTagCompound.setTag overwrites this placeholder name with the property path.
        return new NBTTagByte("", actual ? (byte) 1 : (byte) 0);
    }
}
