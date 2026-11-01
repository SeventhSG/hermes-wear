package cloud.stormlabs.hermescompanion;

import android.app.Activity;
import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * ChatGPT-agent-style observe → act loop for on-screen tasks.
 *
 * A reply's device actions used to run once and stop. This drives Hermes through repeated
 * "[PHONE RESULTS] step N" follow-ups — run the actions, wait for the screen to settle, describe
 * it, send it back — until the reply has no more device actions, the user stops it, the kill
 * switch is on, the step cap is hit, or the screen is stuck. It runs headless (RunManager/
 * RunService keep the stream alive without an Activity), so the target app can stay in front
 * while ChatActivity or the voice overlay step aside.
 */
final class PhoneAgent implements RunManager.Observer {

    /** Ask-mode pause and status, rendered by the agent overlay (or a fallback dialog). */
    interface Listener {
        void onStep(int step, int cap, DeviceActions.Action action, boolean askMode);
        void onPendingConfirmation(DeviceActions.Action action, Runnable allow, Runnable stop);
        void onFinished(String summary, boolean ok, boolean needsScreenContent);
        /** Before a tap: highlight {@code bounds}, then call {@code proceed}. Default: no highlight, proceed at once. */
        default void onBeforeTap(Rect bounds, Runnable proceed) { proceed.run(); }
    }

    private static volatile PhoneAgent active;
    static boolean isRunning(String profileId, String sessionId) { PhoneAgent a = active; return a != null && a.profileId.equals(profileId) && a.sessionId.equals(sessionId); }
    static PhoneAgent current() { return active; }
    /** True while a PhoneAgent task is driving the screen: HermesAccessibilityService excludes its
     *  own app's windows from describeScreen/elements/target matching for exactly this long. */
    static boolean isTaskActive() { return active != null; }

    /** A fallback for the Ask-mode confirmation UI (see {@link #confirm}) before the overlay exists. */
    private static volatile Activity fallbackActivity;
    private static volatile String fallbackSessionId, fallbackProfileId;
    static void attachFallback(Activity activity, String profileId, String sessionId) { fallbackActivity = activity; fallbackProfileId = profileId; fallbackSessionId = sessionId; }
    static void detachFallback(Activity activity) { if (fallbackActivity == activity) { fallbackActivity = null; fallbackSessionId = null; } }

    /** True when a reply is worth handing to the agent loop instead of running its actions once. */
    static boolean shouldStart(RunManager.LiveRun run) {
        if (!HermesAccessibilityService.enabled()) return false;
        for (DeviceActions.Action a : run.pendingActions) if (drivesScreen(a.type)) return true;
        return false;
    }

    /** Actions that change or read what's on screen: any of these makes a reply an agent task, so
     *  the older query-round loop never half-handles navigation (it ran out of rounds silently). */
    static boolean drivesScreen(String type) {
        if (DeviceActions.isScreenStep(type)) return true;
        switch (type == null ? "" : type) {
            case "open_app": case "open_settings": case "open_url": case "read_screen":
            case "back": case "home": case "recents": case "notifications": case "wifi": case "bluetooth":
                return true;
            default: return false;
        }
    }

    static synchronized PhoneAgent start(Context context, RunManager.LiveRun run) {
        if (active != null) {
            run.actionsHandled = true;
            run.agentFailure = "Another phone task is running. Stop it before starting this task.";
            android.widget.Toast.makeText(context, run.agentFailure, android.widget.Toast.LENGTH_LONG).show();
            return null;
        }
        PhoneAgent agent = new PhoneAgent(context, run);
        agent.originalTask = run.userText;
        agent.lastRun = run;
        run.actionsHandled = true;
        active = agent;
        agent.runs.addObserver(agent);
        HermesAccessibilityService.attachAgentOverlay(agent);
        List<DeviceActions.Action> actions = new ArrayList<>(run.pendingActions);
        if (shouldGoHomeFirst(HermesAccessibilityService.currentApp(), context.getPackageName(), actions)) {
            // Resume the app the user came from (a task continued from the chat), else the launcher.
            String previous = HermesAccessibilityService.lastOtherApp();
            try {
                actions.add(0, previous == null ? new DeviceActions.Action("home", new JSONObject())
                        : new DeviceActions.Action("open_app", new JSONObject().put("package", previous)));
            } catch (org.json.JSONException ignored) { }
        }
        agent.worker.execute(() -> {
            SettingsStore.Connection hub = agent.store.hubConnection(agent.profileId);
            if (hub != null) { agent.hubUrl = hub.endpoint; agent.hubKey = hub.key; }
            agent.main.post(() -> { if (!agent.stopped) { agent.pinAgentModel(); agent.runStep(run, actions); } });
        });
        return agent;
    }

