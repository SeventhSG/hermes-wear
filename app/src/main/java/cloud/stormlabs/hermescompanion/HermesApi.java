package cloud.stormlabs.hermescompanion;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Client for the Hermes Agent API server (gateway/platforms/api_server.py).
 *
 * Covers the native surface an external UI needs: sessions, the session chat SSE stream (deltas,
 * reasoning, commentary, tool progress, approvals, terminal run status), run control
 * (stop/steer/approve/status), the cron jobs API, skills/toolsets, model catalog and health.
 */
public class HermesApi implements AgentBackend {
    @Override public Capabilities capabilitiesForUi() { return Capabilities.HERMES; }
    public static final class Message {
        public final long id;
        public final String role, content, toolName;
        public final double timestamp;
        public final List<ToolCall> toolCalls = new ArrayList<>();
        /** Image URLs (data: or http) carried as vision parts on a user message. */
        public final List<String> images = new ArrayList<>();
        public String reasoning = "";
        public boolean pairedToolResult;
        public Message(String role, String content) { this(0, role, content, null, 0); }
        public Message(long id, String role, String content, String toolName, double timestamp) {
            this.id = id; this.role = role; this.content = content == null ? "" : content;
            this.toolName = toolName; this.timestamp = timestamp;
        }
    }

    public static final class ToolCall {
        public final String id, name, arguments;
        public String result = "", status = "running";
        public long resultMessageId;
        ToolCall(String id, String name, String arguments) {
            this.id = id == null ? "" : id;
            this.name = name == null || name.isEmpty() ? "tool" : name;
            this.arguments = arguments == null ? "{}" : arguments;
        }
        public static ToolCall live(String name, String arguments) { return new ToolCall("", name, arguments); }

        /**
         * Arguments for a tool that just started. Hermes' /v1/runs sends only a short preview (no args) until the run
         * ends, so the preview stands in for the command rather than showing an empty card.
         */
        static String startArgs(String name, String preview, String argsJson) {
            if (argsJson != null && !argsJson.isEmpty() && !"{}".equals(argsJson) && !"null".equals(argsJson)) return argsJson;
            if (preview == null || preview.isEmpty()) return "{}";
            try { return new JSONObject().put(isShell(name) ? "command" : "preview", preview).toString(); } catch (Exception e) { return "{}"; }
        }

        static boolean isShell(String name) { return "terminal".equals(name) || "bash".equals(name) || "shell".equals(name) || "exec_command".equals(name); }
        public String summary() {
            try {
                JSONObject o = new JSONObject(arguments);
                for (String k : new String[]{"command", "query", "path", "url", "question", "pattern", "goal", "preview", "name"}) {
                    if (o.has(k)) { String v = o.optString(k); return v.length() > 110 ? v.substring(0, 107) + "…" : v; }
                }
            } catch (Exception ignored) {}
            return arguments.length() > 110 ? arguments.substring(0, 107) + "…" : arguments;
        }
    }

    public static final class SessionInfo {
        public final String id, title, source, model, preview;
        /** A run already working in this session (e.g. a Codex turn started in the terminal), to follow live. */
        public final String activeRunId, activeRunInput;
        public final int messageCount, toolCallCount;
        public final boolean pinned, archived, unread;
        public final double lastActive;
        SessionInfo(JSONObject o) {
            id = o.optString("id"); title = clean(o.optString("title", "")); source = clean(o.optString("source", "api_server"));
            model = clean(o.optString("model", "")); preview = clean(o.optString("preview", ""));
            messageCount = o.optInt("message_count", 0); toolCallCount = o.optInt("tool_call_count", 0);
            pinned = o.optBoolean("pinned", false); archived = o.optBoolean("archived", false);
            unread = o.optBoolean("unread", false);
            lastActive = o.optDouble("last_active", 0);
            activeRunId = clean(o.optString("active_run_id", "")); activeRunInput = o.optString("active_run_input", "");
        }
        public String displayTitle() {
            if (!title.trim().isEmpty()) return title.trim();
            if (!preview.trim().isEmpty()) return preview.trim();
            return "New conversation";
        }
    }

    public static final class Result {
        public final boolean ok; public final String text;
        Result(boolean ok, String text) { this.ok = ok; this.text = text; }
        public static Result ok(String text) { return new Result(true, text); }
        public static Result error(String text) { return new Result(false, text); }
    }

    /** A dangerous-command approval the agent is blocked on (approval.request). */
    public static final class Approval {
        public final String runId, command, description, requestId;
        /** Hermes 0.22+ durable runs: the turn generation the answer must name (-1 = not sent, answer without it). */
        public final int generation;
        public final List<String> choices = new ArrayList<>();
        public Approval(String runId, String command, String description, String requestId, List<String> choices) {
            this(runId, command, description, requestId, choices, -1);
        }
        public Approval(String runId, String command, String description, String requestId, List<String> choices, int generation) {
            this.generation = generation;
            this.runId = runId == null ? "" : runId;
            this.command = command == null ? "" : command;
            this.description = description == null ? "" : description;
            this.requestId = requestId == null ? "" : requestId;
            if (choices != null) this.choices.addAll(choices);
            if (this.choices.isEmpty()) { this.choices.add("once"); this.choices.add("deny"); }
        }
        static Approval parse(String fallbackRunId, JSONObject o) {
            List<String> choices = new ArrayList<>();
            JSONArray c = o.optJSONArray("choices");
            if (c != null) for (int i = 0; i < c.length(); i++) { String v = c.optString(i, ""); if (!v.isEmpty()) choices.add(v); }
            String id = clean(o.optString("request_id", ""));
            if (id.isEmpty()) id = clean(o.optString("prompt_id", ""));   // durable runs call the id prompt_id
            return new Approval(o.optString("run_id", fallbackRunId), clean(o.optString("command", "")),
                    clean(o.optString("description", "")), id, choices, o.optInt("execution_generation", -1));
        }
        public static String label(String choice) {
            switch (choice) {
                case "once": return "Allow once";
                case "session": return "Allow for this session";
                case "always": return "Always allow";
                case "deny": return "Deny";
                default: return choice;
            }
        }
    }

    /** How a run ended: the terminal ``run.<status>`` event plus the final assistant text. */
    public static final class RunOutcome {
        public String status = "completed", finalText = "", turnExitReason = "", pendingSteer = "", sessionId = "";
        public long inputTokens, outputTokens, totalTokens;
        public boolean completed() { return "completed".equals(status); }
        public String usageLine() {
            if (totalTokens <= 0 && inputTokens <= 0 && outputTokens <= 0) return "";
            return String.format(Locale.ROOT, "%s in · %s out", compact(inputTokens), compact(outputTokens));
        }
        static String compact(long n) {
            if (n >= 1_000_000) return String.format(Locale.ROOT, "%.1fM", n / 1_000_000.0);
            if (n >= 10_000) return (n / 1000) + "k";
            if (n >= 1000) return String.format(Locale.ROOT, "%.1fk", n / 1000.0);
            return String.valueOf(n);
        }
    }

    /**
     * Callbacks for one session chat stream. Only the original five are required; everything the
     * newer gateway emits arrives through default methods so simple callers stay simple.
     */
    public interface StreamListener {
        void onRunStarted(String runId);
        void onDelta(String delta);
        void onTool(String state, String name, String preview);
        void onComplete(String finalText);
        void onError(String error);
        default void onToolEvent(String state, String name, String preview, String argsJson) { onTool(state, name, preview); }
        default void onReasoning(String delta) {}
        default void onCommentary(String text) {}
        /** A new assistant message starts (the bridge marks message boundaries); {@code separator} still joins the full text. */
        default void onSegment(String separator) { onDelta(separator); }
        /** A message steered into this run, also one sent from another client. */
        default void onSteer(String text) {}
        default void onApproval(Approval approval) {}
        default void onRunFinished(RunOutcome outcome) {}
        /** The event stream dropped (false) and came back (true); the run keeps going on the server meanwhile. */
        default void onConnection(boolean connected) {}
    }

