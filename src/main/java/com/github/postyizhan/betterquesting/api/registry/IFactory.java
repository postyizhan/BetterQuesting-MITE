package com.github.postyizhan.betterquesting.api.registry;

import com.github.postyizhan.betterquesting.api.util.ResourceKey;

public interface IFactory<T> {
    ResourceKey getRegistryName();

    T createNew();
}
