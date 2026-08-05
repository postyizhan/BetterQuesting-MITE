package com.github.postyizhan.betterquesting.questing;

import static org.junit.jupiter.api.Assertions.*;

import com.github.postyizhan.betterquesting.api.placeholders.rewards.RewardPlaceholder;
import com.github.postyizhan.betterquesting.api.placeholders.tasks.TaskPlaceholder;
import com.github.postyizhan.betterquesting.api.questing.rewards.IReward;
import com.github.postyizhan.betterquesting.api.questing.tasks.ITask;
import com.github.postyizhan.betterquesting.api.registry.IFactoryData;
import com.github.postyizhan.betterquesting.api.util.ResourceKey;
import com.github.postyizhan.betterquesting.questing.rewards.RewardRegistry;
import com.github.postyizhan.betterquesting.questing.rewards.RewardStorage;
import com.github.postyizhan.betterquesting.questing.tasks.TaskRegistry;
import com.github.postyizhan.betterquesting.questing.tasks.TaskStorage;
import java.util.List;
import java.util.UUID;
import net.minecraft.NBTTagCompound;
import net.minecraft.NBTTagList;
import org.junit.jupiter.api.Test;

class StorageCompatibilityTest {
    private static final ResourceKey TASK_ID = ResourceKey.parse("test:stage2_task");
    private static final ResourceKey REWARD_ID = ResourceKey.parse("test:stage2_reward");

    @Test
    void taskPlaceholderRoundTripsConfigAndProgress() {
        TaskPlaceholder placeholder = new TaskPlaceholder();
        NBTTagCompound config = new NBTTagCompound();
        config.setString("payload", "config");
        NBTTagCompound progress = new NBTTagCompound();
        progress.setInteger("amount", 7);
        placeholder.setTaskConfigData(config);
        placeholder.setTaskProgressData(progress);
        TaskPlaceholder loaded = new TaskPlaceholder();
        loaded.readFromNBT(placeholder.writeToNBT(new NBTTagCompound()));
        loaded.readProgressFromNBT(placeholder.writeProgressToNBT(new NBTTagCompound(), null), false);
        assertEquals("config", loaded.getTaskConfigData().getString("payload"));
        assertEquals(7, loaded.getTaskProgressData().getInteger("amount"));
    }

    @Test
    void rewardPlaceholderRoundTripsOriginalData() {
        RewardPlaceholder placeholder = new RewardPlaceholder();
        NBTTagCompound config = new NBTTagCompound();
        config.setString("payload", "reward");
        placeholder.setRewardConfigData(config);
        RewardPlaceholder loaded = new RewardPlaceholder();
        loaded.readFromNBT(placeholder.writeToNBT(new NBTTagCompound()));
        assertEquals("reward", loaded.getRewardConfigData().getString("payload"));
    }

    @Test
    void unknownFactoriesPreservePayloadAndMissingIndexAppends() {
        NBTTagCompound task = new NBTTagCompound();
        task.setString("taskID", "missing:task");
        task.setString("opaque", "task-data");
        NBTTagList tasks = new NBTTagList();
        tasks.appendTag(task);
        TaskStorage taskStorage = new TaskStorage();
        taskStorage.readFromNBT(tasks, false);
        assertInstanceOf(TaskPlaceholder.class, taskStorage.getValue(0));
        NBTTagCompound savedTask = (NBTTagCompound) taskStorage.writeToNBT(new NBTTagList(), null).tagAt(0);
        assertEquals("task-data", savedTask.getCompoundTag("orig_data").getString("opaque"));
        assertEquals("missing:task", savedTask.getCompoundTag("orig_data").getString("taskID"));

        NBTTagCompound reward = new NBTTagCompound();
        reward.setString("rewardID", "missing:reward");
        reward.setString("opaque", "reward-data");
        NBTTagList rewards = new NBTTagList();
        rewards.appendTag(reward);
        RewardStorage rewardStorage = new RewardStorage();
        rewardStorage.readFromNBT(rewards, false);
        assertInstanceOf(RewardPlaceholder.class, rewardStorage.getValue(0));
        NBTTagCompound savedReward = (NBTTagCompound) rewardStorage.writeToNBT(new NBTTagList(), null).tagAt(0);
        assertEquals("reward-data", savedReward.getCompoundTag("orig_data").getString("opaque"));
        assertEquals("missing:reward", savedReward.getCompoundTag("orig_data").getString("rewardID"));
    }

