package com.colonyautopilot.bridge.runtime;

import com.colonyautopilot.bridge.ColonyAutopilotBridge;
import com.colonyautopilot.bridge.action.BridgeActionService;
import com.colonyautopilot.bridge.config.BridgeServerConfig;
import com.colonyautopilot.bridge.ipc.BridgeJsonCodec;
import com.colonyautopilot.bridge.ipc.BridgeProtocol.Request;
import com.colonyautopilot.bridge.ipc.BridgeProtocol.Response;
import com.colonyautopilot.bridge.ipc.BridgeTransport;
import com.colonyautopilot.bridge.ipc.FileSpoolTransport;
import com.google.gson.JsonParseException;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.util.List;

public final class BridgeController implements AutoCloseable {
    private final MinecraftServer server;
    private final BridgeState state = new BridgeState();
    private final BridgeMetrics metrics = new BridgeMetrics();
    private final TickGate tickGate = new TickGate();
    private final BridgeJsonCodec codec = new BridgeJsonCodec();
    private final BridgeTransport transport;
    private final BridgeActionService actions;
    private final OfflineColonyActivator offlineProgress;
    private final BuilderSpeedController builderSpeed;
    private final ConstructionDeadlineController constructionDeadlines;

    public BridgeController(MinecraftServer server) throws IOException {
        this.server = server;
        transport = new FileSpoolTransport();
        constructionDeadlines = new ConstructionDeadlineController(server, state);
        offlineProgress = new OfflineColonyActivator(
            server,
            state,
            constructionDeadlines
        );
        builderSpeed = new BuilderSpeedController(server, state);
        actions = new BridgeActionService(
            server,
            state,
            metrics,
            codec,
            offlineProgress,
            constructionDeadlines
        );
    }

    public void tick() {
        requireServerThread();
        offlineProgress.tick();
        builderSpeed.tick();
        constructionDeadlines.tick();
        if (!tickGate.tryAcquire(server.getTickCount(), BridgeServerConfig.IPC_POLL_INTERVAL_TICKS.get())) {
            return;
        }
        long started = System.nanoTime();
        int count = 0;
        try {
            List<BridgeTransport.InboundMessage> messages =
                transport.poll(BridgeServerConfig.MAX_ACTIONS_PER_TICK.get());
            count = messages.size();
            for (BridgeTransport.InboundMessage message : messages) {
                process(message);
            }
        } catch (IOException exception) {
            metrics.recordFailure();
            ColonyAutopilotBridge.LOGGER.error("IPC spool poll failed", exception);
        } finally {
            metrics.recordPoll(System.nanoTime() - started, count);
        }
    }

    public BridgeState state() {
        return state;
    }

    public BridgeMetrics.Snapshot metrics() {
        return metrics.snapshot();
    }

    public OfflineColonyActivator.Status offlineProgressStatus() {
        return offlineProgress.status();
    }

    public List<ConstructionDeadlineController.Status> constructionStatus() {
        return constructionDeadlines.status();
    }

    public boolean setConstructionDeadlinePaused(
        int colonyId,
        int workOrderId,
        boolean paused
    ) {
        requireServerThread();
        return constructionDeadlines.setPaused(colonyId, workOrderId, paused);
    }

    public boolean resetConstructionDeadline(int colonyId, int workOrderId) {
        requireServerThread();
        return constructionDeadlines.reset(colonyId, workOrderId);
    }

    public void requestNextPoll() {
        tickGate.reset();
    }

    public String explain() {
        return "mutations require enabled=true, mode=ACTIVE, budget, loaded chunks, and owner authorization; offline colony activity also requires sustained healthy TPS and tick time";
    }

    @Override
    public void close() throws IOException {
        constructionDeadlines.close();
        builderSpeed.close();
        offlineProgress.close();
        transport.close();
    }

    private void process(BridgeTransport.InboundMessage message) {
        try {
            Request request = codec.decodeRequest(message.body());
            if (!message.token().equals(request.idempotencyKey() + ".json")) {
                throw new JsonParseException("request filename must equal <idempotency_key>.json");
            }
            Response response = actions.handle(request);
            if (response.status() == com.colonyautopilot.bridge.ipc.BridgeProtocol.ResponseStatus.REJECTED) {
                metrics.recordRejected();
            }
            transport.complete(message, codec.encodeResponse(response));
        } catch (JsonParseException | IllegalArgumentException exception) {
            metrics.recordRejected();
            reject(message, "INVALID_REQUEST: " + exception.getMessage());
        } catch (RuntimeException | IOException exception) {
            metrics.recordFailure();
            ColonyAutopilotBridge.LOGGER.error("IPC request {} failed", message.token(), exception);
            reject(message, "INTERNAL_ERROR");
        }
    }

    private void reject(BridgeTransport.InboundMessage message, String reason) {
        try {
            transport.reject(message, reason);
        } catch (IOException exception) {
            metrics.recordFailure();
            ColonyAutopilotBridge.LOGGER.error("Could not quarantine IPC request {}", message.token(), exception);
        }
    }

    private void requireServerThread() {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Bridge controller must tick on the Minecraft server thread");
        }
    }
}