    /** Claude Haiku 5.5 when the Anthropic provider is signed in: ~1 s to the first spoken word through the
     *  gateway (flash-class models on the aggregators took 3-10 s), and it researches properly with tools
     *  (Haiku 4.5 gave headline-only answers from stale knowledge). The gateway's model list lags new
     *  models, so the provider is enough. Then Haiku 4.5, {@link #FALLBACK_AGENT_MODEL}, else keep the current model. */
    static final String DEFAULT_AGENT_MODEL = "claude-haiku-5-5";
    static final String PREVIOUS_AGENT_MODEL = "claude-haiku-4-5-20251001";
    static final String FALLBACK_AGENT_MODEL = "google/gemini-3-flash-preview";

    /** Best-effort, fire-and-forget: pin this task's conversation to the configured (or default)
     *  agent model. Runs on its own thread (not the step-execution {@link #worker}) so it never
     *  delays the first action; a failed/skipped pin just keeps the current model. */
    private void pinAgentModel() {
        String configured = store.agentModel();
        new Thread(() -> {
            try {
                if (stopped) return;
                pinModel(api, sessionId, configured);
            } catch (Exception ignored) { /* keep the current model */ }
        }, "phone-agent-model").start();
    }

    final String profileId, sessionId;
    private volatile String hubUrl = "", hubKey = "";
    private final Context app;
    private final AgentBackend api;
    private final String sessionTitle;
    private final SettingsStore store;
    private final RunManager runs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final TaskWorker worker = new TaskWorker();
    private final StuckDetector stuck = new StuckDetector();
    private volatile Listener listener;
    private volatile boolean stopped;
    private volatile RunManager.LiveRun lastRun;
    private int step;
    // Screenshots (claude-agent-screens): the last follow-up sent and its parts, kept only to
    // retry once without an image if the server rejects it.
    private boolean imagesRejected, lastFollowUpHadImage;
    private RunManager.LiveRun pendingRun;
    private List<DeviceActionRunner.Outcome> pendingResults;
    private String pendingScreen;
    // Jev fast steps (experiment): the user's original request, every outcome logged so far this
    // task (for Jev's "done" state), and how many fast steps have run since the last Hermes reply.
    private String originalTask;
    private final List<String> doneHistory = new ArrayList<>();
    private int fastStepsThisTurn;
    static final int FAST_STEP_ELEMENT_LIMIT = 60, FAST_STEPS_PER_TURN = 5;
    static final double OP_CONFIDENCE_MIN = 0.75, TARGET_CONFIDENCE_MIN = 0.75, SMART_ASK_RISK_MIN = 0.3;

    private PhoneAgent(Context context, RunManager.LiveRun run) {
        this.app = context.getApplicationContext();
        this.api = run.api; this.profileId = run.profileId; this.sessionId = run.sessionId; this.sessionTitle = run.sessionTitle;
        this.store = new SettingsStore(app);
        this.runs = RunManager.get(app);
    }

    void setListener(Listener l) { listener = l; }

    void stop() {
        if (stopped) return;
        RunManager.LiveRun run = runs.active(profileId, sessionId);
        if (run != null) runs.stop(run);
        finish("Stopped", false, false);
    }

    // ── steps ───────────────────────────────────────────────────────────────────────────────────

    private void runStep(RunManager.LiveRun run, List<DeviceActions.Action> actions) {
        if (stopped) return;
        Log.i("PhoneAgent", "step " + (step + 1) + ": " + actions.size() + " action(s) from Hermes");
        run.actionsHandled = true;
        lastRun = run;
        String earlyEnd = earlyEndReason(run);
        if (earlyEnd != null) { finish("Ended early: " + earlyEnd, false, false); return; }
        switch (stopReason(!actions.isEmpty(), false, store.agentPaused(), step, store.agentSteps(), false)) {
            case DONE: finish(run.finalText(), true, false); return;
            case PAUSED: finish("Paused in the permission centre", false, false); return;
            case CAP: finish("Reached the step limit (" + store.agentSteps() + " steps)", false, false); return;
            default:
        }
        step++;
        fastStepsThisTurn = 0;
        runActions(run, actions, 0, new ArrayList<>());
    }

