package com.github.postyizhan.betterquesting.api.registry;

import com.github.postyizhan.betterquesting.api.util.ResourceKey;
import java.util.List;

public interface IRegistry<T extends IFactory<E>, E> {
    void register(T factory);

    T getFactory(ResourceKey idName);

    E createNew(ResourceKey idName);

    List<T> getAll();
}
