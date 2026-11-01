package cloud.stormlabs.hermescompanion;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fallback delivery for events when no UnifiedPush distributor is installed (or as a backstop
 * even when one is): periodically asks the hub for anything new since {@link SettingsStore#lastEventId()}.
 */
public final class PushPoller extends JobService {
    private static final int JOB_ID = 4200;
    private static final long INTERVAL_MS = 15 * 60 * 1000;
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();

    static void schedule(Context c) {
        FcmService.ensureRegistered(c.getApplicationContext());
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null || js.getPendingJob(JOB_ID) != null) return;
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(c, PushPoller.class))
                .setPeriodic(INTERVAL_MS)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build();
        js.schedule(job);
    }

    @Override public boolean onStartJob(JobParameters params) {
        EXEC.submit(() -> { pollNow(getApplicationContext()); jobFinished(params, false); });
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) { return false; }

    /** Polled once at app start too (MainActivity/ChatActivity onResume), off the UI thread. */
    static void pollOnce(Context c) { EXEC.submit(() -> pollNow(c.getApplicationContext())); }

    private static void pollNow(Context c) {
        try {
            SettingsStore store = new SettingsStore(c);
            if (store.apiKey().isEmpty()) return;
            String selected = store.profileId();
            String profile = store.mainProfileId(selected);
            if (profile == null) profile = selected;
            SettingsStore.Connection connection = store.hubConnection(selected);
            if (connection == null) return;
            String hub = connection.endpoint;
            long since = store.lastEventId(hub);
            if (!Notifier.canPost(c)) return; // stay uninitialised: once allowed, start at the newest event, not history
            HubApi api = new HubApi(hub, connection.key);
            if (!store.eventCursorInitialized(hub) && since <= 0) {
                long newest = since;
                List<HubApi.Event> page;
                do {
                    page = api.events(newest, 50);
                    long previous = newest;
                    for (HubApi.Event e : page) if (e.id > newest) newest = e.id;
                    if (newest == previous) break;
                } while (page.size() == 50);
                if (newest > since) store.lastEventId(hub, newest);
                store.markEventCursorInitialized(hub);
                return;
            }
            if (!store.eventCursorInitialized(hub)) store.markEventCursorInitialized(hub);
            List<HubApi.Event> events = api.events(since, 50);
            long newest = since;
            for (HubApi.Event e : events) {
                if (e.id > newest) newest = e.id;
            }
            List<HubApi.Event> fresh = newEvents(events, since);
            int hidden = fresh.size() > 3 ? fresh.size() - 2 : 0;
            for (int i = hidden; i < fresh.size(); i++) Notifier.event(c, fresh.get(i), hub, profile); // one failed post must not stall the cursor
            if (!fresh.isEmpty()) Notifier.eventSummary(c, hidden, hub, profile);
            if (newest > since) store.lastEventId(hub, newest);
        } catch (Exception ignored) { /* try again next interval */ }
    }

    static List<HubApi.Event> newEvents(List<HubApi.Event> events, long since) {
        List<HubApi.Event> fresh = new ArrayList<>();
        for (HubApi.Event e : events) if (e.id > since && !e.body.trim().isEmpty()) fresh.add(e);
        fresh.sort(Comparator.comparingLong(e -> e.id));
        return fresh;
    }
}