    /** Lets a caller abort an in-flight stream (only the session stream's run dies with its socket; /v1/runs needs stopRun). */
    public static final class StreamHandle {
        private volatile HttpURLConnection connection;
        private volatile boolean cancelled;
        public void cancel() { cancelled = true; HttpURLConnection c = connection; if (c != null) try { c.disconnect(); } catch (Exception ignored) {} }
        public boolean cancelled() { return cancelled; }
    }

    public static final class Skill {
        public final String name, description, category;
        public Skill(String name, String description, String category) {
            this.name = name == null ? "" : name; this.description = description == null ? "" : description;
            this.category = category == null ? "" : category;
        }
    }

    public static final class Toolset {
        public final String name, description; public final boolean enabled; public final List<String> tools = new ArrayList<>();
        public Toolset(String name, String description, boolean enabled) {
            this.name = name == null ? "" : name; this.description = description == null ? "" : description; this.enabled = enabled;
        }
    }

    /** A cron job from /api/jobs. */
    public static final class Job {
        public final String id, name, prompt, schedule, state, deliver, nextRunAt, lastRunAt, lastStatus, lastError;
        public final boolean enabled;
        public final List<String> skills = new ArrayList<>();
        public final int repeatTimes, repeatCompleted;
        public Job(JSONObject o) {
            id = o.optString("id", "");
            name = clean(o.optString("name", ""));
            prompt = clean(o.optString("prompt", ""));
            String display = clean(o.optString("schedule_display", ""));
            if (display.isEmpty()) {
                Object raw = o.opt("schedule");
                if (raw instanceof JSONObject) display = clean(((JSONObject) raw).optString("display", ((JSONObject) raw).optString("expr", "")));
                else if (raw != null) display = clean(String.valueOf(raw));
            }
            schedule = display;
            enabled = o.optBoolean("enabled", true);
            String s = clean(o.optString("state", ""));
            state = s.isEmpty() ? (enabled ? "scheduled" : "paused") : s;
            deliver = clean(o.optString("deliver", "local"));
            nextRunAt = clean(o.optString("next_run_at", ""));
            lastRunAt = clean(o.optString("last_run_at", ""));
            lastStatus = clean(o.optString("last_status", ""));
            lastError = clean(o.optString("last_error", ""));
            JSONArray sk = o.optJSONArray("skills");
            if (sk != null) for (int i = 0; i < sk.length(); i++) { String v = clean(sk.optString(i, "")); if (!v.isEmpty()) skills.add(v); }
            JSONObject rep = o.optJSONObject("repeat");
            repeatTimes = rep == null || rep.isNull("times") ? 0 : rep.optInt("times", 0);
            repeatCompleted = rep == null ? 0 : rep.optInt("completed", 0);
        }
        public boolean paused() { return "paused".equals(state) || !enabled; }
        public String title() { return name.isEmpty() ? (prompt.length() > 40 ? prompt.substring(0, 40) + "…" : prompt) : name; }
    }

    /** One selectable model from the gateway's provider catalog. */
    public static final class ModelOption {
        public final String provider, id;
        public final boolean current;
        public ModelOption(String provider, String id, boolean current) {
            this.provider = provider == null ? "" : provider;
            this.id = id == null ? "" : id;
            this.current = current;
        }
        /** Provider is carried separately so the picker can group; the row shows the model id alone. */
        public String label() { return id; }
    }

    private final String base, key, memoryScope;
    /** org.json renders a JSON null as the literal string "null", which must never reach the UI. */
    /** A tool event's preview; live output chunks keep their whitespace. */
    static String toolPreview(String event, JSONObject o) {
        String raw = o.optString("preview", "");
        return "tool.output".equals(event) ? raw : clean(raw);
    }

    public static String clean(String value) {
        if (value == null) return "";
        String v = value.trim();
        return ("null".equalsIgnoreCase(v) || "none".equalsIgnoreCase(v)) ? "" : v;
    }
    private int connectMs = 15_000, readMs = 60_000;
    /** Short timeouts for fleet health probes; defaults stay untouched. */
    public HermesApi quick() { connectMs = 4_000; readMs = 4_000; return this; }
    public HermesApi(String base, String key, String memoryScope) {
        this.base = UrlUtils.normalize(base); this.key = key == null ? "" : key.trim();
        this.memoryScope = "companion:" + SessionIds.normalize(memoryScope);
    }

    // ── health & catalog ────────────────────────────────────────────────────────────────────────

    public Result health() {
        try {
            JSONObject o = requestJson("GET", "/v1/capabilities", null);
            return Result.ok(o.optString("platform", "Hermes") + " online");
        } catch (Exception e) { return Result.error(friendly(e)); }
    }

    public JSONObject capabilities() throws Exception { return requestJson("GET", "/v1/capabilities", null); }

    /** The bridge's slash commands as {name, description}. */
    @Override public List<String[]> commandCatalog() throws Exception {
        JSONArray data = requestJson("GET", "/v1/commands", null).optJSONArray("data");
        List<String[]> out = new ArrayList<>();
        if (data != null) for (int i = 0; i < data.length(); i++) {
            JSONObject o = data.optJSONObject(i);
            if (o != null && !o.optString("name").isEmpty()) out.add(new String[]{o.optString("name").replaceFirst("^/", ""), o.optString("description")});
        }
        return out;
    }

    /** Runs a slash command on the bridge: {type: send|run|undo|output|notice, message, run_id, text, output, notice}. */
    @Override public JSONObject runCommand(String sessionId, String command) throws Exception {
        return requestJson("POST", "/api/sessions/" + path(sessionId) + "/command", new JSONObject().put("command", command));
    }
    /** GET /health/detailed — version, readiness, platforms, active agents, today's metrics. */
    public JSONObject healthDetailed() throws Exception { return requestJson("GET", "/health/detailed", null); }

