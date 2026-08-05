package com.github.postyizhan.betterquesting.questing.party;

import com.github.postyizhan.betterquesting.api.enums.EnumPartyStatus;
import com.github.postyizhan.betterquesting.api.properties.IPropertyContainer;
import com.github.postyizhan.betterquesting.api.properties.IPropertyType;
import com.github.postyizhan.betterquesting.api.properties.NativeProps;
import com.github.postyizhan.betterquesting.api.questing.party.IParty;
import com.github.postyizhan.betterquesting.api.util.NbtCompat;
import com.github.postyizhan.betterquesting.storage.PropertyContainer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.NBTTagCompound;
import net.minecraft.NBTTagList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class PartyInstance implements IParty {
    private static final Logger LOGGER = LogManager.getLogger("BetterQuesting/PartyInstance");

    private final HashMap<UUID, EnumPartyStatus> members = new HashMap<>();
    private List<UUID> memCache;
    private final PropertyContainer pInfo = new PropertyContainer();

    public PartyInstance() {
        setupProps();
    }

    private void setupProps() {
        setupValue(NativeProps.NAME, "New Party");
    }

    private <T> void setupValue(IPropertyType<T> prop, T defaultValue) {
        pInfo.setProperty(prop, pInfo.getProperty(prop, defaultValue));
    }

    private void refreshCache() {
        memCache = Collections.unmodifiableList(new ArrayList<>(members.keySet()));
    }

    @Override
    public IPropertyContainer getProperties() {
        return pInfo;
    }

    @Override
    public void kickUser(UUID uuid) {
        if (!members.containsKey(uuid)) {
            return;
        }

        EnumPartyStatus old = members.remove(uuid);
        if (old == EnumPartyStatus.OWNER && !members.isEmpty()) {
            hostMigrate();
        }
        refreshCache();
    }

    @Override
    public void setStatus(UUID uuid, EnumPartyStatus status) {
        EnumPartyStatus old = members.get(uuid);
        if (old == status) {
            return;
        }

        members.put(uuid, status);
        if (status == EnumPartyStatus.OWNER) {
            // Upstream iterates the cached member list while mutating the map. Keep an explicit snapshot so
            // correctness cannot silently become dependent on getMembers() continuing to return a copy.
            for (UUID member : new ArrayList<>(members.keySet())) {
                // Upstream uses reference inequality here; network/NBT UUID instances require value equality.
                if (!member.equals(uuid) && members.get(member) == EnumPartyStatus.OWNER) {
                    members.put(member, EnumPartyStatus.ADMIN);
                }
            }
        } else if (old == EnumPartyStatus.OWNER) {
            UUID migrate = null;
            // This is also a mutation-bearing role transition, so candidate selection uses a stable snapshot.
            for (UUID member : new ArrayList<>(members.keySet())) {
                if (member.equals(uuid)) {
                    continue;
                }
                if (members.get(member) == EnumPartyStatus.ADMIN) {
                    migrate = member;
                    break;
                } else if (migrate == null) {
                    migrate = member;
                }
            }

            if (migrate == null) {
                members.put(uuid, old);
                return;
            }
            members.put(migrate, EnumPartyStatus.OWNER);
        }

        refreshCache();
    }

    @Override
    public EnumPartyStatus getStatus(UUID uuid) {
        return members.get(uuid);
    }

    @Override
    public List<UUID> getMembers() {
        if (memCache == null) {
            refreshCache();
        }
        return memCache;
    }

    private void hostMigrate() {
        for (UUID uuid : members.keySet()) {
            if (members.get(uuid) == EnumPartyStatus.OWNER) {
                return;
            }
        }

        UUID migrate = null;
        for (UUID member : new ArrayList<>(members.keySet())) {
            EnumPartyStatus status = members.get(member);
            if (status == EnumPartyStatus.ADMIN || status == EnumPartyStatus.OWNER) {
                migrate = member;
                break;
            } else if (migrate == null) {
                migrate = member;
            }
        }

        if (migrate != null) {
            members.put(migrate, EnumPartyStatus.OWNER);
        } else {
            LOGGER.error("Failed to find suitable host to migrate party {}", pInfo.getProperty(NativeProps.NAME));
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        NBTTagList memberList = new NBTTagList();
        for (Map.Entry<UUID, EnumPartyStatus> member : members.entrySet()) {
            NBTTagCompound memberTag = new NBTTagCompound();
            memberTag.setString("uuid", member.getKey().toString());
            memberTag.setString("status", member.getValue().toString());
            memberList.appendTag(memberTag);
        }
        nbt.setTag("members", memberList);
        nbt.setTag("properties", pInfo.writeToNBT(new NBTTagCompound()));
        return nbt;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        if (NbtCompat.getTagId(nbt, "properties") == 10) {
            pInfo.readFromNBT(nbt.getCompoundTag("properties"));
        } else {
            pInfo.readFromNBT(new NBTTagCompound());
            pInfo.setProperty(NativeProps.NAME, nbt.getString("name"));
        }

        members.clear();
        NBTTagList memberList = NbtCompat.getListOrEmpty(nbt, "members");
        for (int i = 0; i < memberList.tagCount(); i++) {
            try {
                NBTTagCompound memberTag = NbtCompat.getCompoundAt(memberList, i);
                if (memberTag == null || NbtCompat.getTagId(memberTag, "uuid") != 8
                    || !memberTag.hasKey("status")) {
                    continue;
                }
                UUID uuid = UUID.fromString(memberTag.getString("uuid"));
                EnumPartyStatus status = EnumPartyStatus.valueOf(memberTag.getString("status"));
                members.put(uuid, status);
            } catch (Exception ignored) {
                // A malformed member must not prevent later valid entries from loading.
            }
        }

        refreshCache();
        setupProps();
    }

    @Override
    public NBTTagCompound writeProperties(NBTTagCompound nbt) {
        return pInfo.writeToNBT(nbt);
    }

    @Override
    public void readProperties(NBTTagCompound nbt) {
        pInfo.readFromNBT(nbt);
    }
}
