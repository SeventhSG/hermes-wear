package cloud.stormlabs.hermescompanion;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Small JSON records shared by Home, Places and the permission centre. */
final class HomeData {
    private HomeData() {}

    static final class Tile {
        final String label, phrase;
        Tile(String label, String phrase) { this.label = label; this.phrase = phrase; }
    }

    static final class Place {
        final String id, name, arrive, leave;
        final double latitude, longitude;
        final float radius;
        final boolean enabled;
        Place(String id, String name, double latitude, double longitude, float radius,
              String arrive, String leave, boolean enabled) {
            this.id = id; this.name = name; this.latitude = latitude; this.longitude = longitude;
            this.radius = Math.max(100, radius); this.arrive = arrive; this.leave = leave; this.enabled = enabled;
        }
        Place enabled(boolean value) { return new Place(id, name, latitude, longitude, radius, arrive, leave, value); }
    }

    static List<Tile> tiles(String json) {
        List<Tile> out = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.optJSONObject(i);
                if (o != null && !o.optString("label").trim().isEmpty() && !o.optString("phrase").trim().isEmpty())
                    out.add(new Tile(o.optString("label"), o.optString("phrase")));
            }
        } catch (Exception ignored) { }
        return out;
    }

    static String tilesJson(List<Tile> tiles) {
        JSONArray array = new JSONArray();
        for (Tile tile : tiles) {
            try { array.put(new JSONObject().put("label", tile.label).put("phrase", tile.phrase)); }
            catch (Exception ignored) { }
        }
        return array.toString();
    }

    static List<Place> places(String json) {
        List<Place> out = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.optJSONObject(i);
                if (o != null && !o.optString("id").isEmpty()) out.add(new Place(o.optString("id"), o.optString("name"),
                        o.optDouble("latitude"), o.optDouble("longitude"), (float) o.optDouble("radius", 150),
                        o.optString("arrive"), o.optString("leave"), o.optBoolean("enabled", true)));
            }
        } catch (Exception ignored) { }
        return out;
    }

    static String placesJson(List<Place> places) {
        JSONArray array = new JSONArray();
        for (Place p : places) {
            try { array.put(new JSONObject().put("id", p.id).put("name", p.name)
                    .put("latitude", p.latitude).put("longitude", p.longitude).put("radius", p.radius)
                    .put("arrive", p.arrive).put("leave", p.leave).put("enabled", p.enabled)); }
            catch (Exception ignored) { }
        }
        return array.toString();
    }

    /** Which glyph a quick command gets, from words in its label or phrase. */
    static String iconKey(String label, String phrase) {
        String byLabel = iconKeyOf(label);  // the label names the command; the phrase may mention other things
        return byLabel.isEmpty() ? iconKeyOf(phrase) : byLabel;
    }

    private static String iconKeyOf(String text) {
        String t = (text == null ? "" : text).toLowerCase(java.util.Locale.ROOT);
        if (t.matches(".*\\b(light|lights|lamp|bright)\\b.*")) return "light";
        if (t.matches(".*\\b(night|sleep|bed|bedtime)\\b.*")) return "night";
        if (t.matches(".*\\b(leav(e|ing)|away|out)\\b.*")) return "leave";
        if (t.matches(".*\\b(shop|shopping|grocer(y|ies)|list)\\b.*")) return "shop";
        if (t.matches(".*\\b(home|arriv(e|ed|ing))\\b.*")) return "home";
        if (t.matches(".*\\b(music|play|speaker|volume)\\b.*")) return "music";
        return "";
    }

    /** True when the phrase only repeats the label ("I'm home" / "I'm home"), so it needn't be shown twice. */
    static boolean phraseRepeatsLabel(String label, String phrase) {
        java.util.function.Function<String, String> norm = x -> x == null ? "" : x.toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
        return norm.apply(label).equals(norm.apply(phrase));
    }
}
