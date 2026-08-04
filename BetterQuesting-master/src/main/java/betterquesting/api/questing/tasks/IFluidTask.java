package betterquesting.api.questing.tasks;

import java.util.Map;
import java.util.UUID;

import net.minecraftforge.fluids.FluidStack;

import betterquesting.api.questing.IQuest;
import betterquesting.api2.utils.ParticipantInfo;

public interface IFluidTask extends ITask {

    boolean canAcceptFluid(UUID owner, Map.Entry<UUID, IQuest> quest, FluidStack fluid);

    FluidStack submitFluid(UUID owner, Map.Entry<UUID, IQuest> quest, FluidStack fluid);

    /**
     * @param fluids read-only list of fluids
     */
    default void retrieveFluids(ParticipantInfo pInfo, Map.Entry<UUID, IQuest> quest, FluidStack[] fluids) {}
}
