package bq_standard.importers.hqm.converters.tasks;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;

import betterquesting.api.questing.tasks.ITask;

public class HQMTaskAdvancement {

    public ITask[] convertTask(JsonObject json) {
        List<ITask> tasks = new ArrayList<>();
        // No equivalent

        return tasks.toArray(new ITask[0]);
    }
}