    private void runActions(RunManager.LiveRun run, List<DeviceActions.Action> actions, int i, List<DeviceActionRunner.Outcome> results) {
        if (stopped) return;
        if (i == actions.size()) { afterActions(run, results); return; }
        DeviceActions.Action action = actions.get(i);
        boolean tierAsk = DeviceActions.needsConfirmation(action, store);
        // Smart Ask (experiment): for agent screen steps in Ask mode, let Jev judge whether this
        // particular step is risky enough to still need a confirmation, instead of always asking.
        if (tierAsk && !hubUrl.isEmpty() && store.smartAsk() && DeviceActions.isScreenStep(action.type)) { smartAsk(run, actions, i, results, action); return; }
        proceedAction(run, actions, i, results, action, tierAsk, false);
    }

    private void proceedAction(RunManager.LiveRun run, List<DeviceActions.Action> actions, int i, List<DeviceActionRunner.Outcome> results, DeviceActions.Action action, boolean ask, boolean policyApproved) {
        if (stopped) return;
        Listener l = listener;
        if (l != null) l.onStep(step, store.agentSteps(), action, ask);
        String replyKey = ReplyKeys.key(sessionId, run.finalText());
        if (ask) {
            confirm(action, () -> runAction(run, actions, i, results, true, replyKey), () -> {
                results.add(DeviceActionRunner.refused(action, "Stopped by user"));
                runActions(run, actions, i + 1, results);
            });
            return;
        }
        runAction(run, actions, i, results, policyApproved, replyKey);
    }

    private void smartAsk(RunManager.LiveRun run, List<DeviceActions.Action> actions, int i, List<DeviceActionRunner.Outcome> results, DeviceActions.Action action) {
        worker.execute(() -> {
            JSONObject answers = null;
            try { answers = new JevClient(hubUrl, hubKey).decide(smartAskRequest(action, HermesAccessibilityService.currentApp()), sessionId); }
            catch (Exception ignored) { }
            boolean confirmNeeded = smartAskShouldConfirm(smartAskRisk(answers));
            main.post(() -> {
                boolean ask = confirmNeeded || !store.smartAsk();
                proceedAction(run, actions, i, results, action, ask, !ask);
            });
        });
    }

    private void runAction(RunManager.LiveRun run, List<DeviceActions.Action> actions, int i, List<DeviceActionRunner.Outcome> results, boolean approved, String replyKey) {
        if (stopped) return;
        DeviceActions.Action action = actions.get(i);
        Listener l = listener;
        Rect bounds = l == null || !"tap".equals(action.type) ? null
                : action.hasBounds() ? boundsFrom(action)
                : !action.id().isEmpty() ? HermesAccessibilityService.boundsForId(action.id())
                : HermesAccessibilityService.boundsOf(action.str("target"));
        if (bounds != null) l.onBeforeTap(bounds, () -> executeAction(run, actions, i, results, approved, replyKey));
        else executeAction(run, actions, i, results, approved, replyKey);
    }

    private void executeAction(RunManager.LiveRun run, List<DeviceActions.Action> actions, int i, List<DeviceActionRunner.Outcome> results, boolean approved, String replyKey) {
        if (stopped) return;
        DeviceActions.Action action = actions.get(i);
        worker.execute(() -> {
            if (stopped) return;
            List<DeviceActionRunner.Outcome> out = approved
                    ? DeviceActionRunner.runAll(app, Collections.singletonList(action), sessionId, replyKey, true)
                    : DeviceActionRunner.runAll(app, Collections.singletonList(action), sessionId, replyKey);
            List<DeviceActionRunner.Outcome> tagged = action.fastStep ? tagFastStep(out) : out;
            main.post(() -> {
                if (!stopped) {
                    results.addAll(tagged);
                    for (DeviceActionRunner.Outcome o : tagged) doneHistory.add((o.ok ? "✓ " : "✕ ") + o.message);
                    runActions(run, actions, i + 1, results);
                }
            });
        });
    }

    private static Rect boundsFrom(DeviceActions.Action action) {
        int[] b = action.bounds();
        return new Rect(b[0], b[1], b[2], b[3]);
    }