    /** Compatibility path used by the v1 screen while the session UI boots. */
    public Result chat(List<Message> history) {
        try {
            JSONArray messages = new JSONArray();
            for (int i = Math.max(0, history.size() - 20); i < history.size(); i++) {
                Message m = history.get(i);
                messages.put(new JSONObject().put("role", m.role).put("content", m.content));
            }
            JSONObject body = new JSONObject().put("model", "hermes-agent").put("messages", messages).put("stream", false);
            JSONObject root = requestJson("POST", "/v1/chat/completions", body);
            String text = root.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "").trim();
            return text.isEmpty() ? Result.error("Hermes returned an empty response") : Result.ok(text);
        } catch (Exception e) { return Result.error(friendly(e)); }
    }

    // ── sessions ────────────────────────────────────────────────────────────────────────────────

    public List<SessionInfo> listSessions() throws Exception { return listSessions(100, 0); }

    public List<SessionInfo> listSessions(int limit, int offset) throws Exception {
        JSONObject root = requestJson("GET", "/api/sessions?limit=" + Math.max(1, Math.min(200, limit)) + "&offset=" + Math.max(0, offset), null);
        JSONArray data = root.optJSONArray("data"); List<SessionInfo> out = new ArrayList<>();
        if (data != null) for (int i = 0; i < data.length(); i++) {
            SessionInfo item = new SessionInfo(data.getJSONObject(i));
            if (!item.archived) out.add(item);
        }
        return out;
    }

    public SessionInfo getSession(String sessionId) throws Exception {
        JSONObject root = requestJson("GET", "/api/sessions/" + path(sessionId), null);
        JSONObject s = root.optJSONObject("session");
        return new SessionInfo(s == null ? root : s);
    }

    /** Exact-title lookup (the server matches titles exactly); null when no such session exists. */
    public SessionInfo findSessionByTitle(String title) throws Exception {
        JSONObject root = requestJson("GET", "/api/sessions?limit=5&title=" + URLEncoder.encode(title, StandardCharsets.UTF_8.name()), null);
        JSONArray data = root.optJSONArray("data");
        if (data == null) return null;
        for (int i = 0; i < data.length(); i++) {
            SessionInfo s = new SessionInfo(data.getJSONObject(i));
            if (title.equals(s.title)) return s;
        }
        return null;
    }

    public SessionInfo createSession(String title) throws Exception {
        // A placeholder title counts as the user's own name and blocks Hermes' AI titling; leave it untitled.
        if (title == null || AutoTitle.isPlaceholder(title)) {
            return new SessionInfo(requestJson("POST", "/api/sessions", new JSONObject().put("source", "api_server")).getJSONObject("session"));
        }
        String base = (title == null || title.trim().isEmpty()) ? "New conversation" : title.trim();
        // Session titles are UNIQUE server-side (`Title already in use by session X`,
        // code=invalid_title) because they double as canonical addresses. A fixed
        // "New conversation" title therefore only ever works once, so walk forward to
        // the first free suffix instead of failing the create.
        String candidate = base;
        for (int n = 1; n <= 50; n++) {
            JSONObject body = new JSONObject().put("source", "api_server").put("title", candidate);
            try {
                return new SessionInfo(requestJson("POST", "/api/sessions", body).getJSONObject("session"));
            } catch (IllegalStateException e) {
                String msg = e.getMessage() == null ? "" : e.getMessage();
                if (!msg.toLowerCase(Locale.ROOT).contains("already in use")) throw e;
                candidate = base + " " + (n + 1);
            }
        }
        throw new IllegalStateException("Could not find a free title for " + base);
    }

    /** Find the session with this exact title, creating it when missing (used for the assistant lane). */
    public SessionInfo ensureSession(String title) throws Exception {
        try { SessionInfo found = findSessionByTitle(title); if (found != null) return found; }
        catch (Exception ignored) { /* older servers without title filtering fall through to create */ }
        return createSession(title);
    }

    public SessionInfo createSessionWithId(String id, String title) throws Exception {
        JSONObject body = new JSONObject().put("source", "api_server").put("id", SessionIds.normalize(id));
        if (title != null && !title.trim().isEmpty()) body.put("title", title.trim());
        JSONObject root = requestJson("POST", "/api/sessions", body);
        return new SessionInfo(root.getJSONObject("session"));
    }

    public SessionInfo forkSession(String sessionId, String title) throws Exception {
        JSONObject body = new JSONObject(); if (title != null && !title.trim().isEmpty()) body.put("title", title.trim());
        JSONObject root = requestJson("POST", "/api/sessions/" + path(sessionId) + "/fork", body);
        return new SessionInfo(root.getJSONObject("session"));
    }

    public Result renameSession(String sessionId, String title) {
        try { requestJson("PATCH", "/api/sessions/" + path(sessionId), new JSONObject().put("title", title.trim())); return Result.ok("Renamed"); }
        catch (Exception e) { return Result.error(friendly(e)); }
    }

    public Result deleteSession(String sessionId) {
        try { requestJson("DELETE", "/api/sessions/" + path(sessionId), null); return Result.ok("Deleted"); }
        catch (Exception e) { return Result.error(friendly(e)); }
    }

    /** PATCH /api/sessions/{id} — pinned conversations sort to the top of the drawer. */
    public Result pinSession(String sessionId, boolean pinned) {
        return patchFlag(sessionId, "pinned", pinned, pinned ? "Pinned" : "Unpinned");
    }
    /** Archived sessions drop out of the list but keep their transcript server-side. */
    public Result archiveSession(String sessionId, boolean archived) {
        return patchFlag(sessionId, "archived", archived, archived ? "Archived" : "Restored");
    }
    public Result markUnread(String sessionId, boolean unread) {
        return patchFlag(sessionId, "unread", unread, unread ? "Marked unread" : "Marked read");
    }
    private Result patchFlag(String sessionId, String flag, boolean value, String okText) {
        try { requestJson("PATCH", "/api/sessions/" + path(sessionId), new JSONObject().put(flag, value)); return Result.ok(okText); }
        catch (Exception e) { return Result.error(friendly(e)); }
    }

    // ── models ──────────────────────────────────────────────────────────────────────────────────

    /** GET /api/model/options — only authenticated providers can actually serve a model. */
    public List<ModelOption> modelOptions() throws Exception {
        JSONObject root = requestJson("GET", "/api/model/options", null);
        return parseModelOptions(root);
    }

    static List<ModelOption> parseModelOptions(JSONObject root) {
        String currentProvider = root.optString("provider", "");
        String currentModel = root.optString("model", "");
        JSONArray providers = root.optJSONArray("providers");
        List<ModelOption> out = new ArrayList<>();
        if (providers == null) return out;
        for (int i = 0; i < providers.length(); i++) {
            JSONObject p = providers.optJSONObject(i);
            if (p == null) continue;
            String slug = p.optString("slug", "");
            if (!p.optBoolean("authenticated", false)) continue;
            JSONArray models = p.optJSONArray("models");
            if (models == null) continue;
            for (int j = 0; j < models.length(); j++) {
                Object raw = models.opt(j);
                String id = raw instanceof JSONObject ? ((JSONObject) raw).optString("id", "") : raw == null ? "" : String.valueOf(raw).trim();
                if (id.isEmpty()) continue;
                out.add(new ModelOption(slug, id,
                        id.equalsIgnoreCase(currentModel) && slug.equalsIgnoreCase(currentProvider)));
            }
        }
        return out;
    }

    /** POST /api/sessions/{id}/model — pin this conversation to a model, server-side. */
    public Result setSessionModel(String sessionId, String provider, String model) {
        routeCache.remove(base + "|" + sessionId);
        try {
            JSONObject body = new JSONObject().put("model", model == null ? "" : model.trim());
            if (provider != null && !provider.trim().isEmpty()) body.put("provider", provider.trim());
            requestJson("POST", "/api/sessions/" + path(sessionId) + "/model", body);
            return Result.ok("Model set to " + model);
        } catch (Exception e) { return Result.error(friendly(e)); }
    }

    /** Where effort and permissions live for plain Hermes, whose gateway has no per-session settings route. */
    interface SettingsSource {
        JSONObject get(String sessionId) throws Exception;
        Result set(String sessionId, JSONObject fields);
        /** Extra {@code model_options} for the next run in this conversation, or null. */
        JSONObject runOptions(String sessionId);
    }
    volatile SettingsSource settingsSource;

    @Override public boolean supportsAgentSettings() { return settingsSource != null; }

    @Override public JSONObject getSessionSettings(String sessionId) throws Exception {
        if (!supportsAgentSettings()) throw new UnsupportedOperationException("Agent settings are unavailable for this server");
        SettingsSource local = settingsSource;
        if (local != null) return local.get(sessionId);
        return requestJson("GET", "/api/sessions/" + path(sessionId) + "/settings", null);
    }

    @Override public Result setSessionSettings(String sessionId, JSONObject fields) {
        if (!supportsAgentSettings()) return Result.error("Agent settings are unavailable for this server");
        SettingsSource local = settingsSource;
        if (local != null) return local.set(sessionId, fields);
        try {
            JSONObject result = requestJson("POST", "/api/sessions/" + path(sessionId) + "/settings", fields);
            return Result.ok(result.optString("scope", "Changes apply to your next message in this conversation."));
        } catch (Exception e) { return Result.error(friendly(e)); }
    }

    // ── messages ────────────────────────────────────────────────────────────────────────────────

    public List<Message> messages(String sessionId) throws Exception { return messages(sessionId, 120, 0); }

    /** Newest-first page of {@code limit} messages starting {@code offset} back from the latest, returned oldest-first. */
    public List<Message> messages(String sessionId, int limit, int offset) throws Exception {
        JSONObject root = requestJson("GET", "/api/sessions/" + path(sessionId) + "/messages?limit=" + limit + "&offset=" + offset + "&order=latest", null);
        return parseMessages(root.optJSONArray("data"));
    }

    static List<Message> parseMessages(JSONArray data) throws Exception {
        List<Message> out = new ArrayList<>();
        Map<String, ToolCall> calls = new LinkedHashMap<>();
        Map<Long, String> toolCallIds = new LinkedHashMap<>();
        if (data == null) return out;
        for (int i = 0; i < data.length(); i++) {
            JSONObject o = data.getJSONObject(i); String role = o.optString("role", "assistant");
            Object rawContent = o.opt("content");
            List<String> images = new ArrayList<>();
            String content = contentText(rawContent, images);
            Message parsed = new Message(o.optLong("id"), role, content, o.isNull("tool_name") ? null : o.optString("tool_name", null), o.optDouble("timestamp", 0));
            parsed.images.addAll(images);
            String reasoning = clean(o.optString("reasoning", ""));
            if (reasoning.isEmpty()) reasoning = clean(o.optString("reasoning_content", ""));
            parsed.reasoning = reasoning;
            JSONArray tc = o.optJSONArray("tool_calls");
            if (tc != null) for (int j = 0; j < tc.length(); j++) {
                JSONObject raw = tc.optJSONObject(j); if (raw == null) continue; JSONObject fn = raw.optJSONObject("function");
                String callId = raw.optString("call_id", raw.optString("id", ""));
                ToolCall call = new ToolCall(callId, fn == null ? raw.optString("name", "tool") : fn.optString("name", "tool"), fn == null ? raw.optString("arguments", "{}") : fn.optString("arguments", "{}"));
                parsed.toolCalls.add(call); calls.put(callId, call);
            }
            if ("tool".equals(role)) toolCallIds.put(parsed.id, o.optString("tool_call_id", ""));
            if (!parsed.content.isEmpty() || !parsed.images.isEmpty() || parsed.toolName != null || !parsed.toolCalls.isEmpty()) out.add(parsed);
        }
        for (Message m : out) {
            if (!"tool".equals(m.role)) continue;
            String callId = toolCallIds.get(m.id); if (callId == null) callId = "";
            ToolCall call = calls.get(callId);
            if (call != null) { call.result = m.content; call.resultMessageId = m.id; call.status = detectToolStatus(m.content); m.pairedToolResult = true; }
            else { ToolCall standalone = new ToolCall(callId, m.toolName, "{}"); standalone.result = m.content; standalone.status = detectToolStatus(m.content); m.toolCalls.add(standalone); }
        }
        // Any call that never got a result row is not "running" once it is history.
        for (ToolCall c : calls.values()) if ("running".equals(c.status) && c.resultMessageId == 0) c.status = "completed";
        return out;
    }

    /** Flatten string-or-parts content; image parts are collected separately so they can be drawn. */
    static String contentText(Object raw, List<String> imagesOut) {
        if (raw == null || raw == JSONObject.NULL) return "";
        if (raw instanceof String) {
            String s = (String) raw;
            String trimmed = s.trim();
            if (trimmed.startsWith("[{") && trimmed.endsWith("}]")) {
                try { return contentText(new JSONArray(trimmed), imagesOut); } catch (Exception ignored) {}
            }
            return s;
        }
        if (raw instanceof JSONArray) {
            JSONArray parts = (JSONArray) raw; StringBuilder b = new StringBuilder();
            for (int i = 0; i < parts.length(); i++) {
                Object p = parts.opt(i);
                if (p instanceof String) { if (b.length() > 0) b.append('\n'); b.append((String) p); continue; }
                if (!(p instanceof JSONObject)) continue;
                JSONObject part = (JSONObject) p; String type = part.optString("type", "");
                if ("text".equals(type) || "input_text".equals(type) || "output_text".equals(type)) {
                    String t = part.optString("text", ""); if (!t.isEmpty()) { if (b.length() > 0) b.append('\n'); b.append(t); }
                } else if ("image_url".equals(type) || "input_image".equals(type)) {
                    Object ref = part.opt("image_url"); String url = ref instanceof JSONObject ? ((JSONObject) ref).optString("url", "") : ref == null ? "" : String.valueOf(ref);
                    if (!url.isEmpty() && imagesOut != null) imagesOut.add(url);
                }
            }
            return b.toString();
        }
        return String.valueOf(raw);
    }

    private static String detectToolStatus(String result) {
        try { JSONObject o = new JSONObject(result); if (o.has("exit_code") && o.optInt("exit_code", 0) != 0) return "failed"; if (o.has("error") && !o.isNull("error") && !o.optString("error").isEmpty()) return "failed"; }
        catch (Exception ignored) {}
        return "completed";
    }

    /** POST /v1/artifacts/upload — raw bytes plus a filename header; returns the artifact id. */
    public String uploadArtifact(String filename, String mimeType, byte[] data) throws Exception {
        HttpURLConnection c = open("POST", "/v1/artifacts/upload");
        c.setRequestProperty("Content-Type", mimeType == null || mimeType.isEmpty() ? "application/octet-stream" : mimeType);
        c.setRequestProperty("X-Artifact-Filename", filename == null || filename.isEmpty() ? "file" : filename);
        c.setDoOutput(true);
        try (OutputStream out = c.getOutputStream()) { out.write(data); }
        int code = c.getResponseCode();
        String body = read(c, code);
        if (code < 200 || code >= 300) throw new IllegalStateException(errorText(code, body));
        JSONObject o = new JSONObject(body);
        String id = o.optString("artifact_id", "");
        if (id.isEmpty()) {
            JSONObject receipt = o.optJSONObject("receipt");
            if (receipt != null) id = receipt.optString("artifact_id", "");
        }
        return id;
    }

    // ── chat stream ─────────────────────────────────────────────────────────────────────────────

    public void streamChat(String sessionId, Object input, StreamListener listener) {
        streamChat(sessionId, input, null, null, listener);
    }

    /**
     * POST /api/sessions/{id}/chat/stream. {@code systemMessage} rides as an ephemeral system prompt
     * for this turn only (device context and device-action instructions), never stored in history.
     */
    public void streamChat(String sessionId, Object input, String systemMessage, StreamHandle handle, StreamListener listener) {
        // Text turns run through the durable /v1/runs API, like Hermes Desktop's gateway: the run lives on
        // the server whatever happens to the socket, and the app re-attaches and replays what it missed.
        // Image turns (and servers without /v1/runs) keep the session stream, which dies with its socket.
        if (streamRun(sessionId, input, systemMessage, handle, listener)) return;
        streamSessionChat(sessionId, input, systemMessage, handle, listener);
    }

    private volatile List<ModelOption> optionsCache;
    private volatile long optionsAt;

    /** Per-chat route, so a send doesn't wait on two lookups; dropped when the model changes. */
    private static final java.util.Map<String, Object[]> routeCache = new java.util.concurrent.ConcurrentHashMap<>();

    /** The model (and its provider, when only one offers it) a chat is pinned to, or null for the gateway default. */
    String[] sessionRoute(String sessionId) {
        Object[] hit = routeCache.get(base + "|" + sessionId);
        if (hit != null && System.currentTimeMillis() - (Long) hit[0] < 120_000) return (String[]) hit[1];
        String[] route = lookupRoute(sessionId);
        routeCache.put(base + "|" + sessionId, new Object[]{System.currentTimeMillis(), route});
        return route;
    }

    /** After anything that may re-pin models server-side (/model and other slash commands). */
    static void forgetRoutes() { routeCache.clear(); }

    private String[] lookupRoute(String sessionId) {
        try {
            JSONObject o = requestJson("GET", "/api/sessions/" + path(sessionId), null);
            JSONObject s = o.optJSONObject("session"); if (s == null) s = o;
            String model = clean(s.optString("model", ""));
            if (model.isEmpty() || !s.optBoolean("has_model_config", false)) return null;
            if (optionsCache == null || System.currentTimeMillis() - optionsAt > 300_000) {
                try { optionsCache = modelOptions(); optionsAt = System.currentTimeMillis(); } catch (Exception ignored) {}
            }
            String provider = "";
            if (optionsCache != null) for (ModelOption m : optionsCache) if (m.id.equalsIgnoreCase(model)) {
                if (provider.isEmpty()) provider = m.provider; else if (!provider.equals(m.provider)) { provider = ""; break; }
            }
            return new String[]{model, provider};
        } catch (Exception e) { return null; }
    }

    /**
     * Main-profile Hermes: runs go to the gateway's Companion platform (server-kit/hermes-plugin) on this port of the
     * same host, where a person counts as present: risky commands and execute_code bring an approval card instead of
     * being refused, clarify and slash commands work. Its run ids start with gw_. Unreachable (off Tailscale, plugin
     * not installed) means the api_server run, as before.
     */
    static final int GATEWAY_PORT = 8797;
    boolean useGateway;
    private static volatile long gatewayDownUntil;

    String gatewayBase() {
        if (!useGateway || System.currentTimeMillis() < gatewayDownUntil) return null;
        try {
            URL u = new URL(base);
            return u.getPath().contains("/p/") ? null : gatewayRoot();
        } catch (Exception e) { return null; }
    }

    /** The gateway as configured (the primary host on its port); {@link Reach} swaps in whichever backup address answers. */
    private String gatewayRoot() throws java.net.MalformedURLException { return "http://" + new URL(base).getHost() + ":" + GATEWAY_PORT; }

    /** A run's endpoint on whichever server owns it. */
    String runUrl(String runId, String suffix) {
        String p = "/v1/runs/" + path(runId) + suffix;
        if (runId == null || !runId.startsWith("gw_")) return p;
        try { return gatewayRoot() + p; }
        catch (Exception e) { return p; }
    }

    /** What {@link Reach} knows this run's server as, for failover. */
    private String runRoot(String runId) { String u = runUrl(runId, ""); return u.startsWith("http") ? u : UrlUtils.endpoint(base, u); }

    private static boolean unreachable(Exception e) {
        String m = e.getMessage();
        return e instanceof java.net.ConnectException || e instanceof java.net.SocketTimeoutException
                || e instanceof java.net.UnknownHostException || e instanceof java.net.NoRouteToHostException
                || (m != null && (m.startsWith("HTTP 404") || m.startsWith("HTTP 401")));
    }

    /** Returns false only when the server has no /v1/runs, before anything reached the listener. */
    boolean streamRun(String sessionId, Object input, String systemMessage, StreamHandle handle, StreamListener listener) {
        String runId = null;
        String gateway = gatewayBase();
        if (!(input instanceof String) && gateway == null) return false; // image turns: the session stream
        try {
            JSONObject body = new JSONObject().put("session_id", sessionId).put("input", input);
            if (systemMessage != null && !systemMessage.trim().isEmpty()) body.put("instructions", systemMessage);
            String[] route = sessionRoute(sessionId);
            if (route != null) { body.put("model", route[0]); if (!route[1].isEmpty()) body.put("provider", route[1]); }
            SettingsSource local = settingsSource;
            JSONObject options = local == null ? null : local.runOptions(sessionId);
            if (options != null) body.put("model_options", options);
            if (handle != null && handle.cancelled) { listener.onError("Cancelled"); return true; }
            if (gateway != null) {
                try { runId = clean(requestJson("POST", gateway + "/v1/runs", body).optString("run_id", "")); }
                catch (Exception e) {
                    if (!unreachable(e)) throw e;
                    gatewayDownUntil = System.currentTimeMillis() + 60_000;
                    if (!(input instanceof String)) return false;
                    runId = null;
                }
            }
            if (runId == null) runId = clean(requestJson("POST", "/v1/runs", body).optString("run_id", ""));
            if (runId.isEmpty()) throw new IllegalStateException("The server did not start a run");
        } catch (Exception e) {
            String m = e.getMessage();
            if (m != null && m.startsWith("HTTP 404") && !isSessionNotFound(m)) return false;
            listener.onError(friendly(e));
            return true;
        }
        followRun(runId, handle, listener);
        return true;
    }

    /** Streams an already running /v1/runs run (replaying what it did so far) until it ends. */
    public void followRun(String runId, StreamHandle handle, StreamListener listener) {
        listener.onRunStarted(runId);
        RunEvents state = new RunEvents();
        long lastSeq = -1, lastHeard = System.currentTimeMillis();
        int failures = 0;
        boolean dropped = false;
        while (true) {
            if (handle != null && handle.cancelled) { listener.onError("Cancelled"); return; }
            HttpURLConnection c = null;
            try {
                c = open("GET", runUrl(runId, "/events"));
                // The server writes a keepalive every 10 s; silence for 35 s means the link is dead.
                c.setReadTimeout(35_000);
                c.setRequestProperty("Accept", "text/event-stream");
                if (lastSeq >= 0) c.setRequestProperty("Last-Event-ID", String.valueOf(lastSeq));
                if (handle != null) { handle.connection = c; if (handle.cancelled) continue; }
                int code = c.getResponseCode();
                if (code == 404) { finishFromStatus(runId, state, listener); return; } // finished and forgotten
                if (code < 200 || code >= 300) throw new java.io.IOException(errorText(code, read(c, code)));
                if (dropped) { dropped = false; listener.onConnection(true); }
                failures = 0;
                StringBuilder payload = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        lastHeard = System.currentTimeMillis();
                        if (line.startsWith("data:")) { if (payload.length() > 0) payload.append('\n'); payload.append(line.substring(5).trim()); continue; }
                        if (!line.isEmpty() || payload.length() == 0) continue;
                        JSONObject o;
                        try { o = new JSONObject(payload.toString()); } catch (Exception bad) { o = new JSONObject(); }
                        payload.setLength(0);
                        long seq = o.optLong("seq", -1);
                        if (seq >= 0 && seq <= lastSeq) continue; // already seen before the reconnect
                        if (seq >= 0) lastSeq = seq;
                        if (state.handle(runId, o, listener)) { state.finish(listener); return; }
                    }
                }
                // The server closed the stream without a terminal event we saw: ask how the run ended.
                if (finishFromStatusIfDone(runId, state, listener)) return;
            } catch (Exception e) {
                if (handle != null && handle.cancelled) { listener.onError("Cancelled"); return; }
                if (!dropped) { dropped = true; listener.onConnection(false); }
                failures++;
                if (Reach.isConnectFailure(e, "GET")) Reach.failover(runRoot(runId));
                // Keep trying for 15 minutes of silence: phones lose signal, switch networks and sleep.
                if (System.currentTimeMillis() - lastHeard > 900_000) {
                    if (!finishFromStatusIfDone(runId, state, listener))
                        listener.onError(friendly(e) + ". Hermes is still working on the server; the reply will appear in the chat when it finishes.");
                    return;
                }
            } finally { if (c != null) c.disconnect(); }
            try { Thread.sleep(Math.min(15_000, 500L << Math.min(5, failures))); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); listener.onError("Cancelled"); return; }
        }
    }

    private boolean finishFromStatusIfDone(String runId, RunEvents state, StreamListener listener) {
        try {
            JSONObject status = getRun(runId);
            String st = status.optString("status", "");
            if (!("completed".equals(st) || "failed".equals(st) || "cancelled".equals(st) || "interrupted".equals(st))) return false;
            state.outcome = parseOutcome(st, status, state.finalText);
            if ("failed".equals(st)) state.error = clean(status.optString("error", ""));
            state.finish(listener);
            return true;
        } catch (Exception e) { return false; }
    }

    private void finishFromStatus(String runId, RunEvents state, StreamListener listener) {
        if (!finishFromStatusIfDone(runId, state, listener)) state.finish(listener);
    }

    /** Folds /v1/runs events into the listener; the same names the session stream uses. */
    static final class RunEvents {
        String finalText = "";
        RunOutcome outcome;
        final StringBuilder streamed = new StringBuilder();

        /** True on the terminal event. */
        boolean handle(String runId, JSONObject o, StreamListener listener) {
            String name = o.optString("event", "");
            switch (name) {
                case "message.delta": case "assistant.delta": {
                    String d = o.optString("delta", "");
                    streamed.append(d);
                    if (o.optBoolean("segment", false)) listener.onSegment(d); else listener.onDelta(d);
                    return false;
                }
                case "run.steered": listener.onSteer(clean(o.optString("text", ""))); return false;
                case "message.interim": case "assistant.commentary":
                    if (!o.optBoolean("already_streamed", false)) listener.onCommentary(o.optString("text", ""));
                    return false;
                case "reasoning.available": listener.onReasoning(o.optString("text", "")); return false;
                case "tool.progress": {
                    String tool = o.optString("tool_name", "");
                    if ("_thinking".equals(tool) || tool.isEmpty()) listener.onReasoning(o.optString("delta", o.optString("text", "")));
                    return false;
                }
                case "approval.request": listener.onApproval(Approval.parse(runId, o)); return false;
                case "approval.resolved": listener.onApproval(null); return false; // answered elsewhere (the terminal)
                case "assistant.completed": finalText = o.optString("content", finalText); return false;
                default:
                    if (name.startsWith("tool.")) {
                        Object args = o.opt("args");
                        listener.onToolEvent(name.substring(5), clean(o.optString("tool_name", o.optString("tool", "tool"))),
                                toolPreview(name, o), args == null || args == JSONObject.NULL ? "" : String.valueOf(args));
                        return false;
                    }
                    if (name.startsWith("run.") && !"run.started".equals(name) && !"run.queued".equals(name)
                            && !"run.steered".equals(name) && !"run.stopping".equals(name)) {
                        outcome = parseOutcome(name.substring(4), o, finalText);
                        if ("run.failed".equals(name)) error = clean(o.optString("error", ""));
                        return true;
                    }
                    return false;
            }
        }

        String error;

        void finish(StreamListener listener) {
            if (outcome != null && "failed".equals(outcome.status)) { listener.onError(error == null || error.isEmpty() ? "Run failed" : error); return; }
            if (outcome == null) { outcome = new RunOutcome(); outcome.finalText = finalText; }
            if (outcome.finalText.isEmpty()) outcome.finalText = finalText.isEmpty() ? streamed.toString() : finalText;
            listener.onRunFinished(outcome);
            listener.onComplete(outcome.finalText);
        }
    }

    private void streamSessionChat(String sessionId, Object input, String systemMessage, StreamHandle handle, StreamListener listener) {
        HttpURLConnection c = null;
        String runId = null;
        boolean finished = false;
        StringBuilder streamed = new StringBuilder();
        try {
            c = open("POST", "/api/sessions/" + path(sessionId) + "/chat/stream");
            if (handle != null) { handle.connection = c; if (handle.cancelled) throw new java.io.InterruptedIOException("Cancelled"); }
            c.setRequestProperty("Accept", "text/event-stream");
            JSONObject body = new JSONObject().put("input", input);
            if (systemMessage != null && !systemMessage.trim().isEmpty()) body.put("system_message", systemMessage);
            byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = c.getOutputStream()) { out.write(data); }
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) { listener.onError(errorText(code, read(c, code))); return; }
            String event = "message"; StringBuilder payload = new StringBuilder(); String finalText = "";
            RunOutcome outcome = null;
            boolean sawEnd = false;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.isEmpty()) {
                        if (payload.length() > 0) {
                            JSONObject o;
                            try { o = new JSONObject(payload.toString()); } catch (Exception bad) { o = new JSONObject(); }
                            String name = event;
                            if ("message".equals(name) && o.has("event")) name = o.optString("event", name);
                            switch (name) {
                                case "run.started":
                                    runId = o.optString("run_id", runId);
                                    listener.onRunStarted(runId);
                                    break;
                                case "assistant.delta": case "message.delta": {
                                    String d = o.optString("delta", "");
                                    streamed.append(d);
                                    if (o.optBoolean("segment", false)) listener.onSegment(d); else listener.onDelta(d);
                                    break;
                                }
                                case "run.steered":
                                    listener.onSteer(clean(o.optString("text", "")));
                                    break;
                                case "assistant.commentary": case "message.interim":
                                    if (!o.optBoolean("already_streamed", false)) listener.onCommentary(o.optString("text", ""));
                                    break;
                                case "tool.progress": {
                                    String tool = o.optString("tool_name", "");
                                    if ("_thinking".equals(tool) || tool.isEmpty()) listener.onReasoning(o.optString("delta", o.optString("text", "")));
                                    break;
                                }
                                case "reasoning.available":
                                    listener.onReasoning(o.optString("text", ""));
                                    break;
                                case "approval.request":
                                    listener.onApproval(Approval.parse(runId, o));
                                    break;
                                case "assistant.completed":
                                    finalText = o.optString("content", finalText);
                                    break;
                                case "done":
                                    sawEnd = true;
                                    break;
                                case "error":
                                    finished = true;
                                    listener.onError(o.optString("message", "Run failed"));
                                    return;
                                default:
                                    if (name.startsWith("tool.")) {
                                        Object args = o.opt("args");
                                        listener.onToolEvent(name.substring(5), clean(o.optString("tool_name", o.optString("tool", "tool"))),
                                                toolPreview(name, o), args == null || args == JSONObject.NULL ? "" : String.valueOf(args));
                                    } else if (name.startsWith("run.") && !"run.started".equals(name) && !"run.queued".equals(name)
                                            && !"run.steered".equals(name) && !"run.stopping".equals(name)) {
                                        outcome = parseOutcome(name.substring(4), o, finalText);
                                        sawEnd = true;
                                    }
                            }
                        }
                        event = "message"; payload.setLength(0);
                        // The run is over: stop reading. Some servers keep the socket open with keepalives
                        // after the terminal event, which left the app showing "working" forever.
                        if (sawEnd) break;
                        continue;
                    }
                    if (line.startsWith(":")) continue; // keepalive comment
                    if (line.startsWith("event:")) event = line.substring(6).trim();
                    else if (line.startsWith("data:")) { if (payload.length() > 0) payload.append('\n'); payload.append(line.substring(5).trim()); }
                }
            }
            if (!sawEnd && runId != null && (handle == null || !handle.cancelled())) {
                // The body ended without a terminal event: the connection was cut, not the run.
                throw new java.io.EOFException("Stream ended early");
            }
            finished = true;
            if (outcome == null) { outcome = new RunOutcome(); outcome.finalText = finalText; }
            if (outcome.finalText.isEmpty()) outcome.finalText = finalText.isEmpty() ? streamed.toString() : finalText;
            listener.onRunFinished(outcome);
            listener.onComplete(outcome.finalText);
        } catch (Exception e) {
            if (finished) return;
            if (handle != null && handle.cancelled()) { listener.onError("Cancelled"); return; }
            // The socket died mid-run. The server records the terminal status (and reply text)
            // under the run id, so ask it how the run ended instead of guessing.
            if (runId != null) {
                RunOutcome recovered = recoverRun(runId);
                if (recovered != null) {
                    if (recovered.finalText.isEmpty()) recovered.finalText = streamed.toString();
                    listener.onRunFinished(recovered);
                    listener.onComplete(recovered.finalText);
                    return;
                }
            }
            listener.onError(friendly(e));
        } finally { if (c != null) c.disconnect(); }
    }

    static RunOutcome parseOutcome(String status, JSONObject o, String finalText) {
        RunOutcome out = new RunOutcome();
        out.status = status == null || status.isEmpty() ? "completed" : status;
        out.finalText = clean(o.optString("output", "")).isEmpty() ? (finalText == null ? "" : finalText) : o.optString("output", "");
        out.turnExitReason = clean(o.optString("turn_exit_reason", ""));
        out.pendingSteer = clean(o.optString("pending_steer", ""));
        out.sessionId = clean(o.optString("session_id", ""));
        JSONObject usage = o.optJSONObject("usage");
        if (usage != null) {
            out.inputTokens = usage.optLong("input_tokens", usage.optLong("prompt_tokens", 0));
            out.outputTokens = usage.optLong("output_tokens", usage.optLong("completion_tokens", 0));
            out.totalTokens = usage.optLong("total_tokens", out.inputTokens + out.outputTokens);
        }
        return out;
    }

    /** Poll GET /v1/runs/{id} until it reaches a terminal state (bounded), or null if unknown. */
    RunOutcome recoverRun(String runId) {
        for (int attempt = 0; attempt < 8; attempt++) {
            try {
                JSONObject status = getRun(runId);
                String s = status.optString("status", "");
                if ("completed".equals(s) || "failed".equals(s) || "cancelled".equals(s)) return parseOutcome(s, status, "");
            } catch (Exception ignored) {}
            try { Thread.sleep(Math.min(8000, 1000L << attempt)); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return null; }
        }
        return null;
    }

    public JSONObject getRun(String runId) throws Exception { return requestJson("GET", runUrl(runId, ""), null); }

    public Result stopRun(String runId) { return simplePost(runUrl(runId, "/stop"), new JSONObject()); }
    public Result steerRun(String runId, String text) {
        try { return simplePost(runUrl(runId, "/steer"), new JSONObject().put("message", text).put("text", text)); }
        catch (Exception e) { return Result.error(friendly(e)); }
    }
    public Result approveRun(String runId, String choice) { return approveRun(runId, choice, null); }
    public Result approveRun(String runId, String choice, String requestId) { return approveRun(runId, choice, requestId, -1); }
    public Result approveRun(String runId, String choice, String requestId, int generation) {
        try {
            JSONObject body = new JSONObject().put("choice", choice);
            if (requestId != null && !requestId.isEmpty()) body.put("request_id", requestId);
            if (generation >= 0) body.put("execution_generation", generation);   // durable runs refuse a body without it
            JSONObject o = requestJson("POST", runUrl(runId, "/approval"), body);
            return Result.ok("deny".equals(o.optString("choice", choice)) ? "Denied" : "Approved");
        } catch (Exception e) { return Result.error(friendly(e)); }
    }

    // ── skills & toolsets ───────────────────────────────────────────────────────────────────────

    public List<Skill> skillList() throws Exception {
        Object root = requestAny("GET", "/v1/skills", null); JSONArray data = root instanceof JSONArray ? (JSONArray) root : ((JSONObject) root).optJSONArray("data");
        List<Skill> out = new ArrayList<>();
        if (data != null) for (int i = 0; i < data.length(); i++) {
            JSONObject o = data.optJSONObject(i); if (o == null) continue;
            out.add(new Skill(clean(o.optString("name")), clean(o.optString("description")), clean(o.optString("category"))));
        }
        return out;
    }

    public List<Toolset> toolsetList() throws Exception {
        Object root = requestAny("GET", "/v1/toolsets", null); JSONArray data = root instanceof JSONArray ? (JSONArray) root : ((JSONObject) root).optJSONArray("data");
        List<Toolset> out = new ArrayList<>();
        if (data != null) for (int i = 0; i < data.length(); i++) {
            JSONObject o = data.optJSONObject(i); if (o == null) continue;
            Toolset t = new Toolset(clean(o.optString("name")), clean(o.optString("description")), o.optBoolean("enabled", true));
            JSONArray tools = o.optJSONArray("tools");
            if (tools != null) for (int j = 0; j < tools.length(); j++) {
                Object tool = tools.opt(j);
                String n = tool instanceof JSONObject ? ((JSONObject) tool).optString("name", "") : String.valueOf(tool);
                if (!clean(n).isEmpty()) t.tools.add(n);
            }
            out.add(t);
        }
        return out;
    }

    public List<String> skills() throws Exception {
        List<String> out = new ArrayList<>(); for (Skill s : skillList()) out.add(s.name + " — " + s.description); return out;
    }
    public List<String> toolsets() throws Exception {
        List<String> out = new ArrayList<>(); for (Toolset t : toolsetList()) out.add(t.name + (t.enabled ? "  ON" : "  OFF")); return out;
    }

    // ── cron jobs ───────────────────────────────────────────────────────────────────────────────

    public List<Job> jobs() throws Exception {
        JSONObject root = requestJson("GET", "/api/jobs?include_disabled=true", null);
        JSONArray arr = root.optJSONArray("jobs"); List<Job> out = new ArrayList<>();
        if (arr != null) for (int i = 0; i < arr.length(); i++) { JSONObject o = arr.optJSONObject(i); if (o != null) out.add(new Job(o)); }
        return out;
    }

    public Job createJob(String name, String schedule, String prompt, String deliver, List<String> skills, Integer repeat) throws Exception {
        JSONObject body = new JSONObject().put("name", name).put("schedule", schedule).put("prompt", prompt == null ? "" : prompt);
        if (deliver != null && !deliver.trim().isEmpty()) body.put("deliver", deliver.trim());
        if (skills != null && !skills.isEmpty()) body.put("skills", new JSONArray(skills));
        if (repeat != null) body.put("repeat", repeat.intValue());
        return new Job(requestJson("POST", "/api/jobs", body).getJSONObject("job"));
    }

    public Job updateJob(String jobId, JSONObject fields) throws Exception {
        return new Job(requestJson("PATCH", "/api/jobs/" + path(jobId), fields).getJSONObject("job"));
    }
    public Result deleteJob(String jobId) {
        try { requestJson("DELETE", "/api/jobs/" + path(jobId), null); return Result.ok("Job deleted"); }
        catch (Exception e) { return Result.error(friendly(e)); }
    }
    public Result pauseJob(String jobId) { return jobAction(jobId, "pause", "Paused"); }
    public Result resumeJob(String jobId) { return jobAction(jobId, "resume", "Resumed"); }
    public Result runJob(String jobId) { return jobAction(jobId, "run", "Triggered — it runs in the background"); }
    private Result jobAction(String jobId, String action, String okText) {
        try { requestJson("POST", "/api/jobs/" + path(jobId) + "/" + action, new JSONObject()); return Result.ok(okText); }
        catch (Exception e) { return Result.error(friendly(e)); }
    }

    // ── plumbing ────────────────────────────────────────────────────────────────────────────────

    private Result simplePost(String endpoint, JSONObject body) {
        try { JSONObject o = requestJson("POST", endpoint, body); return Result.ok(o.optString("status", "ok")); }
        catch (Exception e) { return Result.error(friendly(e)); }
    }

    private JSONObject requestJson(String method, String endpoint, JSONObject body) throws Exception {
        Object value = requestAny(method, endpoint, body);
        if (!(value instanceof JSONObject)) throw new IllegalStateException("Expected JSON object");
        return (JSONObject) value;
    }

    private Object requestAny(String method, String endpoint, JSONObject body) throws Exception {
        try { return requestOnce(method, endpoint, body); }
        catch (java.io.IOException e) {
            if (!Reach.isConnectFailure(e, method) || !Reach.failover(endpoint.startsWith("http") ? endpoint : UrlUtils.endpoint(base, endpoint))) throw e;
            return requestOnce(method, endpoint, body);
        }
    }

    private Object requestOnce(String method, String endpoint, JSONObject body) throws Exception {
        HttpURLConnection c = open(method, endpoint);
        c.setReadTimeout(readMs);
        if (body != null) { byte[] data = body.toString().getBytes(StandardCharsets.UTF_8); try (OutputStream out = c.getOutputStream()) { out.write(data); } }
        // No disconnect(): on Android it closes the socket, and keep-alive saves a TCP+TLS handshake per call.
        int code = c.getResponseCode(); String response = read(c, code);
        if (code < 200 || code >= 300) throw new IllegalStateException(errorText(code, response));
        String trim = response.trim();
        if (trim.isEmpty()) return new JSONObject();
        if (trim.startsWith("<")) throw new IllegalStateException("The server answered with a web page, not the Hermes API. Check the URL and port point at the Hermes API server.");
        return trim.startsWith("[") ? new JSONArray(trim) : new JSONObject(trim);
    }

    /** Fetch raw bytes (images referenced by http URL in a reply), with auth when it is our server. */
    public byte[] fetchBytes(String url, int maxBytes) throws Exception {
        URL target = new URL(url);
        HttpURLConnection c = (HttpURLConnection) target.openConnection();
        c.setConnectTimeout(15_000); c.setReadTimeout(30_000);
        if (sameServer(base, target)) {
            Reach.checkSecure(url);
            c.setRequestProperty("Authorization", "Bearer " + key);
            c.setInstanceFollowRedirects(false);
        }
        try {
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buf = new byte[16384]; int n;
                while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); if (out.size() > maxBytes) throw new IllegalStateException("Too large"); }
                return out.toByteArray();
            }
        } finally { c.disconnect(); }
    }

    static boolean sameServer(String base, URL target) throws Exception {
        if (base == null || base.isEmpty()) return false;
        URL server = new URL(base);
        if (target.toURI().getRawUserInfo() != null || server.toURI().getRawUserInfo() != null) return false;
        String scheme = target.getProtocol();
        return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                && scheme.equalsIgnoreCase(server.getProtocol()) && target.getHost().equalsIgnoreCase(server.getHost())
                && (target.getPort() < 0 ? target.getDefaultPort() : target.getPort())
                == (server.getPort() < 0 ? server.getDefaultPort() : server.getPort());
    }

    private HttpURLConnection open(String method, String endpoint) throws Exception {
        boolean absolute = endpoint.startsWith("http"); // the gateway's run port, which Reach maps like any other port
        String url = Reach.apply(absolute ? endpoint : UrlUtils.endpoint(base, endpoint));
        Reach.checkSecure(url);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method); c.setConnectTimeout(absolute ? Math.min(connectMs, 4_000) : Reach.hasBackups() ? Math.min(connectMs, Reach.FIRST_CONNECT_MS) : connectMs); c.setReadTimeout(1_800_000); c.setDoInput(true);
        c.setRequestProperty("Accept", "application/json"); c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("Authorization", "Bearer " + key); c.setRequestProperty("X-Hermes-Session-Key", memoryScope);
        c.setRequestProperty("User-Agent", "Hermes-Companion-Android/7");
        if ("POST".equals(method) || "PATCH".equals(method)) c.setDoOutput(true);
        return c;
    }

    private static String path(String raw) {
        try { return URLEncoder.encode(raw, StandardCharsets.UTF_8.name()).replace("+", "%20"); }
        catch (Exception e) { return raw; }
    }
    private static String read(HttpURLConnection c, int code) throws Exception {
        InputStream stream = code >= 400 ? c.getErrorStream() : c.getInputStream(); if (stream == null) return "";
        StringBuilder b = new StringBuilder(); try (BufferedReader r = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) { String line; while ((line = r.readLine()) != null && b.length() < 8_000_000) b.append(line).append('\n'); } return b.toString();
    }
    static String errorText(int code, String body) {
        try {
            JSONObject o = new JSONObject(body); Object e = o.opt("error");
            if (e instanceof JSONObject) return "HTTP " + code + ": " + ((JSONObject) e).optString("message", e.toString());
            if (e instanceof String && !((String) e).isEmpty()) return "HTTP " + code + ": " + e;
            String d = o.optString("detail", o.optString("message", "")); if (!d.isEmpty()) return "HTTP " + code + ": " + d;
        } catch (Exception ignored) {}
        String b = body == null ? "" : body.trim();
        if (code == 401) return "HTTP 401: the API key was rejected";
        if (code == 403) return "HTTP 403: the server refused this key" + (b.isEmpty() || b.startsWith("<") ? "" : " — " + b.substring(0, Math.min(b.length(), 200)));
        if (code == 404) return "HTTP 404: this URL has no Hermes API here. Check the host, port and any path prefix.";
        if (code == 502 || code == 503 || code == 504) return "HTTP " + code + ": a proxy in front of Hermes couldn't reach it — is the Hermes gateway running?";
        return "HTTP " + code + (b.isEmpty() ? "" : ": " + b.substring(0, Math.min(b.length(), 300)));
    }
    static boolean isSessionNotFound(String error) {
        return error != null && error.startsWith("HTTP 404: Session not found: ");
    }
    static String friendly(Exception e) {
        String m = e.getMessage();
        if (e instanceof java.net.UnknownHostException) return "Can't resolve the server host — check the URL or your network";
        if (e instanceof java.net.ConnectException) return "Can't connect to the server — is Hermes running and reachable?";
        if (e instanceof java.net.SocketTimeoutException) return "The server took too long to respond";
        if (e instanceof javax.net.ssl.SSLException) return "Secure connection failed (" + (m == null ? "TLS error" : m) + "). If the server uses plain HTTP, use an http:// URL";
        if (e instanceof java.net.NoRouteToHostException) return "No route to the server — are you on the right network or Tailscale?";
        if (e instanceof org.json.JSONException) return "The server's reply wasn't Hermes API JSON — check the URL points at the Hermes API server";
        if (e instanceof IllegalStateException && m != null) return m;
        return e.getClass().getSimpleName() + (m == null || m.isEmpty() ? "" : ": " + m);
    }

    /** Iterate a JSONObject's keys in a type-safe way (org.json returns a raw Iterator on Android). */
    static List<String> keys(JSONObject o) {
        List<String> out = new ArrayList<>(); if (o == null) return out;
        Iterator<String> it = o.keys(); while (it.hasNext()) out.add(it.next()); return out;
    }
}
