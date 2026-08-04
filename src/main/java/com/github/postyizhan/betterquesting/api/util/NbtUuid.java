package com.github.postyizhan.betterquesting.api.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.NBTBase;
import net.minecraft.NBTTagCompound;
import net.minecraft.NBTTagList;

public final class NbtUuid {
    private NbtUuid() {
    }

    public enum UuidValueType {
        QUEST("questID"),
        QUEST_LINE("questLineID");

        private final String idFieldName;
        private final String highIdFieldName;
        private final String lowIdFieldName;

        UuidValueType(String idFieldName) {
            this.idFieldName = idFieldName;
            this.highIdFieldName = idFieldName + "High";
            this.lowIdFieldName = idFieldName + "Low";
        }

        public NBTTagCompound writeId(UUID uuid) {
            NBTTagCompound tag = new NBTTagCompound();
            writeId(uuid, tag);
            return tag;
        }

        public void writeId(UUID uuid, NBTTagCompound tag) {
            tag.setLong(highIdFieldName, uuid.getMostSignificantBits());
            tag.setLong(lowIdFieldName, uuid.getLeastSignificantBits());
        }

        public void tryWriteId(UUID uuid, NBTTagCompound tag) {
            if (uuid != null) {
                writeId(uuid, tag);
            }
        }

        public void writeIdString(UUID uuid, NBTTagCompound tag) {
            tag.setString(idFieldName, uuid == null ? "" : UuidConverter.encodeUuid(uuid));
        }

        public NBTTagList writeIds(Collection<UUID> uuids) {
            NBTTagList tagList = new NBTTagList();
            for (UUID uuid : uuids) {
                tagList.appendTag(writeId(uuid));
            }
            return tagList;
        }

        public Optional<UUID> tryReadId(NBTTagCompound tag) {
            if (isNumeric(tag, highIdFieldName) && isNumeric(tag, lowIdFieldName)) {
                return Optional.of(readId(tag));
            }
            return Optional.empty();
        }

        public UUID readId(NBTTagCompound tag) {
            return new UUID(tag.getLong(highIdFieldName), tag.getLong(lowIdFieldName));
        }

        public Optional<UUID> tryReadIdString(NBTTagCompound tag) {
            if (!tag.hasKey(idFieldName) || tag.getTag(idFieldName).getId() != 8) {
                return Optional.empty();
            }
            String id = tag.getString(idFieldName);
            if (id.isEmpty()) {
                return Optional.empty();
            }
            if (id.length() > 24) {
                return Optional.of(UUID.fromString(id));
            }
            return Optional.of(UuidConverter.decodeUuid(id));
        }

        public List<UUID> readIds(NBTTagCompound tag, String key) {
            // MITE throws when getTagList sees a present non-list tag; upstream treats that case as empty.
            if (!tag.hasKey(key) || tag.getTag(key).getId() != 9) {
                return new ArrayList<>();
            }
            return readIds(tag.getTagList(key));
        }

        public List<UUID> readIds(NBTTagList tagList) {
            List<UUID> result = new ArrayList<>();
            for (int i = 0; i < tagList.tagCount(); i++) {
                NBTBase item = tagList.tagAt(i);
                if (item.getId() == 10) {
                    result.add(readId((NBTTagCompound) item));
                }
            }
            return result;
        }

        private static boolean isNumeric(NBTTagCompound tag, String key) {
            if (!tag.hasKey(key)) {
                return false;
            }
            int id = tag.getTag(key).getId();
            return id >= 1 && id <= 6;
        }
    }
}