    private static List<DeviceActionRunner.Outcome> tagFastStep(List<DeviceActionRunner.Outcome> out) {
        List<DeviceActionRunner.Outcome> tagged = new ArrayList<>(out.size());
        for (DeviceActionRunner.Outcome o : out) tagged.add(new DeviceActionRunner.Outcome(o.action, o.ok, o.message + " (fast step)").withResult(o.result));
        return tagged;
    }

    /** Ask mode: pause for the overlay, falling back to the existing Confirm flow, or stop. */
    private void confirm(DeviceActions.Action action, Runnable allow, Runnable deny) {
        Listener l = listener;
        if (l != null) { l.onPendingConfirmation(action, allow, deny); return; }
        Activity a = fallbackActivity;
        if (a != null && profileId.equals(fallbackProfileId) && sessionId.equals(fallbackSessionId) && !a.isFinishing()) {
            Confirm.strong(a, action.describe(), "Allow Hermes to do this on your phone?", allow, deny);
            return;
        }
        finish("Needs your OK — open Hermes", false, false);
    }

    private void afterActions(RunManager.LiveRun run, List<DeviceActionRunner.Outcome> results) {
        if (stopped) return;
        if (!store.allowScreenContent()) { finish("Needs Screen content on in Settings to see what happened.", false, true); return; }
        if (!HermesAccessibilityService.enabled()) { finish(store.screenControlHint(), false, false); return; }
        settle(run, results, new SettleWatcher());
    }

    private void settle(RunManager.LiveRun run, List<DeviceActionRunner.Outcome> results, SettleWatcher watcher) {
        if (stopped) return;
        String text = HermesAccessibilityService.describeScreen();
        if (watcher.sample(SystemClock.elapsedRealtime(), text)) { observe(run, results, text); return; }
        main.postDelayed(() -> settle(run, results, watcher), 150);
    }

    private void observe(RunManager.LiveRun run, List<DeviceActionRunner.Outcome> results, String screen) {
        if (stopped) return;
        if (stuck.sample(screen)) { finish("Stuck — the screen isn't changing.", false, false); return; }
        pendingRun = run; pendingResults = results; pendingScreen = screen;
        if (stuck.repeats != 2 && canFastStep()) tryFastStep(run, results, screen);
        else finishObservation(run, results, screen);
    }

    private void finishObservation(RunManager.LiveRun run, List<DeviceActionRunner.Outcome> results, String screen) {
        if (stopped) return;
        if (!imagesRejected && store.sendScreenshots() && store.allowScreenContent() && HermesAccessibilityService.canScreenshot()) {
            HermesAccessibilityService.screenshot(url -> main.post(() -> sendFollowUp(run, results, screen, url)));
        } else {
            sendFollowUp(run, results, screen, null);
        }
    }

    // ── Jev fast steps (experiment): a few System-One decisions between Hermes turns ──────────────

    private boolean canFastStep() {
        return !hubUrl.isEmpty() && store.fastSteps() && HermesAccessibilityService.enabled() && fastStepsThisTurn < FAST_STEPS_PER_TURN
                && stopReason(true, false, store.agentPaused(), step, store.agentSteps(), false) == StopReason.NONE;
    }

    private void tryFastStep(RunManager.LiveRun run, List<DeviceActionRunner.Outcome> results, String screen) {
        long startedAt = SystemClock.elapsedRealtime();
        List<ScreenNodes.Element> elements = HermesAccessibilityService.elementSnapshot(FAST_STEP_ELEMENT_LIMIT);
        if (elements.isEmpty()) { finishObservation(run, results, screen); return; } // nothing for Jev to pick
        String appPkg = HermesAccessibilityService.currentApp();
        String plan = run.finalText();
        worker.execute(() -> {
            JSONObject answers = null;
            try { answers = new JevClient(hubUrl, hubKey).decide(fastStepRequest(originalTask, plan, doneHistory, appPkg, elements), sessionId); }
            catch (Exception ignored) { }
            JSONObject a = answers;
            main.post(() -> { if (!stopped) applyFastStep(run, results, screen, elements, a, startedAt); });
        });
    }

