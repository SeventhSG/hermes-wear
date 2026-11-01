package cloud.stormlabs.hermescompanion;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Owns every in-flight Hermes run for the whole process.
 *
 * Text turns run on the server's durable /v1/runs API and survive a dropped connection (the stream
 * re-attaches and replays); the session stream used for image turns is interrupted the moment its
 * client disconnects. Either way a stream that lives inside an Activity would die as soon as the
 * screen rotates, the user switches apps, or the assistant overlay closes. Streams live here instead, kept alive by
 * {@link RunService} while any run is active, and screens attach as observers.
 */
public final class RunManager {
    /** Screens observe runs; every callback arrives on the main thread. */
    public interface Observer { void onRunUpdate(LiveRun run, String kind); }

    public static final String UPDATE_STARTED = "started", UPDATE_DELTA = "delta", UPDATE_REASONING = "reasoning",
            UPDATE_TOOL = "tool", UPDATE_COMMENTARY = "commentary", UPDATE_APPROVAL = "approval",
            UPDATE_FINISHED = "finished", UPDATE_ERROR = "error";

    /** Everything a screen needs to redraw a run it did not start, or re-attach after rotation. */
    public static final class LiveRun {
        public final String profileId, sessionId, sessionTitle, userText, systemMessage;
        public volatile Object payload; // kept for Retry; cleared once the run succeeds
        public final boolean presence;
        public final long startedAt = System.currentTimeMillis();
        public volatile String runId;
        public final StringBuilder text = new StringBuilder(), reasoning = new StringBuilder();
        public final List<HermesApi.ToolCall> tools = new ArrayList<>();
        public final List<String> commentary = new ArrayList<>();
        /** Messages steered into this run, shown in the live view until the transcript reloads. */
        public final List<String> steers = new ArrayList<>();
        /**
         * The run in arrival order, for the live view: a StringBuilder is one assistant message, a ToolCall a tool,
         * a {@link Note} a steer or commentary. Text after a tool, steer or new message starts a new segment.
         */
        public final List<Object> timeline = new ArrayList<>();
        public static final class Note {
            public final boolean steer; public final String text;
            Note(boolean steer, String text) { this.steer = steer; this.text = text; }
        }
        /** Caller holds the lock. */
        void appendText(String delta, boolean newSegment) {
            text.append(delta);
            Object last = timeline.isEmpty() ? null : timeline.get(timeline.size() - 1);
            if (last instanceof StringBuilder && !newSegment) ((StringBuilder) last).append(delta);
            else if (!delta.trim().isEmpty() || newSegment) timeline.add(new StringBuilder(newSegment ? "" : delta));
        }
        /** Caller holds the lock. A steer may arrive from the POST reply and from the stream; show it once. */
        boolean addSteer(String s) {
            if (s == null || s.trim().isEmpty() || steers.contains(s)) return false;
            steers.add(s); timeline.add(new Note(true, s));
            return true;
        }
        public volatile HermesApi.Approval approval;
        public volatile HermesApi.RunOutcome outcome;
        public volatile String error, lastToolLine = "";
        public volatile String agentFailure;
        /** Device actions found in the reply, executed once by whichever screen sees them first. */
        public final List<DeviceActions.Action> pendingActions = new ArrayList<>();
        public volatile boolean actionsHandled, spoken;
        /** Characters of the reply already handed to the speaker while it streamed; {@code muted} drops the rest (interrupted). */
        public volatile int spokenChars;
        public volatile boolean muted;
        /** The prompt was spoken, so the reply should be read aloud. */
        public volatile boolean voice;
        final HermesApi.StreamHandle handle = new HermesApi.StreamHandle();
        final AgentBackend api;
        LiveRun(AgentBackend api, String profileId, String sessionId, String sessionTitle, String userText, boolean presence) {
            this(api, profileId, sessionId, sessionTitle, userText, presence, null, null);
        }
        LiveRun(AgentBackend api, String profileId, String sessionId, String sessionTitle, String userText, boolean presence, Object payload, String systemMessage) {
            this.api = api; this.profileId = profileId; this.sessionId = sessionId; this.sessionTitle = sessionTitle == null ? "Hermes" : sessionTitle; this.userText = userText; this.presence = presence; this.payload = payload; this.systemMessage = systemMessage;
        }
        public boolean belongsTo(String profile, String session) { return profileId.equals(profile) && sessionId.equals(session); }
        public boolean active() { return outcome == null && error == null; }
        public String finalText() {
            if (outcome != null && !outcome.finalText.isEmpty()) return outcome.finalText;
            return text.toString();
        }
    }

