package com.github.postyizhan.betterquesting.api.storage;

import com.github.postyizhan.betterquesting.api.properties.IPropertyContainer;

public interface IQuestSettings extends IPropertyContainer {
    // TODO: Add canUserEdit after the identity, operator cache, and permission layer is ported.
    void reset();
}
