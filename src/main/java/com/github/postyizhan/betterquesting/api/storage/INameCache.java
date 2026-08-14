package com.github.postyizhan.betterquesting.api.storage;

import java.util.List;
import java.util.UUID;
import net.minecraft.NBTTagList;

public interface INameCache extends INBTPartial<NBTTagList, UUID> {
    /** Updates persisted metadata supplied by a caller without asserting how the identity was verified. */
    boolean updateName(UUID uuid, String name, boolean operator);

    String getName(UUID uuid);

    /** Returns null for an absent or ambiguous case-insensitive name. */
    UUID getUUID(String name);

    List<String> getAllNames();

    boolean isOP(UUID uuid);

    int size();

    void reset();
}
