package com.github.postyizhan.betterquesting.questing.party;

import com.github.postyizhan.betterquesting.api.enums.EnumPartyStatus;
import com.github.postyizhan.betterquesting.api.properties.NativeProps;
import com.github.postyizhan.betterquesting.api.questing.party.IParty;
import com.github.postyizhan.betterquesting.api.questing.party.IPartyDatabase;
import com.github.postyizhan.betterquesting.api.storage.DBEntry;
import com.github.postyizhan.betterquesting.api.storage.SimpleDatabase;
import com.github.postyizhan.betterquesting.api.util.NbtCompat;
import com.github.postyizhan.betterquesting.storage.QuestSettings;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import net.minecraft.NBTTagCompound;
import net.minecraft.NBTTagList;

/**
 * Party persistence and membership lookup.
 *
 * <p>Upstream {@code SyncPartyQuests} overloads are deferred to stage 7. They must not be copied with
 * their raw background thread, because quest/database mutations must instead be scheduled on the server
 * main thread or dispatched through EventBridge.</p>
 */
public class PartyManager extends SimpleDatabase<IParty> implements IPartyDatabase {
    public static final PartyManager INSTANCE = new PartyManager();

    private final HashMap<UUID, Integer> partyCache = new HashMap<>();

    @Override
    public synchronized IParty createNew(int id) {
        IParty party = new PartyInstance();
        if (id >= 0) {
            add(id, party);
        }
        return party;
    }

    @Override
    public synchronized DBEntry<IParty> getParty(UUID uuid) {
        // Disabling parties prevents access without erasing persisted party data.
        if (!QuestSettings.INSTANCE.getProperty(NativeProps.PARTY_ENABLE)) {
            return null;
        }

        Integer cachedId = partyCache.get(uuid);
        IParty cachedParty = cachedId == null ? null : getValue(cachedId);
        if (cachedId != null && cachedParty == null) {
            partyCache.remove(uuid);
        } else if (cachedParty != null) {
            EnumPartyStatus status = cachedParty.getStatus(uuid);
            if (status != null) {
                return new DBEntry<>(cachedId, cachedParty);
            }
            partyCache.remove(uuid);
        }

        for (DBEntry<IParty> entry : getEntries()) {
            if (entry.getValue().getStatus(uuid) != null) {
                partyCache.put(uuid, entry.getID());
                return entry;
            }
        }
        return null;
    }

    @Override
    public NBTTagList writeToNBT(NBTTagList nbt, List<Integer> subset) {
        for (DBEntry<IParty> entry : getEntries()) {
            if (subset != null && !subset.contains(entry.getID())) {
                continue;
            }
            NBTTagCompound partyTag = entry.getValue().writeToNBT(new NBTTagCompound());
            partyTag.setInteger("partyID", entry.getID());
            nbt.appendTag(partyTag);
        }
        return nbt;
    }

    @Override
    public void readFromNBT(NBTTagList nbt, boolean merge) {
        if (!merge) {
            reset();
        }

        for (int i = 0; i < nbt.tagCount(); i++) {
            NBTTagCompound partyTag = NbtCompat.getCompoundAt(nbt, i);
            if (partyTag == null) {
                continue;
            }
            int partyId = NbtCompat.isNumeric(partyTag, "partyID") ? partyTag.getInteger("partyID") : -1;
            if (partyId < 0) {
                continue;
            }

            IParty party = new PartyInstance();
            party.readFromNBT(partyTag);
            if (!party.getMembers().isEmpty()) {
                add(partyId, party);
            }
        }
    }

    @Override
    public synchronized void reset() {
        super.reset();
        partyCache.clear();
    }
}