    private void applyFastStep(RunManager.LiveRun run, List<DeviceActionRunner.Outcome> results, String screen, List<ScreenNodes.Element> elements, JSONObject answers, long startedAt) {
        if (stopped) return;
        JSONObject opAnswer = answers == null ? null : answers.optJSONObject("op");
        JSONObject targetAnswer = answers == null ? null : answers.optJSONObject("target");
        String op = opAnswer == null ? "" : opAnswer.optString("choice", "");
        double opConfidence = opAnswer == null ? 0 : opAnswer.optDouble("confidence", 0);
        String targetId = targetAnswer == null ? "" : targetAnswer.optString("choice", "");
        double targetConfidence = targetAnswer == null ? 0 : targetAnswer.optDouble("confidence", 0);
        Log.i("PhoneAgent", "jev fast step: op=" + op + " opConfidence=" + opConfidence
                + " target=" + targetId + " targetConfidence=" + targetConfidence
                + " elapsedMs=" + (SystemClock.elapsedRealtime() - startedAt));
        FastStepOp decision = fastStepDecision(op, opConfidence, targetId, targetConfidence);
        ScreenNodes.Element chosen = decision == FastStepOp.CLICK ? findElement(elements, targetId) : null;
        if (decision == FastStepOp.STOP || (decision == FastStepOp.CLICK && chosen == null)) { finishObservation(run, results, screen); return; }
        DeviceActions.Action action;
        try { action = fastStepAction(decision, chosen); }
        catch (JSONException e) { finishObservation(run, results, screen); return; }
        fastStepsThisTurn++;
        step++;
        runActions(run, Collections.singletonList(action), 0, results);
    }

    private void sendFollowUp(RunManager.LiveRun run, List<DeviceActionRunner.Outcome> results, String screen, String screenshotUrl) {
        if (stopped) return;
        String text = followUpMessage(step, results, screen, stuck.repeats == 2);
        if (imagesRejected) text += "\n\n(Screenshots turned off after the server rejected an image; continuing with text only.)";
        Object payload = screenshotUrl == null ? text : ChatActivity.payload(text, Collections.singletonList(screenshotUrl));
        String systemMessage = DeviceContext.systemMessage(app, store, run.voice);
        lastFollowUpHadImage = screenshotUrl != null;
        RunManager.LiveRun next = runs.start(api, profileId, sessionId, sessionTitle, payload, systemMessage, text, false);
        if (next == null) { finish("Hermes is already working on this task.", false, false); return; }
        next.voice = run.voice;
        lastRun = next;
    }

    // ── RunManager.Observer: drives the loop for follow-ups this agent started ────────────────────

    @Override public void onRunUpdate(RunManager.LiveRun run, String kind) {
        if (stopped || !run.belongsTo(profileId, sessionId)) return;
        if (RunManager.UPDATE_APPROVAL.equals(kind) && run.approval != null) { finish("Needs your approval — open Hermes", false, false); return; }
        if (RunManager.UPDATE_ERROR.equals(kind)) {
            if (lastFollowUpHadImage && !imagesRejected && looksLikeImageRejection(run.error) && pendingRun != null) {
                imagesRejected = true; lastFollowUpHadImage = false;
                sendFollowUp(pendingRun, pendingResults, pendingScreen, null);
                return;
            }
            finish("Ended early: " + earlyEndReason(run), false, false);
            return;
        }
        if (RunManager.UPDATE_FINISHED.equals(kind) && !run.actionsHandled) runStep(run, new ArrayList<>(run.pendingActions));
    }

    private void finish(String summary, boolean ok, boolean needsScreenContent) {
        if (stopped) return;
        Log.i("PhoneAgent", "finish ok=" + ok + " step=" + step + ": " + summary);
        stopped = true;
        synchronized (PhoneAgent.class) { if (active == this) active = null; }
        runs.removeObserver(this);
        main.removeCallbacksAndMessages(null);
        worker.close();
        RunManager.LiveRun run = lastRun;
        if (run != null) { run.actionsHandled = true; if (!ok) run.agentFailure = summary; }
        Listener l = listener;
        if (l != null) l.onFinished(summary, ok, needsScreenContent);
        if (run != null && !runs.isVisible(profileId, sessionId)) Notifier.result(app, run);
    }

    // ── pure parts: unit-tested directly ───────────────────────────────────────────────────────────

    static String earlyEndReason(RunManager.LiveRun run) {
        if (run.error != null) return run.error.trim().isEmpty() ? "error" : run.error;
        HermesApi.RunOutcome outcome = run.outcome;
        if (outcome == null) return "no reply";
        if (!outcome.completed()) {
            if ("cancelled".equals(outcome.status) || "stopped".equals(outcome.status)) return "stopped";
            if ("iteration_budget".equals(outcome.turnExitReason) || "step_limit".equals(outcome.turnExitReason)) return "step limit";
            return outcome.turnExitReason.isEmpty() ? "error" : outcome.turnExitReason.replace('_', ' ');
        }
        return run.finalText().trim().isEmpty() && run.pendingActions.isEmpty() ? "no reply" : null;
    }

