package com.github.postyizhan.betterquesting.api.registry;

import net.minecraft.ResourceLocation;

public interface IFactory<T> {
    ResourceLocation getRegistryName();

    T createNew();
}
