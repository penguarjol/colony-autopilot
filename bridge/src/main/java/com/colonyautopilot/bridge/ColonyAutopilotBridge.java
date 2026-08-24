package com.colonyautopilot.bridge;

import com.colonyautopilot.bridge.command.BridgeCommands;
import com.colonyautopilot.bridge.config.BridgeServerConfig;
import com.colonyautopilot.bridge.runtime.BridgeController;
import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.world.ForgeChunkManager;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.network.NetworkConstants;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.ArrayList;

@Mod(ColonyAutopilotBridge.MOD_ID)
public final class ColonyAutopilotBridge {
    public static final String MOD_ID = "colonyautobridge";
    public static final Logger LOGGER = LogUtils.getLogger();

    private volatile BridgeController controller;

    public ColonyAutopilotBridge() {
        ForgeChunkManager.setForcedChunkLoadingCallback(
            MOD_ID,
            (level, tickets) -> new ArrayList<>(tickets.getEntityTickets().keySet())
                .forEach(tickets::removeAllTickets)
        );
        ModLoadingContext.get().registerExtensionPoint(
            IExtensionPoint.DisplayTest.class,
            () -> new IExtensionPoint.DisplayTest(
                () -> NetworkConstants.IGNORESERVERONLY,
                ClientCompatibility::acceptsRemoteVersion
            )
        );
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, BridgeServerConfig.SPEC);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        BridgeCommands.register(event.getDispatcher(), () -> controller);
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        try {
            controller = new BridgeController(event.getServer());
            LOGGER.info("Colony Autopilot Bridge file-spool IPC initialized");
        } catch (IOException exception) {
            controller = null;
            LOGGER.error("Colony Autopilot Bridge IPC disabled because its private spool could not be secured", exception);
        }
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        BridgeController active = controller;
        if (active != null && event.phase == TickEvent.Phase.END) {
            active.tick();
        }
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        BridgeController active = controller;
        controller = null;
        if (active == null) {
            return;
        }
        try {
            active.close();
        } catch (IOException exception) {
            LOGGER.error("Failed to close Colony Autopilot Bridge IPC", exception);
        }
    }
}
