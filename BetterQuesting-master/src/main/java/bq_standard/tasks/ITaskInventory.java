package bq_standard.tasks;

import java.util.Map;
import java.util.UUID;

import javax.annotation.Nonnull;

import betterquesting.api.questing.IQuest;
import betterquesting.api.questing.tasks.ITask;
import betterquesting.api2.utils.ParticipantInfo;

public interface ITaskInventory extends ITask {

    void onInventoryChange(@Nonnull Map.Entry<UUID, IQuest> quest, @Nonnull ParticipantInfo pInfo);
}