    /** True when a task's first action would land on Hermes' own app: it should press Home first,
     *  so the user's previous app (or the launcher) is what the agent actually sees. */
    static boolean shouldGoHomeFirst(String currentAppPackage, String ownPackage, List<DeviceActions.Action> actions) {
        if (currentAppPackage == null || !currentAppPackage.equals(ownPackage)) return false;
        if (actions == null || actions.isEmpty()) return false;
        String first = actions.get(0).type;
        return !"open_app".equals(first) && !"home".equals(first);
    }

    /** Which model id to pin the task's conversation to before it starts, or null to leave it
     *  alone: the configured Agent model setting, else {@link #DEFAULT_AGENT_MODEL} when the
     *  gateway offers it, else null (keep the conversation's current model). Never repeats the
     *  model the conversation is already on. */
    static String desiredAgentModel(String configured, List<HermesApi.ModelOption> options, String currentModel) {
        String want = configured != null && !configured.trim().isEmpty() ? configured.trim() : null;
        if (want == null) want = defaultOption(options);
        return want == null || want.equalsIgnoreCase(currentModel) ? null : want;
    }

    /** The offered id for the default model: exact match first, else the same model under another provider's
     *  naming ("gemini-3-flash-preview" on Copilot for "google/gemini-3-flash-preview"). */
    static String defaultOption(List<HermesApi.ModelOption> options) {
        List<HermesApi.ModelOption> all = options == null ? Collections.<HermesApi.ModelOption>emptyList() : options;
        for (HermesApi.ModelOption o : all) if ("anthropic".equalsIgnoreCase(o.provider)) return DEFAULT_AGENT_MODEL;
        // Claude Code and Codex bridges name their own models: take their small, fast one.
        for (String small : new String[]{"haiku", "mini", "flash"})
            for (HermesApi.ModelOption o : all) if (o.id.toLowerCase(java.util.Locale.ROOT).contains(small)) return o.id;
        for (String want : new String[]{PREVIOUS_AGENT_MODEL, FALLBACK_AGENT_MODEL}) {
            String bare = want.substring(want.lastIndexOf('/') + 1);
            for (HermesApi.ModelOption o : all) if (want.equalsIgnoreCase(o.id)) return o.id;
            for (HermesApi.ModelOption o : all) if (o.id.equalsIgnoreCase(bare) || o.id.toLowerCase(java.util.Locale.ROOT).endsWith("/" + bare)) return o.id;
        }
        return null;
    }

    /** Blocking: pin {@code sessionId} to the configured (or default) assistant model when it differs. */
    static void pinModel(AgentBackend api, String sessionId, String configured) throws Exception {
        List<HermesApi.ModelOption> options = api.modelOptions();
        HermesApi.SessionInfo info = api.getSession(sessionId);
        String want = desiredAgentModel(configured, options, info == null ? "" : info.model);
        if (want != null) api.setSessionModel(sessionId, providerFor(want, options), want);
    }

    /** The provider slug {@code modelId} was offered under, or "" when it isn't in {@code options}. */
    static String providerFor(String modelId, List<HermesApi.ModelOption> options) {
        if (options != null) for (HermesApi.ModelOption o : options) if (o.id.equalsIgnoreCase(modelId)) return o.provider;
        if (DEFAULT_AGENT_MODEL.equalsIgnoreCase(modelId) && options != null)
            for (HermesApi.ModelOption o : options) if ("anthropic".equalsIgnoreCase(o.provider)) return o.provider;
        return "";
    }

    enum StopReason { NONE, STOPPED, PAUSED, DONE, CAP, STUCK }

    /** No Android/network dependency: how the loop decides to end a task. */
    static StopReason stopReason(boolean hasActions, boolean userStopped, boolean paused, int stepsTaken, int cap, boolean stuck) {
        if (userStopped) return StopReason.STOPPED;
        if (paused) return StopReason.PAUSED;
        if (!hasActions) return StopReason.DONE;
        if (stuck) return StopReason.STUCK;
        if (stepsTaken >= cap) return StopReason.CAP;
        return StopReason.NONE;
    }

    /** What a fast step should do next, decided from Jev's answers; STOP hands back to Hermes. */
    enum FastStepOp { CLICK, SCROLL_DOWN, SCROLL_UP, BACK, STOP }

