package bq_standard.tasks;

import java.util.List;

import betterquesting.api.questing.tasks.ITask;
import betterquesting.api.utils.BigItemStack;

public interface ITaskItemInput extends ITask {

    List<BigItemStack> getItemInputs();
}
