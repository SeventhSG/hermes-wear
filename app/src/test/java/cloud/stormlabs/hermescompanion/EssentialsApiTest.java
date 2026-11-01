package cloud.stormlabs.hermescompanion;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class EssentialsApiTest {
    private static final String FULL_ITEM = "{\n"
            + "  \"id\": \"cap_7f31\",\n"
            + "  \"kind\": \"voice\",\n"
            + "  \"text\": \"buy milk and call the plumber\",\n"
            + "  \"title\": \"Groceries\",\n"
            + "  \"transcript\": \"buy milk and call the plumber\",\n"
            + "  \"triage\": \"todo\",\n"
            + "  \"note\": \"from the car\",\n"
            + "  \"status\": \"ready\",\n"
            + "  \"last_error\": \"\",\n"
            + "  \"ts\": 1758096000.5,\n"
            + "  \"updated_at\": 1758096123.25,\n"
            + "  \"actions\": [\"archive\", \"retry\"],\n"
            + "  \"commands\": [\n"
            + "    {\"id\": \"cmd_1\", \"text\": \"re-transcribe\", \"state\": \"done\", \"at\": \"2026-09-17T10:00:00Z\"},\n"
            + "    {\"id\": \"cmd_2\", \"text\": \"summarise\", \"state\": \"queued\", \"at\": \"2026-09-17T10:02:00Z\"}\n"
            + "  ]\n"
            + "}";

    @Test public void fullItemParsesEveryField() throws Exception {
        EssentialsApi.Item item = EssentialsApi.parseItem(new JSONObject(FULL_ITEM));
        assertEquals("cap_7f31", item.id);
        assertEquals("voice", item.kind);
        assertEquals("buy milk and call the plumber", item.text);
        assertEquals("Groceries", item.title);
        assertEquals("buy milk and call the plumber", item.transcript);
        assertEquals("todo", item.triage);
        assertEquals("from the car", item.note);
        assertEquals("ready", item.status);
        assertEquals("", item.lastError);
        assertEquals(1758096000.5, item.ts, 0.0001);
        assertEquals(1758096123.25, item.updatedAt, 0.0001);
        assertEquals(2, item.actions.size());
        assertEquals("archive", item.actions.get(0));
        assertEquals("retry", item.actions.get(1));
        assertEquals(2, item.commands.size());
        EssentialsApi.Command first = item.commands.get(0);
        assertEquals("cmd_1", first.id);
        assertEquals("re-transcribe", first.text);
        assertEquals("done", first.state);
        assertEquals("2026-09-17T10:00:00Z", first.at);
        assertEquals("queued", item.commands.get(1).state);
    }

    @Test public void emptyItemYieldsBlanksAndEmptyLists() throws Exception {
        EssentialsApi.Item item = EssentialsApi.parseItem(new JSONObject("{}"));
        assertEquals("", item.id);
        assertEquals("", item.kind);
        assertEquals("", item.text);
        assertEquals("", item.title);
        assertEquals("", item.transcript);
        assertEquals("", item.triage);
        assertEquals("", item.note);
        assertEquals("", item.status);
        assertEquals("", item.lastError);
        assertEquals(0.0, item.ts, 0.0);
        assertEquals(0.0, item.updatedAt, 0.0);
        assertTrue(item.actions.isEmpty());
        assertTrue(item.commands.isEmpty());
    }

    @Test public void partialItemDoesNotThrow() throws Exception {
        EssentialsApi.Item item = EssentialsApi.parseItem(new JSONObject("{\"id\":\"cap_1\",\"ts\":12}"));
        assertEquals("cap_1", item.id);
        assertEquals(12.0, item.ts, 0.0);
        assertEquals("", item.status);
        assertTrue(item.actions.isEmpty());
        assertTrue(item.commands.isEmpty());
    }

    @Test public void nullJsonObjectIsTolerated() throws Exception {
        EssentialsApi.Item item = EssentialsApi.parseItem(null);
        assertEquals("", item.id);
        assertTrue(item.actions.isEmpty());
    }

    @Test public void wrongTypedArraysYieldEmptyLists() throws Exception {
        EssentialsApi.Item item = EssentialsApi.parseItem(new JSONObject(
                "{\"id\":\"cap_2\",\"actions\":\"archive\",\"commands\":\"none\",\"transcript\":null}"));
        assertEquals("cap_2", item.id);
        assertTrue(item.actions.isEmpty());
        assertTrue(item.commands.isEmpty());
    }

    @Test public void nonObjectCommandEntriesAreSkipped() throws Exception {
        EssentialsApi.Item item = EssentialsApi.parseItem(new JSONObject(
                "{\"id\":\"cap_3\",\"commands\":[42,\"junk\",{\"id\":\"cmd_ok\",\"state\":\"queued\"}]}"));
        assertEquals(1, item.commands.size());
        assertEquals("cmd_ok", item.commands.get(0).id);
        assertEquals("queued", item.commands.get(0).state);
        assertEquals("", item.commands.get(0).text);
    }

    @Test public void missingNumberFieldsFallBackToZero() throws Exception {
        EssentialsApi.Item item = EssentialsApi.parseItem(new JSONObject("{\"id\":\"cap_4\",\"ts\":null}"));
        assertEquals(0.0, item.ts, 0.0);
        assertEquals(0.0, item.updatedAt, 0.0);
    }

    @Test public void syncBodyYieldsVersionAndItems() throws Exception {
        String body = "{\"version\":1758096123.5,\"count\":2,\"items\":["
                + "{\"id\":\"cap_a\",\"status\":\"ready\"},{\"id\":\"cap_b\",\"status\":\"processing\"}]}";
        EssentialsApi.Sync sync = EssentialsApi.parseSync(body, 0);
        assertEquals(1758096123.5, sync.version, 0.0001);
        assertEquals(2, sync.items.size());
        assertEquals("cap_a", sync.items.get(0).id);
        assertEquals("processing", sync.items.get(1).status);
    }

    @Test public void syncWithoutItemsYieldsEmptyList() throws Exception {
        EssentialsApi.Sync sync = EssentialsApi.parseSync("{\"version\":9.5,\"count\":0}", 0);
        assertEquals(9.5, sync.version, 0.0001);
        assertTrue(sync.items.isEmpty());
    }

    @Test public void syncResultOf304IsUnchanged() throws Exception {
        EssentialsApi.Sync sync = EssentialsApi.syncResult(304, "", 1758096000.5);
        assertEquals(1758096000.5, sync.version, 0.0001);
        assertTrue(sync.items.isEmpty());
    }

    @Test public void syncResultOfEmptyBodyKeepsWatermark() throws Exception {
        EssentialsApi.Sync sync = EssentialsApi.syncResult(200, "", 42.5);
        assertEquals(42.5, sync.version, 0.0001);
        assertTrue(sync.items.isEmpty());
        EssentialsApi.Sync nullBody = EssentialsApi.parseSync(null, 7.25);
        assertEquals(7.25, nullBody.version, 0.0001);
        assertTrue(nullBody.items.isEmpty());
    }

    @Test public void itemsParsesBareArray() throws Exception {
        EssentialsApi.Sync parsed = EssentialsApi.parseSync(
                "{\"version\":3,\"items\":[]}", 0);
        assertTrue(parsed.items.isEmpty());
        assertEquals(2, EssentialsApi.parseItems("[{\"id\":\"a\"},{\"id\":\"b\"}]").size());
        assertEquals("b", EssentialsApi.parseItems("[{\"id\":\"a\"},{\"id\":\"b\"}]").get(1).id);
    }

    @Test public void itemsParsesWrappedArrayAndToleratesJunk() throws Exception {
        assertEquals(1, EssentialsApi.parseItems("{\"items\":[{\"id\":\"a\"}]}").size());
        assertEquals(1, EssentialsApi.parseItems("[{\"id\":\"a\"},\"junk\",null]").size());
        assertTrue(EssentialsApi.parseItems("").isEmpty());
        assertTrue(EssentialsApi.parseItems((String)null).isEmpty());
        assertTrue(EssentialsApi.parseItems("{\"count\":0}").isEmpty());
    }

    @Test public void parsesCountedItemFromSyncPayload() throws Exception {
        EssentialsApi.Sync sync = EssentialsApi.parseSync("{\"version\":1,\"items\":[" + FULL_ITEM + "]}", 0);
        assertEquals(1, sync.items.size());
        EssentialsApi.Item item = sync.items.get(0);
        assertEquals("cap_7f31", item.id);
        assertEquals(2, item.commands.size());
        assertEquals("2026-09-17T10:02:00Z", item.commands.get(1).at);
    }
}
