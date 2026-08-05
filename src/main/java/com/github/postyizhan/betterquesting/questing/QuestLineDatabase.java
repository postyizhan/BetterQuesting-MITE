package com.github.postyizhan.betterquesting.questing;

import com.github.postyizhan.betterquesting.api.questing.IQuestLine;
import com.github.postyizhan.betterquesting.api.questing.IQuestLineDatabase;
import com.github.postyizhan.betterquesting.api.storage.UuidDatabase;
import com.github.postyizhan.betterquesting.api.util.NbtCompat;
import com.github.postyizhan.betterquesting.api.util.NbtUuid.UuidValueType;
import com.github.postyizhan.betterquesting.api.util.UuidConverter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.NBTBase;
import net.minecraft.NBTTagCompound;
import net.minecraft.NBTTagList;

/**
 * Quest-line registry. Upstream null-value warning logs are omitted until the configuration and logging layer is
 * available; null lines are still skipped during persistence.
 */
public class QuestLineDatabase extends UuidDatabase<IQuestLine> implements IQuestLineDatabase {
    public static final QuestLineDatabase INSTANCE = new QuestLineDatabase();

    // This display-order cache intentionally is not kept perfectly synchronized with every inherited map mutation.
    protected final List<UUID> lineOrder = new ArrayList<>();
    private final QuestLineSorter sorter = new QuestLineSorter(this);

    @Override
    public IQuestLine createNew(UUID lineId) {
        IQuestLine line = new QuestLine();
        put(lineId, line);
        return line;
    }

    @Override
    public void removeQuest(UUID questId) {
        // Null lines are a supported map state here, so guard the dereference. Upstream lacks this guard and
        // throws NullPointerException whenever a null line is present.
        values().forEach(line -> {
            if (line != null) {
                line.remove(questId);
            }
        });
    }

    @Override
    public synchronized int getOrderIndex(UUID lineId) {
        if (!containsKey(lineId)) {
            return -1;
        }
        int order = lineOrder.indexOf(lineId);
        if (order >= 0) {
            return order;
        }
        // Upstream deliberately appends known lines lazily when no explicit display order exists.
        lineOrder.add(lineId);
        return lineOrder.size() - 1;
    }

    @Override
    public void setOrderIndex(UUID lineId, int index) {
        lineOrder.remove(lineId);
        lineOrder.add(Math.min(Math.max(index, 0), lineOrder.size()), lineId);
    }

    @Override
    public synchronized List<Map.Entry<UUID, IQuestLine>> getOrderedEntries() {
        return entrySet().stream().sorted(sorter).collect(Collectors.toCollection(ArrayList::new));
    }

    @Override
    public void setOrderedEntries(Collection<Map.Entry<UUID, IQuestLine>> entries) {
        clear();
        for (Map.Entry<UUID, IQuestLine> entry : entries) {
            put(entry.getKey(), entry.getValue());
            lineOrder.add(entry.getKey());
        }
    }

    @Override
    public synchronized void clear() {
        super.clear();
        lineOrder.clear();
    }

    @Override
    public NBTTagList writeToNBT(NBTTagList nbt, List<UUID> subset) {
        orderedEntries().forEach(entry -> {
            if (subset != null && !subset.contains(entry.getKey())) {
                return;
            }
            if (entry.getValue() == null) {
                return;
            }
            NBTTagCompound line = entry.getValue().writeToNBT(new NBTTagCompound(), null);
            UuidValueType.QUEST_LINE.writeId(entry.getKey(), line);
            line.setInteger("order", getOrderIndex(entry.getKey()));
            nbt.appendTag(line);
        });
        return nbt;
    }

    @Override
    public synchronized void readFromNBT(NBTTagList nbt, boolean merge) {
        if (!merge) {
            clear();
        }
        List<IQuestLine> unassigned = new ArrayList<>();
        SortedMap<Integer, UUID> orderMap = new TreeMap<>();

        for (int i = 0; i < nbt.tagCount(); i++) {
            NBTBase item = nbt.tagAt(i);
            if (item.getId() != 10) {
                continue;
            }
            NBTTagCompound serializedLine = (NBTTagCompound) item;
            Optional<UUID> currentId = UuidValueType.QUEST_LINE.tryReadId(serializedLine);
            UUID lineId = null;
            if (currentId.isPresent()) {
                lineId = currentId.get();
            } else if (NbtCompat.isNumeric(serializedLine, "lineID")) {
                lineId = UuidConverter.convertLegacyId(serializedLine.getInteger("lineID"));
            }
            int order = NbtCompat.isNumeric(serializedLine, "order") ? serializedLine.getInteger("order") : -1;

            IQuestLine line = getOrDefault(lineId, new QuestLine());
            line.readFromNBT(serializedLine, merge);
            if (lineId != null) {
                put(lineId, line);
                if (order >= 0) {
                    // Unlike upstream, never cache null: it breaks later order lookups and cannot identify a line.
                    orderMap.put(order, lineId);
                }
            } else {
                unassigned.add(line);
            }
        }

        for (IQuestLine line : unassigned) {
            put(generateKey(), line);
        }
        lineOrder.clear();
        lineOrder.addAll(orderMap.values());
    }

}