    /** Accept only a confident op (and, for a click, a confident target); anything else — including
     *  "done"/"planner", an unrecognized op, or a missing answer — stops fast steps for this turn. */
    static FastStepOp fastStepDecision(String op, double opConfidence, String target, double targetConfidence) {
        if (opConfidence < OP_CONFIDENCE_MIN) return FastStepOp.STOP;
        switch (op == null ? "" : op) {
            case "click": return targetConfidence >= TARGET_CONFIDENCE_MIN ? FastStepOp.CLICK : FastStepOp.STOP;
            case "scroll_down": return FastStepOp.SCROLL_DOWN;
            case "scroll_up": return FastStepOp.SCROLL_UP;
            // "back" stays with Hermes: a wrong back undoes progress (seen: two confident backs right after opening Settings).
            default: return FastStepOp.STOP; // "done", "planner", "back", or anything unrecognized
        }
    }

    private static ScreenNodes.Element findElement(List<ScreenNodes.Element> elements, String id) {
        for (ScreenNodes.Element e : elements) if (e.id.equals(id)) return e;
        return null;
    }

    /** The device action a fast step's decision maps to, executed the same way as a Hermes-requested one. */
    static DeviceActions.Action fastStepAction(FastStepOp decision, ScreenNodes.Element chosen) throws JSONException {
        JSONObject args = new JSONObject();
        String type;
        switch (decision) {
            case CLICK:
                type = "tap";
                args.put("target", fastStepLabel(chosen)).put("left", chosen.left).put("top", chosen.top)
                        .put("right", chosen.right).put("bottom", chosen.bottom);
                break;
            case SCROLL_DOWN: type = "scroll"; args.put("direction", "down"); break;
            case SCROLL_UP: type = "scroll"; args.put("direction", "up"); break;
            default: type = "back";
        }
        DeviceActions.Action action = new DeviceActions.Action(type, args);
        action.fastStep = true;
        return action;
    }

    private static String fastStepLabel(ScreenNodes.Element e) {
        if (!e.text.isEmpty()) return e.text;
        if (!e.description.isEmpty()) return e.description;
        if (!e.viewId.isEmpty()) return e.viewId;
        return e.id;
    }

    /** Up to 60 elements, one indexed line each, for the "elements" field of Jev's state. */
    static String elementsTableText(List<ScreenNodes.Element> elements) {
        StringBuilder b = new StringBuilder();
        int limit = Math.min(elements.size(), FAST_STEP_ELEMENT_LIMIT);
        for (int i = 0; i < limit; i++) b.append(elements.get(i).id).append(": ").append(elements.get(i).describe()).append('\n');
        return b.toString();
    }

    /** {@code target}'s choice criteria: each element id mapped to its short description. */
    static JSONObject elementCriteria(List<ScreenNodes.Element> elements) throws JSONException {
        JSONObject criteria = new JSONObject();
        int limit = Math.min(elements.size(), FAST_STEP_ELEMENT_LIMIT);
        for (int i = 0; i < limit; i++) criteria.put(elements.get(i).id, elements.get(i).describe());
        return criteria;
    }

    /** The full /jev/decisions request body for one fast step's "op"/"target" questions. */
    static JSONObject fastStepRequest(String task, String plan, List<String> doneSoFar, String app, List<ScreenNodes.Element> elements) throws JSONException {
        JSONObject state = new JSONObject().put("task", task == null ? "" : task).put("plan", plan == null ? "" : plan)
                .put("done", String.join("\n", doneSoFar)).put("app", app == null ? "" : app)
                .put("elements", elementsTableText(elements));
        JSONObject op = new JSONObject().put("type", "choice").put("instructions", "Pick the next step to move the plan forward on this screen.")
                .put("criteria", new JSONObject()
                        .put("click", "tap one element to move the plan forward")
                        .put("scroll_down", "scroll down to see more of the screen")
                        .put("scroll_up", "scroll up to see more of the screen")
                        .put("done", "the plan's current goal is already reached on this screen")
                        .put("planner", "needs typing, a choice, information, or anything unclear — hand back to Hermes"));
        JSONObject target = new JSONObject().put("type", "choice").put("instructions", "Which element to tap, if op is click.")
                .put("criteria", elementCriteria(elements));
        JSONObject questions = new JSONObject().put("op", op).put("target", target);
        return JevClient.request(state, questions);
    }

