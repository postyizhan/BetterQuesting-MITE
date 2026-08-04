package com.github.postyizhan.betterquesting.questing;

import com.github.postyizhan.betterquesting.api.questing.IQuestLine;
import com.github.postyizhan.betterquesting.api.questing.IQuestLineDatabase;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;

public final class QuestLineSorter implements Comparator<Map.Entry<UUID, IQuestLine>> {
    private final IQuestLineDatabase database;

    public QuestLineSorter(IQuestLineDatabase database) {
        this.database = database;
    }

    @Override
    public int compare(Map.Entry<UUID, IQuestLine> left, Map.Entry<UUID, IQuestLine> right) {
        return Integer.compare(database.getOrderIndex(left.getKey()), database.getOrderIndex(right.getKey()));
    }
}
