package com.github.postyizhan.betterquesting.api.registry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import net.minecraft.ResourceLocation;

public class SimpleRegistry<T extends IFactory<E>, E> implements IRegistry<T, E> {
    private final HashMap<ResourceLocation, T> factories = new HashMap<>();

    @Override
    public void register(T factory) {
        if (factory == null || factory.getRegistryName() == null) {
            throw new NullPointerException("Factory or registry name is null!");
        } else if (factories.containsKey(factory.getRegistryName()) || factories.containsValue(factory)) {
            throw new IllegalArgumentException("Cannot register duplicate factory or registry name");
        }

        factories.put(factory.getRegistryName(), factory);
    }

    @Override
    public T getFactory(ResourceLocation idName) {
        return factories.get(idName);
    }

    @Override
    public E createNew(ResourceLocation idName) {
        IFactory<E> factory = getFactory(idName);
        return factory == null ? null : factory.createNew();
    }

    @Override
    public List<T> getAll() {
        return new ArrayList<>(factories.values());
    }
}
