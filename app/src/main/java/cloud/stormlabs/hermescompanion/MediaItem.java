package cloud.stormlabs.hermescompanion;

import android.view.View;

import java.io.File;
import java.lang.ref.WeakReference;

/** One photo or video in a conversation's gallery. {@code anchor} is the chat view it came from (weakly held), used for ordering and the open animation. */
final class MediaItem {
    final boolean video;
    final String url, name, caption;
    File file;
    int resumeMs;   // where playback left off when the video was swiped away
    final WeakReference<View> anchor;

    private MediaItem(boolean video, String url, String name, String caption, File file, View anchor) {
        this.video = video; this.url = url; this.name = name; this.caption = caption; this.file = file; this.anchor = new WeakReference<>(anchor);
    }

    static MediaItem image(String url, String alt, View anchor) {
        String c = alt == null ? "" : alt.trim();
        if (c.equalsIgnoreCase("image") || c.equalsIgnoreCase("photo") || c.equalsIgnoreCase("picture")) c = "";
        String n = url.startsWith("data:") ? "" : MarkdownView.fileName(url);
        return new MediaItem(false, url, n.isEmpty() ? "Image" : n, c, null, anchor);
    }

    static MediaItem video(String url, String name, File file, View anchor) { return new MediaItem(true, url, name, "", file, anchor); }

    /** A single downloaded file opened on its own (no conversation). */
    static MediaItem of(File f, String name, boolean video) { return new MediaItem(video, f.getPath(), name, "", f, null); }
}