    /** The /jev/decisions request for Smart Ask's single "risk" noul question about one step. */
    static JSONObject smartAskRequest(DeviceActions.Action action, String app) throws JSONException {
        String target = !action.str("target").isEmpty() ? action.str("target") : action.str("direction");
        JSONObject state = new JSONObject().put("step", action.describe()).put("target", target).put("app", app == null ? "" : app);
        JSONObject risk = new JSONObject().put("type", "noul")
                .put("instructions", "Would this step send, pay, buy, delete, post, call someone, or change an important setting?")
                .put("criteria", new JSONObject().put("true", "Yes, it has that kind of effect").put("false", "No, it's safe, reversible or informational"));
        return JevClient.request(state, new JSONObject().put("risk", risk));
    }

    /** null (Jev didn't answer, or failed) means "treat as risky": {@link #smartAskShouldConfirm}. */
    static Double smartAskRisk(JSONObject answers) {
        JSONObject risk = answers == null ? null : answers.optJSONObject("risk");
        return risk == null || !risk.has("noul") ? null : risk.optDouble("noul");
    }

    /** Confirm unless Jev is confident (p < 0.3) that the step isn't risky. */
    static boolean smartAskShouldConfirm(Double riskProbability) {
        return riskProbability == null || riskProbability >= SMART_ASK_RISK_MIN;
    }

    /** The server rejecting an attached screenshot ("this model does not support images", etc.). */
    static boolean looksLikeImageRejection(String error) {
        if (error == null) return false;
        String e = error.toLowerCase(Locale.ROOT);
        return e.contains("image") || e.contains("vision") || e.contains("multimodal");
    }

    /** The "[PHONE RESULTS] step N" follow-up: each action's outcome, then the current screen. */
    static String followUpMessage(int step, List<DeviceActionRunner.Outcome> outcomes, String screen) {
        return followUpMessage(step, outcomes, screen, false);
    }

    static final String DONE_HINT = "If the task is done, answer now with no device actions; don't press back or home to tidy up.";

    static String followUpMessage(int step, List<DeviceActionRunner.Outcome> outcomes, String screen, boolean unchanged) {
        StringBuilder b = new StringBuilder(DeviceActionRunner.RESULTS_PREFIX).append(" step ").append(step).append('\n');
        for (DeviceActionRunner.Outcome o : outcomes) {
            b.append(o.ok ? "✓ " : "✕ ").append(o.message).append('\n');
            // Query data (app list, calendar, contacts…) must reach Hermes; read_screen's is the screen below.
            boolean readScreen = o.action != null && "read_screen".equals(o.action.type);
            if (!readScreen && o.result != null && !o.result.trim().isEmpty())
                b.append(o.result.length() > 2000 ? o.result.substring(0, 2000) + "…" : o.result.trim()).append('\n');
        }
        if (unchanged) {
            b.append("Screen did not change after your last step. Try a different element, scroll, or close any dialog.\n");
            String lower = screen == null ? "" : screen.toLowerCase(Locale.ROOT);
            if (lower.contains("cookie") || lower.contains("consent") || lower.contains("accept all"))
                b.append("A cookie/consent dialog may be blocking; accept or close it first.\n");
        }
        // Seen live: goal reached, then a tidy-up Back exposed another app and the summary described that instead.
        b.append(DONE_HINT).append('\n');
        b.append('\n').append(DeviceContext.screenBlock(screen));
        return b.toString();
    }

    /** Poll-until-stable: true once the same text has held for STABLE_MS, or MAX_MS has elapsed. */
    static final class SettleWatcher {
        static final long STABLE_MS = 300, MAX_MS = 2000;
        private String lastText; private long lastChangeAt = -1, startedAt = -1;
        boolean sample(long nowMs, String text) {
            if (startedAt < 0) { startedAt = lastChangeAt = nowMs; lastText = text; }
            else if (text == null ? lastText != null : !text.equals(lastText)) { lastText = text; lastChangeAt = nowMs; }
            return nowMs - lastChangeAt >= STABLE_MS || nowMs - startedAt >= MAX_MS;
        }
    }

    /** True once the same non-empty screen text has been observed 3 times in a row (no progress). */
    static final class StuckDetector {
        private String lastText; private int repeats;
        boolean sample(String text) {
            if (text != null && text.equals(lastText)) repeats++; else { lastText = text; repeats = 1; }
            return repeats >= 3;
        }
    }
}
