package cloud.stormlabs.hermescompanion;

import java.util.List;

final class SharePreview {
    private SharePreview() { }

    static String text(String body, List<String> names, int fileCount) {
        StringBuilder preview = new StringBuilder();
        if (body != null && !body.isEmpty()) {
            preview.append(body, 0, Math.min(body.length(), 300));
            if (body.length() > 300) preview.append('…');
        }
        if (fileCount > 0) {
            if (preview.length() > 0) preview.append("\n\n");
            preview.append(fileCount).append(fileCount == 1 ? " file" : " files");
            for (String name : names) {
                preview.append("\n");
                if (name.length() > 60) preview.append(name, 0, 60).append('…');
                else preview.append(name);
            }
            if (fileCount > names.size()) preview.append("\n…and ").append(fileCount - names.size()).append(" more");
        }
        return preview.toString();
    }
}
