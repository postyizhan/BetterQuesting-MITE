package com.github.postyizhan.betterquesting.storage;

import com.github.postyizhan.betterquesting.api.properties.NativeProps;
import com.github.postyizhan.betterquesting.api.storage.ILifeDatabase;
import com.github.postyizhan.betterquesting.api.util.NbtCompat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.NBTTagCompound;
import net.minecraft.NBTTagList;

public final class LifeDatabase implements ILifeDatabase {
    public static final LifeDatabase INSTANCE = new LifeDatabase();

    private final Map<UUID, Integer> playerLives = new LinkedHashMap<>();

    @Override
    public synchronized int getLives(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");
        Integer lives = playerLives.get(uuid);
        if (lives == null) {
            lives = QuestSettings.INSTANCE.getProperty(NativeProps.LIVES_DEF);
            playerLives.put(uuid, lives);
        }
        return lives;
    }

    @Override
    public synchronized void setLives(UUID uuid, int value) {
        Objects.requireNonNull(uuid, "uuid");
        int maximum = QuestSettings.INSTANCE.getProperty(NativeProps.LIVES_MAX);
        playerLives.put(uuid, value < 0 ? 0 : Math.min(value, maximum));
    }

    @Override
    public synchronized NBTTagCompound writeToNBT(NBTTagCompound nbt, List<UUID> users) {
        NBTTagList serialized = new NBTTagList();
        List<Map.Entry<UUID, Integer>> entries = new ArrayList<>(playerLives.entrySet());
        entries.sort(Map.Entry.comparingByKey(Comparator.comparing(UUID::toString)));
        for (Map.Entry<UUID, Integer> entry : entries) {
            if (users != null && !users.contains(entry.getKey())) continue;
            NBTTagCompound player = new NBTTagCompound();
            player.setString("uuid", entry.getKey().toString());
            player.setInteger("lives", entry.getValue());
            serialized.appendTag(player);
        }
        nbt.setTag("playerLives", serialized);
        return nbt;
    }

    @Override
    public synchronized void readFromNBT(NBTTagCompound nbt, boolean merge) {
        if (!merge) playerLives.clear();
        NBTTagList serialized = NbtCompat.getListOrEmpty(nbt, "playerLives");
        for (int index = 0; index < serialized.tagCount(); index++) {
            NBTTagCompound player = NbtCompat.getCompoundAt(serialized, index);
            if (player == null) continue;
            try {
                playerLives.put(UUID.fromString(player.getString("uuid")), player.getInteger("lives"));
            } catch (RuntimeException ignored) {
            }
        }
    }

    @Override
    public synchronized void reset() {
        playerLives.clear();
    }
}
