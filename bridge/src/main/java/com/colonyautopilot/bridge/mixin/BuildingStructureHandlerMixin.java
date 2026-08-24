package com.colonyautopilot.bridge.mixin;

import com.colonyautopilot.bridge.runtime.ConstructionBatchRegistry;
import com.minecolonies.api.colony.workorders.IBuilderWorkOrder;
import com.minecolonies.core.entity.ai.workers.util.BuildingStructureHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = BuildingStructureHandler.class, remap = false)
abstract class BuildingStructureHandlerMixin {
    @Shadow(remap = false)
    private IBuilderWorkOrder workOrder;

    @Inject(
        method = "getStepsPerCall",
        at = @At("HEAD"),
        cancellable = true,
        remap = false
    )
    private void colonyAutoStepsPerCall(
        CallbackInfoReturnable<Integer> callback
    ) {
        callback.setReturnValue(ConstructionBatchRegistry.stepsFor(workOrder));
    }
}
