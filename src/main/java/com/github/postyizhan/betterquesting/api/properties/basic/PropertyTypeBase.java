package com.github.postyizhan.betterquesting.api.properties.basic;

import com.github.postyizhan.betterquesting.api.properties.IPropertyType;
import com.github.postyizhan.betterquesting.api.util.ResourceKey;

public abstract class PropertyTypeBase<T> implements IPropertyType<T> {
    private final ResourceKey key;
    private final T defaultValue;

    protected PropertyTypeBase(ResourceKey key, T defaultValue) {
        this.key = key;
        this.defaultValue = defaultValue;
    }

    @Override
    public ResourceKey getKey() {
        return key;
    }

    @Override
    public T getDefault() {
        return defaultValue;
    }
}