    private static RunManager instance;
    public static synchronized RunManager get(Context c) {
        if (instance == null) instance = new RunManager(c.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService streams = Executors.newCachedThreadPool();
    private final Map<String, LiveRun> bySession = new LinkedHashMap<>();
    private final List<Observer> observers = new CopyOnWriteArrayList<>();
    /** The session a screen is currently showing; notifications are skipped for it. */
    private volatile String visibleSession;

    static String runKey(String profileId, String sessionId) { return profileId + "\u0000" + sessionId; }

    private RunManager(Context app) { this.app = app; }

    public void addObserver(Observer o) { if (!observers.contains(o)) observers.add(o); }
    public void removeObserver(Observer o) { observers.remove(o); }
    public void setVisibleSession(String profileId, String sessionId) {
        visibleSession = sessionId == null ? null : runKey(profileId, sessionId);
        if (sessionId != null) Notifier.cancelResult(app, profileId, sessionId);
    }
    public void clearVisibleSession(String profileId, String sessionId) {
        if (isVisible(profileId, sessionId)) visibleSession = null;
    }
    public boolean isVisible(String profileId, String sessionId) { return runKey(profileId, sessionId).equals(visibleSession); }

    public synchronized LiveRun run(String profileId, String sessionId) { return sessionId == null ? null : bySession.get(runKey(profileId, sessionId)); }
    public synchronized LiveRun active(String profileId, String sessionId) { LiveRun r = run(profileId, sessionId); return r != null && r.active() ? r : null; }
    public synchronized List<LiveRun> activeRuns() {
        List<LiveRun> out = new ArrayList<>();
        for (LiveRun r : bySession.values()) if (r.active()) out.add(r);
        return out;
    }
    public synchronized void forget(String profileId, String sessionId) { String key = runKey(profileId, sessionId); LiveRun r = bySession.get(key); if (r != null && !r.active()) bySession.remove(key); }

    /** Start a turn in this profile and session, or return null if it is already active. */
    LiveRun start(AgentBackend api, String profileId, String sessionId, String title, Object payload, String systemMessage, String userText, boolean presence) {
        final LiveRun run;
        synchronized (this) {
            LiveRun existing = bySession.get(runKey(profileId, sessionId));
            if (existing != null && existing.active()) return null;
            run = new LiveRun(api, profileId, sessionId, title, userText, presence, payload, systemMessage);
            bySession.put(runKey(profileId, sessionId), run);
        }
        RunService.ensureRunning(app);
        streams.submit(() -> api.streamChat(sessionId, payload, systemMessage, run.handle, listener(run)));
        return run;
    }

    /** Follow a run already working on the server (started in the terminal or by another device), unless one is live here. */
    LiveRun attach(AgentBackend api, String profileId, String sessionId, String title, String runId, String userText) {
        final LiveRun run;
        synchronized (this) {
            LiveRun existing = bySession.get(runKey(profileId, sessionId));
            if (existing != null && (existing.active() || runId.equals(existing.runId))) return null;
            run = new LiveRun(api, profileId, sessionId, title, userText, false);
            bySession.put(runKey(profileId, sessionId), run);
        }
        RunService.ensureRunning(app);
        streams.submit(() -> api.followRun(runId, run.handle, listener(run)));
        return run;
    }

    private HermesApi.StreamListener listener(LiveRun run) {
        return new HermesApi.StreamListener() {
            public void onRunStarted(String runId) { run.runId = runId; post(run, UPDATE_STARTED); }
            public void onDelta(String delta) { synchronized (run) { run.appendText(delta, false); } post(run, UPDATE_DELTA); }
            @Override public void onSegment(String separator) { synchronized (run) { run.appendText(separator, true); } post(run, UPDATE_DELTA); }
            @Override public void onSteer(String text) { boolean added; synchronized (run) { added = run.addSteer(text); } if (added) post(run, UPDATE_DELTA); }
            public void onTool(String state, String name, String preview) { onToolEvent(state, name, preview, ""); }
            @Override public void onToolEvent(String state, String name, String preview, String argsJson) {
                synchronized (run) {
                    if ("started".equals(state)) {
                        HermesApi.ToolCall call = HermesApi.ToolCall.live(name, HermesApi.ToolCall.startArgs(name, preview, argsJson));
                        run.tools.add(call);
                        run.timeline.add(call);
                    } else if ("output".equals(state)) {
                        for (int i = run.tools.size() - 1; i >= 0; i--) {
                            HermesApi.ToolCall t = run.tools.get(i);
                            if (t.name.equals(name) && "running".equals(t.status)) { t.result += preview == null ? "" : preview; break; }
                        }
                    } else {
                        for (int i = run.tools.size() - 1; i >= 0; i--) {
                            HermesApi.ToolCall t = run.tools.get(i);
                            if (t.name.equals(name) && "running".equals(t.status)) {
                                t.status = "failed".equals(state) ? "failed" : "completed";
                                if (preview != null && !preview.isEmpty()) t.result = preview;
                                break;
                            }
                        }
                    }
                    if (!"output".equals(state)) run.lastToolLine = Tools.label(name) + (preview == null || preview.isEmpty() ? "" : " · " + preview);
                }
                post(run, UPDATE_TOOL);
            }
            @Override public void onReasoning(String delta) { synchronized (run) { run.reasoning.append(delta); } post(run, UPDATE_REASONING); }
            @Override public void onCommentary(String text) { synchronized (run) { run.commentary.add(text); run.timeline.add(new LiveRun.Note(false, text)); } post(run, UPDATE_COMMENTARY); }
            @Override public void onApproval(HermesApi.Approval approval) {
                run.approval = approval;
                if (approval == null) main.post(() -> Notifier.cancelApproval(app, run.profileId, run.sessionId));
                post(run, approval == null ? UPDATE_TOOL : UPDATE_APPROVAL);
            }
            @Override public void onRunFinished(HermesApi.RunOutcome outcome) {
                DeviceActions.Parsed parsed = DeviceActions.parse(outcome.finalText);
                outcome.finalText = parsed.cleanText;
                List<DeviceActions.Action> actions = parsed.actions;
                // A multi-step turn (device block, then a tool call, then more text) ends with a final segment
                // that lacks the block; take the actions from everything streamed in this run instead.
                if (actions.isEmpty()) { String all; synchronized (run) { all = run.text.toString(); } actions = DeviceActions.parse(all).actions; }
                synchronized (run) { run.pendingActions.addAll(actions); }
                run.approval = null;
                run.outcome = outcome;
            }
            public void onComplete(String finalText) {
                run.payload = null;
                if (run.outcome == null) { HermesApi.RunOutcome o = new HermesApi.RunOutcome(); o.finalText = finalText; onRunFinished(o); }
                post(run, UPDATE_FINISHED);
            }
            @Override public void onConnection(boolean connected) {
                synchronized (run) { run.lastToolLine = connected ? "Reconnected" : "Connection lost, reconnecting… (Hermes keeps working)"; }
                post(run, UPDATE_TOOL);
            }
            public void onError(String error) { run.error = error == null ? "Run failed" : error; run.approval = null; post(run, UPDATE_ERROR); }
        };
    }

    public void stop(LiveRun run) {
        if (run == null) return;
        String id = run.runId;
        streams.submit(() -> {
            HermesApi.Result r = id == null ? HermesApi.Result.error("not started") : run.api.stopRun(id);
            // A run that never got an id (or a server that refused) is ended by dropping the socket.
            if (!r.ok) { run.handle.cancel(); return; }
            // The server accepted the stop (or the run had already finished there): give it a moment to
            // send the terminal event, then drop the socket anyway so Stop always ends the run locally.
            main.postDelayed(() -> { if (run.active()) streams.submit(run.handle::cancel); }, 4000);
        });
    }

    public void steer(LiveRun run, String text, java.util.function.Consumer<HermesApi.Result> done) {
        streams.submit(() -> {
            HermesApi.Result r = run.runId == null ? HermesApi.Result.error("The run is still starting") : run.api.steerRun(run.runId, text);
            if (r.ok) synchronized (run) { run.addSteer(text); }
            main.post(() -> { if (done != null) done.accept(r); });
        });
    }

    public void approve(LiveRun run, String choice, java.util.function.Consumer<HermesApi.Result> done) {
        HermesApi.Approval a = run.approval;
        String runId = a != null && !a.runId.isEmpty() ? a.runId : run.runId;
        streams.submit(() -> {
            HermesApi.Result r = runId == null ? HermesApi.Result.error("No run to approve") : run.api.approveRun(runId, choice, a == null ? null : a.requestId, a == null ? -1 : a.generation);
            if (r.ok) run.approval = null;
            main.post(() -> {
                Notifier.cancelApproval(app, run.profileId, run.sessionId);
                HermesWidgetProvider.update(app);
                if (done != null) done.accept(r);
                notifyObservers(run, UPDATE_TOOL);
            });
        });
    }

    private void post(LiveRun run, String kind) { main.post(() -> dispatch(run, kind)); }

    private void dispatch(LiveRun run, String kind) {
        notifyObservers(run, kind);
        boolean watched = isVisible(run.profileId, run.sessionId) && !observers.isEmpty();
        switch (kind) {
            case UPDATE_APPROVAL:
                HermesWidgetProvider.update(app);
                if (!watched && run.approval != null) Notifier.approval(app, run);
                break;
            case UPDATE_FINISHED: case UPDATE_ERROR:
                if (kind.equals(UPDATE_FINISHED)) HermesWidgetProvider.record(app, run.sessionId, run.sessionTitle, run.finalText()); else HermesWidgetProvider.update(app);
                Notifier.cancelApproval(app, run.profileId, run.sessionId);
                if (!watched && !run.presence && !"Reply drafts".equals(run.sessionTitle)) Notifier.result(app, run);
                // A steer that landed after Hermes' last tool call comes back unread; it is the next turn.
                String late = kind.equals(UPDATE_FINISHED) && run.outcome != null ? run.outcome.pendingSteer : "";
                if (!late.isEmpty()) start(run.api, run.profileId, run.sessionId, run.sessionTitle, late, run.systemMessage, late, run.presence);
                break;
            default:
        }
        // Per-token deltas are cheap: RunService drops unchanged state and rate-limits to 1/s.
        RunService.refresh(app);
    }

    private void notifyObservers(LiveRun run, String kind) { for (Observer o : observers) try { o.onRunUpdate(run, kind); } catch (Throwable ignored) {} }
}
