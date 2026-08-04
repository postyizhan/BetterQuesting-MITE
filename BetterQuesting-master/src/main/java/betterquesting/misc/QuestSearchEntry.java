package betterquesting.misc;

import java.util.Map;
import java.util.UUID;

import betterquesting.api.questing.IQuest;
import betterquesting.api.questing.IQuestLine;

public class QuestSearchEntry {

    public QuestSearchEntry(Map.Entry<UUID, IQuest> quest, Map.Entry<UUID, IQuestLine> questLineEntry) {
        this.quest = quest;
        this.questLineEntry = questLineEntry;
    }

    private Map.Entry<UUID, IQuest> quest;

    public Map.Entry<UUID, IQuest> getQuest() {
        return quest;
    }

    public void setQuest(Map.Entry<UUID, IQuest> quest) {
        this.quest = quest;
    }

    public Map.Entry<UUID, IQuestLine> getQuestLineEntry() {
        return questLineEntry;
    }

    public void setQuestLineEntry(Map.Entry<UUID, IQuestLine> questLineEntry) {
        this.questLineEntry = questLineEntry;
    }

    private Map.Entry<UUID, IQuestLine> questLineEntry;
}
