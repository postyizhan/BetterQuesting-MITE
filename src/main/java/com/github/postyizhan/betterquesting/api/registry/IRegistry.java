package com.github.postyizhan.betterquesting.api.registry;

import java.util.List;
import net.minecraft.ResourceLocation;

public interface IRegistry<T extends IFactory<E>, E> {
    void register(T factory);

    T getFactory(ResourceLocation idName);

    E createNew(ResourceLocation idName);

    List<T> getAll();
}
