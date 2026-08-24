package com.colonyautopilot.bridge.command;

import com.colonyautopilot.bridge.config.BridgeServerConfig;
import com.colonyautopilot.bridge.runtime.BridgeController;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.function.Supplier;

public final class BridgeCommands {
    private static final int DEFAULT_PAUSE_TICKS = 1_200;

    private BridgeCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, Supplier<BridgeController> controller) {
        dispatcher.register(Commands.literal("colonyauto")
            .requires(source -> source.hasPermission(BridgeServerConfig.REQUIRED_PERMISSION_LEVEL.get()))
            .then(Commands.literal("on").executes(context -> {
                BridgeServerConfig.ENABLED.set(true);
                return reply(context.getSource(), "Colony Autopilot enabled; current mode is " + BridgeServerConfig.MODE.get());
            }))
            .then(Commands.literal("off").executes(context -> {
                BridgeServerConfig.ENABLED.set(false);
                return reply(context.getSource(), "Colony Autopilot disabled");
            }))
            .then(Commands.literal("status").executes(context -> {
                BridgeController bridge = requireController(controller);
                var metrics = bridge.metrics();
                var offline = bridge.offlineProgressStatus();
                String status = "enabled=" + BridgeServerConfig.ENABLED.get()
                    + ", mode=" + BridgeServerConfig.MODE.get()
                    + ", paused=" + bridge.state().isPaused(context.getSource().getServer().getTickCount())
                    + ", budget=" + BridgeServerConfig.DAILY_ACTION_BUDGET.get()
                    + ", builderSpeed=" + BridgeServerConfig.BUILDER_SPEED_MULTIPLIER.get()
                    + ", offline=" + offline.enabled()
                    + ", offlineActive=" + offline.active()
                    + ", offlineLeases=" + offline.leases()
                    + ", offlineTickingChunks=" + offline.tickingChunks()
                    + ", stallRecovery=" + offline.stallRecoveryEnabled()
                    + ", stallRecoveries=" + offline.stallRecoveries()
                    + ", constructionDeadline="
                    + BridgeServerConfig.CONSTRUCTION_DEADLINE_ENABLED.get()
                    + ", constructionJobs=" + bridge.constructionStatus().size()
                    + ", requests=" + metrics.requests()
                    + ", rejected=" + metrics.rejected()
                    + ", failures=" + metrics.failures();
                return reply(context.getSource(), status);
            }))
            .then(Commands.literal("mode")
                .executes(context -> reply(context.getSource(), "Mode is " + BridgeServerConfig.MODE.get()))
                .then(Commands.literal("observer").executes(context -> {
                    BridgeServerConfig.MODE.set(BridgeServerConfig.Mode.OBSERVER);
                    return reply(context.getSource(), "Mode set to OBSERVER");
                }))
                .then(Commands.literal("active").executes(context -> {
                    BridgeServerConfig.MODE.set(BridgeServerConfig.Mode.ACTIVE);
                    return reply(context.getSource(), "Mode set to ACTIVE; enabled and budget gates still apply");
                })))
            .then(Commands.literal("budget")
                .executes(context -> reply(
                    context.getSource(),
                    "Daily action budget is " + BridgeServerConfig.DAILY_ACTION_BUDGET.get()
                ))
                .then(Commands.argument("actions", IntegerArgumentType.integer(0, 10_000))
                    .executes(context -> {
                        int budget = IntegerArgumentType.getInteger(context, "actions");
                        BridgeServerConfig.DAILY_ACTION_BUDGET.set(budget);
                        return reply(context.getSource(), "Daily action budget set to " + budget);
                    })))
            .then(Commands.literal("builder-speed")
                .executes(context -> reply(
                    context.getSource(),
                    "Builder speed multiplier is "
                        + BridgeServerConfig.BUILDER_SPEED_MULTIPLIER.get() + "x"
                ))
                .then(Commands.argument("multiplier", IntegerArgumentType.integer(1, 10))
                    .executes(context -> {
                        int multiplier = IntegerArgumentType.getInteger(
                            context,
                            "multiplier"
                        );
                        BridgeServerConfig.BUILDER_SPEED_MULTIPLIER.set(multiplier);
                        return reply(
                            context.getSource(),
                            "Builder speed multiplier set to " + multiplier + "x"
                        );
                    })))
            .then(Commands.literal("deadline")
                .executes(context -> {
                    BridgeController bridge = requireController(controller);
                    String jobs = bridge.constructionStatus().stream()
                        .map(status -> "colony=" + status.colonyId()
                            + "/order=" + status.workOrderId()
                            + "/phase=" + status.phase()
                            + "/healthy=" + status.healthySeconds() + "s"
                            + "/remaining=" + status.estimatedRemainingOperations())
                        .reduce((left, right) -> left + "; " + right)
                        .orElse("none");
                    return reply(
                        context.getSource(),
                        "Construction deadline is "
                            + BridgeServerConfig.CONSTRUCTION_DEADLINE_ENABLED.get()
                            + "; minutes="
                            + BridgeServerConfig.CONSTRUCTION_DEADLINE_MINUTES.get()
                            + "; jobs=" + jobs
                    );
                })
                .then(Commands.literal("on").executes(context -> {
                    BridgeServerConfig.CONSTRUCTION_DEADLINE_ENABLED.set(true);
                    return reply(context.getSource(), "Construction deadline enabled");
                }))
                .then(Commands.literal("off").executes(context -> {
                    BridgeServerConfig.CONSTRUCTION_DEADLINE_ENABLED.set(false);
                    return reply(context.getSource(), "Construction deadline disabled");
                }))
                .then(Commands.literal("minutes")
                    .then(Commands.argument(
                        "minutes",
                        IntegerArgumentType.integer(5, 360)
                    ).executes(context -> {
                        int minutes = IntegerArgumentType.getInteger(
                            context,
                            "minutes"
                        );
                        BridgeServerConfig.CONSTRUCTION_DEADLINE_MINUTES.set(minutes);
                        return reply(
                            context.getSource(),
                            "Construction deadline set to " + minutes
                                + " healthy-runtime minutes"
                        );
                    })))
                .then(deadlineWorkOrderCommand("pause", controller, true, false))
                .then(deadlineWorkOrderCommand("resume", controller, false, false))
                .then(deadlineWorkOrderCommand("reset", controller, false, true)))
            .then(Commands.literal("offline")
                .executes(context -> reply(
                    context.getSource(),
                    "Offline progress is " + BridgeServerConfig.OFFLINE_PROGRESS_ENABLED.get()
                        + "; " + requireController(controller).offlineProgressStatus().reason()
                ))
                .then(Commands.literal("on").executes(context -> {
                    BridgeServerConfig.OFFLINE_PROGRESS_ENABLED.set(true);
                    return reply(context.getSource(), "Bounded offline colony progress enabled");
                }))
                .then(Commands.literal("off").executes(context -> {
                    BridgeServerConfig.OFFLINE_PROGRESS_ENABLED.set(false);
                    return reply(context.getSource(), "Offline colony progress disabled");
                }))
                .then(Commands.literal("recovery")
                    .executes(context -> {
                        var status = requireController(controller).offlineProgressStatus();
                        return reply(
                            context.getSource(),
                            "Offline stall recovery is " + status.stallRecoveryEnabled()
                                + "; recoveries=" + status.stallRecoveries()
                                + "; last=" + status.lastStallRecovery()
                        );
                    })
                    .then(Commands.literal("on").executes(context -> {
                        BridgeServerConfig.OFFLINE_STALL_RECOVERY_ENABLED.set(true);
                        return reply(context.getSource(), "Offline builder stall recovery enabled");
                    }))
                    .then(Commands.literal("off").executes(context -> {
                        BridgeServerConfig.OFFLINE_STALL_RECOVERY_ENABLED.set(false);
                        return reply(context.getSource(), "Offline builder stall recovery disabled");
                    }))))
            .then(Commands.literal("pause")
                .executes(context -> pause(context.getSource(), controller, DEFAULT_PAUSE_TICKS))
                .then(Commands.argument("ticks", IntegerArgumentType.integer(0, 72_000))
                    .executes(context -> pause(
                        context.getSource(),
                        controller,
                        IntegerArgumentType.getInteger(context, "ticks")
                    ))))
            .then(Commands.literal("next").executes(context -> {
                requireController(controller).requestNextPoll();
                return reply(context.getSource(), "Next IPC poll scheduled for the next server tick");
            }))
            .then(Commands.literal("explain").executes(context ->
                reply(context.getSource(), requireController(controller).explain()))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<
        CommandSourceStack
    > deadlineWorkOrderCommand(
        String name,
        Supplier<BridgeController> controller,
        boolean paused,
        boolean reset
    ) {
        return Commands.literal(name)
            .then(Commands.argument("colony", IntegerArgumentType.integer(1))
                .then(Commands.argument("work-order", IntegerArgumentType.integer(1))
                    .executes(context -> {
                        int colony = IntegerArgumentType.getInteger(
                            context,
                            "colony"
                        );
                        int workOrder = IntegerArgumentType.getInteger(
                            context,
                            "work-order"
                        );
                        BridgeController bridge = requireController(controller);
                        boolean changed = reset
                            ? bridge.resetConstructionDeadline(colony, workOrder)
                            : bridge.setConstructionDeadlinePaused(
                                colony,
                                workOrder,
                                paused
                            );
                        return reply(
                            context.getSource(),
                            changed
                                ? "Construction deadline " + name
                                    + " applied to work order " + workOrder
                                : "No matching construction work order"
                        );
                    })));
    }

    private static int pause(
        CommandSourceStack source,
        Supplier<BridgeController> controller,
        int ticks
    ) {
        BridgeController bridge = requireController(controller);
        if (ticks == 0) {
            bridge.state().resume();
            return reply(source, "Bridge resumed");
        }
        bridge.state().pause(source.getServer().getTickCount(), ticks);
        return reply(source, "Bridge mutations paused for " + ticks + " ticks");
    }

    private static BridgeController requireController(Supplier<BridgeController> supplier) {
        BridgeController controller = supplier.get();
        if (controller == null) {
            throw new IllegalStateException("Bridge runtime is not available");
        }
        return controller;
    }

    private static int reply(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal(message), false);
        return 1;
    }
}
