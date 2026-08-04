package com.github.postyizhan.betterquesting.api.registry;

@Deprecated
public interface IFactoryData<T, E> extends IFactory<T> {
    T loadFromData(E data);
}
