package com.colonyautopilot.bridge.mixin;

import com.minecolonies.api.entity.ai.statemachine.states.IAIState;
import com.minecolonies.core.entity.ai.workers.AbstractEntityAIStructure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = AbstractEntityAIStructure.class, remap = false)
public interface StructureAiAccessor {
    @Invoker(value = "completeBuild", remap = false)
    IAIState colonyAutoCompleteBuild();
}
