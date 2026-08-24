package com.colonyautopilot.bridge.action;

import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.util.InventoryUtils;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;

import java.util.List;
import java.util.function.IntUnaryOperator;
import java.util.function.Predicate;

final class ItemDelivery {
    private ItemDelivery() {
    }

    static boolean canAcceptEntireStack(
        List<IItemHandler> handlers,
        ItemStack stack
    ) {
        List<IntUnaryOperator> simulations = handlers.stream()
            .map(handler -> (IntUnaryOperator) count -> {
                ItemStack candidate = stack.copy();
                candidate.setCount(count);
                return ItemHandlerHelper.insertItemStacked(
                    handler,
                    candidate,
                    true
                ).getCount();
            })
            .toList();
        return allInserted(stack.getCount(), simulations);
    }

    static boolean canForceTransferEntireStack(
        IBuilding building,
        Level level,
        ItemStack stack
    ) {
        ICapabilityProvider hut = building.getTileEntity();
        List<ICapabilityProvider> chests = building.getContainers().stream()
            .map(level::getBlockEntity)
            .filter(ChestBlockEntity.class::isInstance)
            .map(BlockEntity.class::cast)
            .map(ICapabilityProvider.class::cast)
            .toList();
        ICapabilityProvider target = selectTransferTarget(
            hut,
            chests,
            provider -> !InventoryUtils.isProviderFull(provider)
        );
        return target != null && canAcceptEntireStack(
            List.copyOf(InventoryUtils.getItemHandlersFromProvider(target)),
            stack
        );
    }

    static <T> T selectTransferTarget(
        T primary,
        List<T> alternatives,
        Predicate<T> usable
    ) {
        if (primary != null) {
            return primary;
        }
        return alternatives.stream().filter(usable).findFirst().orElse(null);
    }

    static boolean allInserted(
        int initialCount,
        List<IntUnaryOperator> simulations
    ) {
        int remaining = initialCount;
        for (IntUnaryOperator simulation : simulations) {
            remaining = simulation.applyAsInt(remaining);
            if (remaining <= 0) {
                return true;
            }
        }
        return false;
    }
}
