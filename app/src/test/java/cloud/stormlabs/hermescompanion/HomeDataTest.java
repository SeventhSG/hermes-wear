package cloud.stormlabs.hermescompanion;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class HomeDataTest {
    @Test public void tilesRoundTripEscapedPhrasesAndEmptyList() {
        List<HomeData.Tile> tiles = Arrays.asList(new HomeData.Tile("Good night", "Check doors, then say \"good night\""));
        List<HomeData.Tile> restored = HomeData.tiles(HomeData.tilesJson(tiles));
        assertEquals(1, restored.size());
        assertEquals(tiles.get(0).phrase, restored.get(0).phrase);
        assertEquals(0, HomeData.tiles("[]").size());
        assertEquals(0, HomeData.tiles("broken").size());
    }

    @Test public void placeRoundTripKeepsOptIn() {
        HomeData.Place place = new HomeData.Place("id", "Home", 42.1, 23.2, 150, "arrive", "leave", false);
        HomeData.Place restored = HomeData.places(HomeData.placesJson(Arrays.asList(place))).get(0);
        assertEquals(150, restored.radius, 0.01);
        assertEquals("leave", restored.leave);
        assertFalse(restored.enabled);
    }
    @Test public void oldSmallRadiusIsClamped() {
        HomeData.Place old = new HomeData.Place("id", "Home", 42.1, 23.2, 25, "arrive", "leave", true);
        assertEquals(100, old.radius, 0.01);
        assertEquals(100, HomeData.places("[{\"id\":\"id\",\"radius\":25}]").get(0).radius, 0.01);
    }

    @Test public void hostThresholdBoundaries() {
        assertEquals(0, HostThresholds.percent(79.9));
        assertEquals(1, HostThresholds.percent(80));
        assertEquals(2, HostThresholds.percent(90));
        assertEquals(0, HostThresholds.load(4, 4));
        assertEquals(1, HostThresholds.load(4.1, 4));
        assertEquals(0, HostThresholds.ratio(10, 0), 0);
    }

    @org.junit.Test public void quickCommandIconsAndRepeatedPhrases() {
        org.junit.Assert.assertEquals("light", HomeData.iconKey("Lights off", "Turn off all the lights"));
        org.junit.Assert.assertEquals("night", HomeData.iconKey("Good night", "Run my good night routine in Home Assistant"));
        org.junit.Assert.assertEquals("leave", HomeData.iconKey("Leaving home", "I'm leaving home"));
        org.junit.Assert.assertEquals("home", HomeData.iconKey("I\u2019m home", "I'm home"));
        org.junit.Assert.assertEquals("shop", HomeData.iconKey("Shopping list", "Read my Home Assistant shopping list"));
        org.junit.Assert.assertEquals("", HomeData.iconKey("Weather", "What's the weather"));
        org.junit.Assert.assertEquals("night", HomeData.iconKey("Good night", "Run my good night routine: lights off, lock up"));
        org.junit.Assert.assertTrue(HomeData.phraseRepeatsLabel("I\u2019m home", "I'm home"));
        org.junit.Assert.assertFalse(HomeData.phraseRepeatsLabel("Leaving home", "I'm leaving home"));
    }
}
