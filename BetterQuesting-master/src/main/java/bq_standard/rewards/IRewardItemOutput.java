package bq_standard.rewards;

import java.util.List;

import betterquesting.api.questing.rewards.IReward;
import betterquesting.api.utils.BigItemStack;

public interface IRewardItemOutput extends IReward {

    List<BigItemStack> getItemOutputs();
}
