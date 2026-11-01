package cloud.stormlabs.hermescompanion;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Every notification the companion posts: live-run status, approvals and finished replies. */
final class Notifier {
    private Notifier() {}

    // CH_RESULTS id bumped (results -> results_v2): Android never changes an existing channel's
    // importance from code, only from the user's own Settings, so raising it for installs that
    // already created "results" at the old DEFAULT needs a new id, not a new importance value here.
    static final String CH_RUNS = "runs", CH_RESULTS = "results_v2", CH_APPROVALS = "approvals";
    static final String CH_EVENTS = "hermes-events", CH_ALERTS = "alerts";
    static final String CH_DRAFTS = "reply-drafts", CH_UPDATES = "updates", CH_QUIET = "quiet-hours";
    static final int ID_UPDATE = 4300;
    static final String CH_WAKE = "wake", CH_WAKE_HIT = "wake_hit", CH_LECTURE = "live_notes";
    static final int ID_RUNNING = 4100, ID_WAKE = 4200, ID_WAKE_HIT = 4201, ID_WAKE_PAUSED = 4202, ID_LECTURE = 4210;
    static final String KEY_REPLY = "reply_text";

    static void channels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        // Dead channel left behind by the CH_RESULTS id bump above — an install that already
        // created "results" would otherwise keep showing a dead "Replies" entry in system
        // settings forever (deleteNotificationChannel no-ops once it's already gone).
        try { nm.deleteNotificationChannel("results"); } catch (Throwable ignored) {}
        NotificationChannel runs = new NotificationChannel(CH_RUNS, "Working", NotificationManager.IMPORTANCE_LOW);
        runs.setDescription("Shown while Hermes is working in the background");
        runs.setShowBadge(false);
        // HIGH, not DEFAULT: this is the one that fires for a chat the user is actively away
        // from waiting on, same as an approval or an alert — DEFAULT sits quietly in the tray
        // with no heads-up, which is what read as "it doesn't pop up" next to those two.
        NotificationChannel results = new NotificationChannel(CH_RESULTS, "Replies", NotificationManager.IMPORTANCE_HIGH);
        results.setDescription("Hermes finished a reply while you were away");
        NotificationChannel approvals = new NotificationChannel(CH_APPROVALS, "Approvals", NotificationManager.IMPORTANCE_HIGH);
        approvals.setDescription("Hermes needs your permission to run a command");
        NotificationChannel events = new NotificationChannel(CH_EVENTS, "Hub events", NotificationManager.IMPORTANCE_DEFAULT);
        events.setDescription("Jobs, briefings and proposals pushed from the hub");
        NotificationChannel alerts = new NotificationChannel(CH_ALERTS, "Alerts", NotificationManager.IMPORTANCE_HIGH);
        alerts.setDescription("High-priority events pushed from the hub");
        NotificationChannel drafts = new NotificationChannel(CH_DRAFTS, "Reply drafts", NotificationManager.IMPORTANCE_DEFAULT);
        drafts.setDescription("Opt-in drafts for selected messaging apps");
        nm.createNotificationChannel(runs);
        nm.createNotificationChannel(results);
        nm.createNotificationChannel(approvals);
        nm.createNotificationChannel(events);
        nm.createNotificationChannel(alerts);
        nm.createNotificationChannel(drafts);
        NotificationChannel wake = new NotificationChannel(CH_WAKE, "Wake word", NotificationManager.IMPORTANCE_LOW);
        wake.setDescription("Shown while Hermes listens on this phone for its name");
        wake.setShowBadge(false);
        NotificationChannel wakeHit = new NotificationChannel(CH_WAKE_HIT, "Wake word heard", NotificationManager.IMPORTANCE_HIGH);
        wakeHit.setDescription("Tap to talk when Hermes can't open over the current app");
        wakeHit.setShowBadge(false);
        NotificationChannel updates = new NotificationChannel(CH_UPDATES, "Updates", NotificationManager.IMPORTANCE_LOW);
        updates.setDescription("New Hermes Companion versions from your hub");
        updates.setShowBadge(false);
        nm.createNotificationChannel(updates);
        NotificationChannel quiet = new NotificationChannel(CH_QUIET, "Quiet hours", NotificationManager.IMPORTANCE_LOW);
        quiet.setDescription("Alerts that arrive during quiet hours: shown without sound or vibration");
        nm.createNotificationChannel(quiet);
        NotificationChannel lecture = new NotificationChannel(CH_LECTURE, "Live notes", NotificationManager.IMPORTANCE_LOW);
        lecture.setDescription("Shown while Hermes listens to a lecture or meeting for live notes");
        lecture.setShowBadge(false);
        nm.createNotificationChannel(lecture);
        nm.createNotificationChannel(wake);
        nm.createNotificationChannel(wakeHit);
    }

    /** The wake word's foreground-service notification: required while it listens in the background. */
    static Notification wake(Context c) {
        PendingIntent off = PendingIntent.getBroadcast(c, ID_WAKE,
                new Intent(c, RunActionReceiver.class).setAction(RunActionReceiver.ACTION_STOP_WAKE), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        SettingsStore store = new SettingsStore(c);
        long remaining = WakeWordService.pauseRemaining(store);
        boolean paused = remaining > 0;
        String title = paused ? "Wake word paused until " + android.text.format.DateFormat.format("HH:mm", System.currentTimeMillis() + remaining)
                : "Listening for \"Hey Hermes\"";
        PendingIntent toggle = PendingIntent.getBroadcast(c, ID_WAKE + 1,
                new Intent(c, RunActionReceiver.class).setAction(paused ? RunActionReceiver.ACTION_RESUME_WAKE : RunActionReceiver.ACTION_PAUSE_WAKE),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return builder(c, CH_WAKE).setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
                .setContentTitle(title)
                .setContentText(paused ? "Microphone off · resumes automatically" : "On this phone only · say \"Hey Hermes\" and your question")
                .setContentIntent(openSettings(c))
                .addAction(new Notification.Action.Builder(null, paused ? "Resume" : "Pause 1 h", toggle).build())
                .addAction(new Notification.Action.Builder(null, "Turn off", off).build())
                .build();
    }

    /** Foreground notification while Live notes listens; tapping it reopens the session. */
    static Notification lecture(Context c) {
        PendingIntent open = PendingIntent.getActivity(c, ID_LECTURE, new Intent(c, LiveNotesActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return builder(c, CH_LECTURE).setOngoing(true).setOnlyAlertOnce(true).setShowWhen(true)
                .setContentTitle("Live notes is listening")
                .setContentText("Transcribing on your server · tap to open")
                .setContentIntent(open)
                .build();
    }

    /** "Update available" (tap opens the install flow) or, with progress >= 0, the download progress. */
    static void update(Context c, String title, String text, int progress) {
        PendingIntent open = PendingIntent.getActivity(c, ID_UPDATE, new Intent(c, SettingsActivity.class)
                .putExtra(Updater.EXTRA_UPDATE, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = builder(c, CH_UPDATES).setContentTitle(title).setContentText(text).setContentIntent(open).setOnlyAlertOnce(true);
        if (progress >= 0) b.setOngoing(true).setProgress(100, progress, false); else b.setAutoCancel(true).setStyle(new Notification.BigTextStyle().bigText(text));
        post(c, ID_UPDATE, b.build());
    }

    static void cancelUpdate(Context c) { try { nm(c).cancel(ID_UPDATE); } catch (Throwable ignored) {} }

    private static PendingIntent openSettings(Context c) {
        return PendingIntent.getActivity(c, ID_WAKE, new Intent(c, SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void cancelWake(Context c) { try { nm(c).cancel(ID_WAKE); } catch (Throwable ignored) {} }

    /** Heads-up fallback when the wake word fires but Android won't let us open over the current app. */
    static void wakeHeard(Context c, Intent open, String command) {
        PendingIntent pi = PendingIntent.getActivity(c, ID_WAKE_HIT, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        post(c, ID_WAKE_HIT, builder(c, CH_WAKE_HIT).setAutoCancel(true).setTimeoutAfter(20_000)
                .setCategory(Notification.CATEGORY_CALL)
                .setContentTitle(command.isEmpty() ? "Hermes heard you · tap to talk" : "Ask Hermes · tap to send")
                .setContentText(command.isEmpty() ? "Make Hermes the default assistant to skip this tap" : command)
                .setContentIntent(pi).build());
    }

    static void cancelWakeHeard(Context c) { try { nm(c).cancel(ID_WAKE_HIT); } catch (Throwable ignored) {} }

    /** After a reboot Android won't give a background-started service the mic; one tap resumes it. */
    static void wakePaused(Context c) {
        post(c, ID_WAKE_PAUSED, builder(c, CH_WAKE).setAutoCancel(true)
                .setContentTitle("Wake word paused")
                .setContentText("Tap to resume listening for \"Hey Hermes\"")
                .setContentIntent(openSettings(c)).build());
    }

    static void cancelWakePaused(Context c) { try { nm(c).cancel(ID_WAKE_PAUSED); } catch (Throwable ignored) {} }

    private static NotificationManager nm(Context c) { return (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE); }
    private static final int ID_RESULT = 5000, ID_APPROVAL = 9000, ID_PRESENCE = 13000, ID_DRAFT = 16000;

    static String sessionKey(String profileId, String sessionId) {
        return (profileId == null ? "" : profileId) + "\u0000" + sessionId;
    }
    static String resultTag(String profileId, String sessionId) { return "result:" + sessionKey(profileId, sessionId); }
    static String approvalTag(String profileId, String sessionId) { return "approval:" + sessionKey(profileId, sessionId); }
    static String presenceTag(String profileId, String sessionId) { return "presence:" + sessionKey(profileId, sessionId); }
    static String draftTag(String key) { return "draft:" + key; }

    static PendingIntent openChat(Context c, String profileId, String sessionId, String title) {
        Intent i = new Intent(c, MainActivity.class).putExtra(MainActivity.EXTRA_OPEN_CHAT, true)
                .putExtra(MainActivity.EXTRA_SESSION, sessionId).putExtra(ChatActivity.EXTRA_TITLE, title)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        forProfile(c, i, profileId);
        i.setData(android.net.Uri.parse("hermes-chat:" + android.net.Uri.encode(sessionKey(profileId, sessionId))));
        return PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Only this app's notifications may switch profile: MainActivity is exported, so the extra carries a secret. */
    static Intent forProfile(Context c, Intent i, String profileId) {
        if (profileId != null) i.putExtra(MainActivity.EXTRA_PROFILE, profileId).putExtra(MainActivity.EXTRA_PROFILE_SECRET, new SettingsStore(c).intentSecret());
        return i;
    }

    static PendingIntent action(Context c, String action, String profileId, String sessionId, String extra, boolean mutable) {
        Intent i = new Intent(c, RunActionReceiver.class).setAction(action).putExtra(RunActionReceiver.EXTRA_SESSION, sessionId);
        if (extra != null) i.putExtra(RunActionReceiver.EXTRA_CHOICE, extra);
        if (profileId != null) i.putExtra(MainActivity.EXTRA_PROFILE, profileId);
        i.setData(android.net.Uri.parse("hermes-action:" + android.net.Uri.encode(action + "\u0000" + sessionKey(profileId, sessionId) + "\u0000" + extra)));
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (mutable && Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : PendingIntent.FLAG_IMMUTABLE);
        return PendingIntent.getBroadcast(c, 0, i, flags);
    }

    static Notification.Builder builder(Context c, String channel) {
        channels(c);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, channel) : new Notification.Builder(c);
        return b.setSmallIcon(R.drawable.ic_notification).setColor(Ui.BLUE);
    }

    /** The foreground-service notification: what is running, with a Stop button for one run. */
    static Notification running(Context c, java.util.List<RunManager.LiveRun> runs) {
        Notification.Builder b = builder(c, CH_RUNS).setOngoing(true).setOnlyAlertOnce(true).setShowWhen(true);
        if (runs.isEmpty()) return b.setContentTitle("Hermes").setContentText("Finishing up…").build();
        RunManager.LiveRun r = runs.get(0);
        String title = runs.size() == 1 ? "Hermes is working · " + r.sessionTitle : "Hermes is working on " + runs.size() + " conversations";
        b.setContentTitle(title).setContentText(runText(r)).setSubText(RunStatus.step(r.tools.size())).setProgress(0, 0, true)
                .setWhen(r.startedAt).setUsesChronometer(true)
                .setContentIntent(openChat(c, r.profileId, r.sessionId, r.sessionTitle));
        if (runs.size() == 1) b.addAction(new Notification.Action.Builder(null, "Stop", action(c, RunActionReceiver.ACTION_STOP, null, r.sessionId, null, false)).build());
        return b.build();
    }

    static String runText(RunManager.LiveRun r) {
        synchronized (r) {
            HermesApi.ToolCall t = null;
            for (int i = r.tools.size() - 1; i >= 0 && t == null; i--) if ("running".equals(r.tools.get(i).status)) t = r.tools.get(i);
            return RunStatus.text(r.approval != null, t == null ? null : t.name, t == null ? null : t.summary(), r.text.length() > 0);
        }
    }

    static void approval(Context c, RunManager.LiveRun run) {
        HermesApi.Approval a = run.approval;
        if (a == null) return;
        if (a.clarify) {
            StringBuilder body = new StringBuilder(a.description);
            for (int i = 0; i < a.choices.size(); i++) body.append(i == 0 ? "\n\n" : "\n").append(i + 1).append(". ").append(a.choices.get(i));
            Notification.Builder q = builder(c, CH_APPROVALS)
                    .setContentTitle("Hermes asks · " + run.sessionTitle)
                    .setContentText(a.description)
                    .setStyle(new Notification.BigTextStyle().bigText(body))
                    .setCategory(Notification.CATEGORY_REMINDER)
                    .setAutoCancel(true)
                    .setContentIntent(openChat(c, run.profileId, run.sessionId, run.sessionTitle));
            if (Build.VERSION.SDK_INT < 26) q.setPriority(Notification.PRIORITY_HIGH);
            post(c, approvalTag(run.profileId, run.sessionId), ID_APPROVAL, q.build());
            return;
        }
        String cmd = a.command.isEmpty() ? a.description : a.command;
        Notification.Builder b = builder(c, CH_APPROVALS)
                .setContentTitle("Approve command? · " + run.sessionTitle)
                .setContentText(cmd)
                .setStyle(new Notification.BigTextStyle().bigText((a.description.isEmpty() ? "" : a.description + "\n\n") + cmd))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setContentIntent(openChat(c, run.profileId, run.sessionId, run.sessionTitle));
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_HIGH);
        if (a.choices.contains("once")) b.addAction(new Notification.Action.Builder(null, "Allow once", action(c, RunActionReceiver.ACTION_APPROVE, run.profileId, run.sessionId, "once", false)).build());
        if (a.choices.contains("session")) b.addAction(new Notification.Action.Builder(null, "This session", action(c, RunActionReceiver.ACTION_APPROVE, run.profileId, run.sessionId, "session", false)).build());
        b.addAction(new Notification.Action.Builder(null, "Deny", action(c, RunActionReceiver.ACTION_APPROVE, run.profileId, run.sessionId, "deny", false)).build());
        post(c, approvalTag(run.profileId, run.sessionId), ID_APPROVAL, b.build());
    }

    static void result(Context c, RunManager.LiveRun run) {
        boolean failed = run.agentFailure != null || run.error != null || (run.outcome != null && !run.outcome.completed());
        String body = resultBody(run, failed);
        Notification.Builder b = builder(c, CH_RESULTS)
                .setContentTitle((failed ? "Hermes hit a problem · " : "Hermes · ") + run.sessionTitle)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body.length() > 4000 ? body.substring(0, 4000) + "…" : body))
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setContentIntent(openChat(c, run.profileId, run.sessionId, run.sessionTitle));
        RemoteInput input = new RemoteInput.Builder(KEY_REPLY).setLabel("Reply to Hermes").build();
        b.addAction(new Notification.Action.Builder(null, "Reply", action(c, RunActionReceiver.ACTION_REPLY, run.profileId, run.sessionId, run.sessionTitle, true))
                .addRemoteInput(input).build());
        post(c, resultTag(run.profileId, run.sessionId), ID_RESULT, b.build());
    }

    static String resultBody(RunManager.LiveRun run, boolean failed) {
        String body = run.agentFailure != null ? run.agentFailure : run.error != null ? run.error : Markdown.plain(run.finalText());
        if (body.trim().isEmpty()) body = failed ? "The run did not finish" : "Done";
        if (run.agentFailure == null && run.error == null && run.outcome != null && "cancelled".equals(run.outcome.status)) body = "Stopped. " + body;
        if (!run.actionsHandled && !run.pendingActions.isEmpty()) body = body + "\n\nTap to run on this phone: " + run.pendingActions.get(0).describe()
                + (run.pendingActions.size() > 1 ? " +" + (run.pendingActions.size() - 1) : "");
        return body;
    }

    static void presence(Context c, String title, String reply, String profileId, String sessionId) {
        String body = Markdown.plain(reply);
        if (body.length() > 4000) body = body.substring(0, 4000) + "…";
        post(c, presenceTag(profileId, sessionId), ID_PRESENCE, builder(c, CH_RESULTS)
                .setContentTitle("Home · " + title).setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setAutoCancel(true).setContentIntent(openChat(c, profileId, sessionId, "Home")).build());
    }

    static String eventTag(String hub, long id) { return "event:" + UrlUtils.normalize(hub) + "\u0000" + id; }
    private static final int ID_EVENT_SUMMARY = 5999;

    /** A hermes-hub event pushed or polled in: alerts, jobs, briefings, proposals. */
    /** Skills send alerts as "alert" with priority 4 (e.g. a failed deploy) and proposals as "proposal": both need the user. */
    static boolean urgent(String type, String priority) {
        if ("alert".equalsIgnoreCase(type) || "proposal".equalsIgnoreCase(type) || "high".equalsIgnoreCase(priority)) return true;
        try { return Integer.parseInt(priority == null ? "" : priority.trim()) >= 4; } catch (NumberFormatException e) { return false; }
    }

    static boolean critical(String priority) {
        try { return Integer.parseInt(priority == null ? "" : priority.trim()) >= 5; } catch (NumberFormatException e) { return false; }
    }

    /** True when {@code hour} falls in [start, end), wrapping past midnight (23 to 7 covers 23:00-06:59). */
    static boolean quietNow(int start, int end, int hour) {
        return start == end ? false : start < end ? hour >= start && hour < end : hour >= start || hour < end;
    }

    static boolean event(Context c, HubApi.Event e, String hub, String profileId) {
        if (e.body.trim().isEmpty()) return true;
        // Push and the 15-minute poll can both deliver an event; show it once even after it was dismissed.
        if (!new SettingsStore(c).markEventNotified(hub, e.id)) return true;
        boolean urgent = urgent(e.type, e.priority);
        String title = e.title.isEmpty() ? "Hermes" : e.title;
        boolean alert = "alert".equalsIgnoreCase(e.type), recovered = "info".equalsIgnoreCase(e.type) && AlertKey.recovered(title);
        String akey = AlertKey.of(title);
        if (recovered) cancelAlert(c, akey);
        else if (alert && AlertKey.muted(new SettingsStore(c).alertMutedUntil(akey), System.currentTimeMillis())) return true;
        if ("proposal".equalsIgnoreCase(e.type)) new SettingsStore(c).pendingProposal(e.sessionId, true);
        // Quiet hours: only critical alerts (priority 5, e.g. a service down) make a sound.
        SettingsStore qs = new SettingsStore(c);
        boolean hush = qs.quietHours() && quietNow(qs.quietStart(), qs.quietEnd(), java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)) && !critical(e.priority);
        Notification.Builder b = builder(c, hush ? CH_QUIET : urgent ? CH_ALERTS : CH_EVENTS)
                .setContentTitle(title)
                .setContentText(e.body)
                .setStyle(new Notification.BigTextStyle().bigText(e.body))
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setGroup(CH_EVENTS + ":" + hub)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setContentIntent(eventTarget(c, e, title, hub, profileId));
        if (!e.sessionId.isEmpty()) {
            RemoteInput input = new RemoteInput.Builder(KEY_REPLY).setLabel("Reply to Hermes").build();
            b.addAction(new Notification.Action.Builder(null, "Reply", action(c, RunActionReceiver.ACTION_REPLY, profileId, e.sessionId, title, true))
                    .addRemoteInput(input).build());
        }
        if (alert || recovered) alertActions(c, b, e, title, akey, hub, profileId);
        return post(c, alert ? alertTag(akey) : eventTag(hub, e.id), 6000, b.build());
    }

    static String alertTag(String key) { return "alert:" + key; }
    static void cancelAlert(Context c, String key) { try { nm(c).cancel(alertTag(key), 6000); } catch (Throwable ignored) {} }

    /** Open System, Ask Hermes (fresh chat, autosend) and, for live alerts, Mute 1 h. */
    private static void alertActions(Context c, Notification.Builder b, HubApi.Event e, String title, String key, String hub, String profileId) {
        int fl = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        String uid = eventTag(hub, e.id);
        Intent sys = new Intent(c, StatusActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        sys.setData(android.net.Uri.parse("hermes-system:" + android.net.Uri.encode(uid)));
        b.addAction(new Notification.Action.Builder(null, "Open System", PendingIntent.getActivity(c, 0, sys, fl)).build());
        Intent ask = new Intent(c, MainActivity.class).setAction(MainActivity.ACTION_NEW_CHAT)
                .putExtra(ChatActivity.EXTRA_PROMPT, "The companion app got this homelab alert: " + title + " \u2014 " + e.body
                        + ". Investigate read-only first, explain the cause, and propose fixes as proposal cards; don't change anything until I approve.")
                .putExtra(ChatActivity.EXTRA_AUTOSEND, true)
                .putExtra(MainActivity.EXTRA_PROFILE_SECRET, new SettingsStore(c).intentSecret())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (profileId != null) ask.putExtra(MainActivity.EXTRA_PROFILE, profileId);
        ask.setData(android.net.Uri.parse("hermes-ask:" + android.net.Uri.encode(uid)));
        b.addAction(new Notification.Action.Builder(null, "Ask Hermes", PendingIntent.getActivity(c, 0, ask, fl)).build());
        if (AlertKey.recovered(title)) return;
        Intent mute = new Intent(c, RunActionReceiver.class).setAction(RunActionReceiver.ACTION_MUTE_ALERT).putExtra(RunActionReceiver.EXTRA_CHOICE, key);
        mute.setData(android.net.Uri.parse("hermes-mute:" + android.net.Uri.encode(key)));
        b.addAction(new Notification.Action.Builder(null, "Mute 1 h", PendingIntent.getBroadcast(c, 0, mute, fl)).build());
    }

    static void eventSummary(Context c, int hidden, String hub, String profileId) {
        String title = hidden > 0 ? hidden + " more updates from Hermes" : "Hermes updates";
        Intent i = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        forProfile(c, i, profileId);
        i.setData(android.net.Uri.parse("hermes-events:" + android.net.Uri.encode(hub)));
        PendingIntent target = PendingIntent.getActivity(c, ID_EVENT_SUMMARY, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        post(c, "events:" + hub, ID_EVENT_SUMMARY, builder(c, CH_EVENTS).setContentTitle(title)
                .setContentIntent(target).setAutoCancel(true).setOnlyAlertOnce(true)
                .setGroup(CH_EVENTS + ":" + hub).setGroupSummary(true).build());
    }

    /** Where tapping the event notification lands: its session, its job, a linked URL, or just the app. */
    private static PendingIntent eventTarget(Context c, HubApi.Event e, String title, String hub, String profileId) {
        if (!e.sessionId.isEmpty()) return openChat(c, profileId, e.sessionId, title);
        Intent i;
        if (!e.jobId.isEmpty()) i = new Intent(c, MainActivity.class).putExtra(JobsActivity.EXTRA_JOB_ID, e.jobId);
        else if ("app:system".equals(e.url)) i = new Intent(c, StatusActivity.class); // the hub's morning digest
        else if ("app:media".equals(e.url)) i = new Intent(c, MediaActivity.class); // Sonarr/Radarr "downloaded"
        else if (!e.url.isEmpty()) i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(e.url));
        else i = new Intent(c, MainActivity.class);
        if (i.getComponent() != null) { forProfile(c, i, profileId); i.setData(android.net.Uri.parse("hermes-event:" + android.net.Uri.encode(eventTag(hub, e.id)))); }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(c, (int) e.id, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void cancelResult(Context c, String profileId, String sessionId) { try { nm(c).cancel(resultTag(profileId, sessionId), ID_RESULT); } catch (Throwable ignored) {} }
    static void cancelApproval(Context c, String profileId, String sessionId) { try { nm(c).cancel(approvalTag(profileId, sessionId), ID_APPROVAL); } catch (Throwable ignored) {} }

    private static PendingIntent draftAction(Context c, String action, String key) {
        Intent i = new Intent(c, ReplyDraftReceiver.class).setAction(action).putExtra(ReplyDraftReceiver.KEY, key);
        i.setData(android.net.Uri.parse("hermes-draft:" + android.net.Uri.encode(action + "\u0000" + key)));
        return PendingIntent.getBroadcast(c, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void replyOffer(Context c, String key, String sender) {
        Notification n = builder(c, CH_DRAFTS).setContentTitle("Draft reply with Hermes?")
                .setContentText("Message from " + sender).setAutoCancel(true)
                .addAction(new Notification.Action.Builder(null, "Draft", draftAction(c, ReplyDraftReceiver.DRAFT, key)).build())
                .build();
        post(c, draftTag(key), ID_DRAFT, n);
    }

    static void replyDraft(Context c, String key, String sender, String draft, boolean canSend) {
        Notification n = builder(c, CH_DRAFTS).setContentTitle("Reply to " + sender)
                .setContentText(draft).setStyle(new Notification.BigTextStyle().bigText(draft))
                .addAction(new Notification.Action.Builder(null, canSend ? "Send" : "Copy & open", draftAction(c, canSend ? ReplyDraftReceiver.SEND : ReplyDraftReceiver.COPY, key)).build())
                .addAction(new Notification.Action.Builder(null, "Edit", draftAction(c, ReplyDraftReceiver.EDIT, key)).build())
                .build();
        post(c, draftTag(key), ID_DRAFT, n);
    }

    static void replyError(Context c, String key, String error) {
        post(c, draftTag(key), ID_DRAFT, builder(c, CH_DRAFTS).setContentTitle("Could not draft reply")
                .setContentText(error).setAutoCancel(true).build());
    }

    static void cancelReplyDraft(Context c, String key) { try { nm(c).cancel(draftTag(key), ID_DRAFT); } catch (Throwable ignored) {} }

    static boolean post(Context c, int id, Notification n) { return post(c, null, id, n); }

    static boolean post(Context c, String tag, int id, Notification n) {
        try {
            if (!canPost(c)) return false;
            nm(c).notify(tag, id, n);
            return true;
        } catch (Throwable ignored) { return false; }
    }

    static boolean canPost(Context c) {
        return Build.VERSION.SDK_INT < 33 || c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }
}