    @Test
    void explicitIndexLocatesAndPlaceholderRestoresRegisteredTypes() {
        registerFactories();
        NBTTagCompound originalTask = new NBTTagCompound();
        originalTask.setString("taskID", TASK_ID.toString());
        originalTask.setString("value", "restored-task");
        NBTTagCompound taskEnvelope = new NBTTagCompound();
        taskEnvelope.setString("taskID", "betterquesting:placeholder");
        taskEnvelope.setInteger("index", 4);
        taskEnvelope.setTag("orig_data", originalTask);
        NBTTagList tasks = new NBTTagList();
        tasks.appendTag(taskEnvelope);
        TaskStorage taskStorage = new TaskStorage();
        taskStorage.readFromNBT(tasks, false);
        assertEquals("restored-task", ((TestTask) taskStorage.getValue(4)).value);

        NBTTagCompound originalReward = new NBTTagCompound();
        originalReward.setString("rewardID", REWARD_ID.toString());
        originalReward.setString("value", "restored-reward");
        NBTTagCompound rewardEnvelope = new NBTTagCompound();
        rewardEnvelope.setString("rewardID", "betterquesting:placeholder");
        rewardEnvelope.setInteger("index", 3);
        rewardEnvelope.setTag("orig_data", originalReward);
        NBTTagList rewards = new NBTTagList();
        rewards.appendTag(rewardEnvelope);
        RewardStorage rewardStorage = new RewardStorage();
        rewardStorage.readFromNBT(rewards, false);
        assertEquals("restored-reward", ((TestReward) rewardStorage.getValue(3)).value);
    }

    private static void registerFactories() {
        if (TaskRegistry.INSTANCE.getFactory(TASK_ID) == null) TaskRegistry.INSTANCE.register(new TaskFactory());
        if (RewardRegistry.INSTANCE.getFactory(REWARD_ID) == null) RewardRegistry.INSTANCE.register(new RewardFactory());
    }

    private static final class TaskFactory implements IFactoryData<ITask, NBTTagCompound> {
        public ResourceKey getRegistryName() { return TASK_ID; }
        public ITask createNew() { return new TestTask(); }
        public ITask loadFromData(NBTTagCompound data) { TestTask task = new TestTask(); task.readFromNBT(data); return task; }
    }

    private static final class RewardFactory implements IFactoryData<IReward, NBTTagCompound> {
        public ResourceKey getRegistryName() { return REWARD_ID; }
        public IReward createNew() { return new TestReward(); }
        public IReward loadFromData(NBTTagCompound data) { TestReward reward = new TestReward(); reward.readFromNBT(data); return reward; }
    }

    private static final class TestTask implements ITask {
        private String value = "";
        public String getUnlocalisedName() { return "test.task"; }
        public ResourceKey getFactoryID() { return TASK_ID; }
        public boolean isComplete(UUID uuid) { return false; }
        public void setComplete(UUID uuid) { }
        public void resetUser(UUID uuid) { }
        public NBTTagCompound writeToNBT(NBTTagCompound nbt) { nbt.setString("value", value); return nbt; }
        public void readFromNBT(NBTTagCompound nbt) { value = nbt.getString("value"); }
        public NBTTagCompound writeProgressToNBT(NBTTagCompound nbt, List<UUID> users) { return nbt; }
        public void readProgressFromNBT(NBTTagCompound nbt, boolean merge) { }
    }

    private static final class TestReward implements IReward {
        private String value = "";
        public String getUnlocalisedName() { return "test.reward"; }
        public ResourceKey getFactoryID() { return REWARD_ID; }
        public NBTTagCompound writeToNBT(NBTTagCompound nbt) { nbt.setString("value", value); return nbt; }
        public void readFromNBT(NBTTagCompound nbt) { value = nbt.getString("value"); }
    }
}
