package bq_standard.importers.hqm.converters.tasks;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;

import betterquesting.api.questing.tasks.ITask;

public class HQMTaskTame {

    public ITask[] convertTask(JsonObject json) {
        List<ITask> tList = new ArrayList<>();
        // No Equivalent

        return tList.toArray(new ITask[0]);
    }
}
