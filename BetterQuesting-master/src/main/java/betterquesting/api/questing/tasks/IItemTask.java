package betterquesting.api.questing.tasks;

import java.util.Map;
import java.util.UUID;

import net.minecraft.item.ItemStack;

import betterquesting.api.questing.IQuest;
import betterquesting.api2.utils.ParticipantInfo;

public interface IItemTask extends ITask {

    boolean canAcceptItem(UUID owner, Map.Entry<UUID, IQuest> quest, ItemStack stack);

    ItemStack submitItem(UUID owner, Map.Entry<UUID, IQuest> quest, ItemStack stack);

    /**
     * @param items read-only list of items
     */
    default void retrieveItems(ParticipantInfo pInfo, Map.Entry<UUID, IQuest> quest, ItemStack[] items) {}
}
