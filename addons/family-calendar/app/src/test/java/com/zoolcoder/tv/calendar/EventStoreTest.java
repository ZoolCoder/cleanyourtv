package com.zoolcoder.tv.calendar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public class EventStoreTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static Event event(int n) {
        return new Event(String.format("%012x", n), "Event " + n, LocalDate.of(2026, 10, 26), LocalTime.of(10, 0),
                LocalTime.of(11, 0), "Mom", 2, Event.Repeat.YEARLY, Event.Reminder.MIN_10);
    }

    private EventStore store() {
        return new EventStore(file(), Runnable::run);
    }

    private File file() {
        return new File(folder.getRoot(), EventStore.FILE);
    }

    @Test
    public void survivesARestart() {
        EventStore store = store();
        assertEquals(EventStore.Added.OK, store.add(event(1)));
        Event allDay = new Event("00000000000b", "Eid", LocalDate.of(2026, 3, 20), null, null, null, 0,
                Event.Repeat.NONE, Event.Reminder.NONE);
        assertEquals(EventStore.Added.OK, store.add(allDay));
        store.markFired(Arrays.asList("000000000001@2026-10-26"), LocalDate.of(2026, 10, 26));

        EventStore again = store();
        assertEquals(2, again.events().size());
        Event e = again.events().get(0);
        assertEquals("Event 1", e.title);
        assertEquals(LocalTime.of(10, 0), e.start);
        assertEquals(LocalTime.of(11, 0), e.end);
        assertEquals("Mom", e.who);
        assertEquals(2, e.color);
        assertEquals(Event.Repeat.YEARLY, e.repeat);
        assertEquals(Event.Reminder.MIN_10, e.reminder);
        assertTrue(again.events().get(1).allDay());
        assertTrue(again.fired().contains("000000000001@2026-10-26"));
        assertFalse(new File(file().getPath() + ".tmp").exists());
    }

    @Test
    public void refusesThe501stEvent() {
        EventStore store = store();
        for (int i = 0; i < EventStore.MAX_EVENTS; i++) assertEquals(EventStore.Added.OK, store.add(event(i)));
        assertEquals(EventStore.Added.FULL, store.add(event(9999)));
        assertEquals(EventStore.MAX_EVENTS, store().events().size());
    }

    @Test
    public void deletesById() {
        EventStore store = store();
        store.add(event(1));
        store.add(event(2));
        assertTrue(store.delete(String.format("%012x", 1)));
        assertFalse(store.delete(String.format("%012x", 1)));
        assertEquals(1, store().events().size());
    }

    @Test
    public void tellsListenersAboutChanges() {
        EventStore store = store();
        AtomicInteger calls = new AtomicInteger();
        Runnable listener = calls::incrementAndGet;
        store.listen(listener);
        store.add(event(1));
        store.delete(String.format("%012x", 1));
        store.delete("ffffffffffff");
        assertEquals(2, calls.get());
        store.unlisten(listener);
        store.add(event(2));
        assertEquals(2, calls.get());
    }

    @Test
    public void garbageGivesAnEmptyCalendar() throws IOException {
        Files.write(file().toPath(), "{not json".getBytes(StandardCharsets.UTF_8));
        assertTrue(store().events().isEmpty());
    }

    @Test
    public void anUnreadableFileIsKeptAsideNotOverwritten() throws IOException {
        Files.write(file().toPath(), "{not json".getBytes(StandardCharsets.UTF_8));
        EventStore store = store();
        File bad = new File(file().getPath() + ".bad");
        assertTrue(bad.exists());
        assertFalse(file().exists());
        store.add(event(1));
        assertEquals("{not json", new String(Files.readAllBytes(bad.toPath()), StandardCharsets.UTF_8));
        assertEquals(1, store().events().size());
    }

    @Test
    public void aFailedWriteDropsTheEventAndSaysSo() {
        EventStore store = new EventStore(new File(folder.getRoot(), "missing/" + EventStore.FILE), Runnable::run);
        AtomicInteger calls = new AtomicInteger();
        store.listen(calls::incrementAndGet);
        assertEquals(EventStore.Added.NOT_SAVED, store.add(event(1)));
        assertTrue(store.events().isEmpty());
        assertEquals(0, calls.get());
    }

    @Test
    public void remembersEventsFromAbroad() {
        Event away = new Event("00000000000a", "Visit", LocalDate.of(2026, 11, 3), null, null, "Amna", 1,
                Event.Repeat.NONE, Event.Reminder.NONE, true);
        store().add(away);
        store().add(event(2));
        List<Event> events = store().events();
        assertTrue(events.get(0).away);
        assertFalse(events.get(1).away);
    }

    @Test
    public void shownRemindersAreWrittenInTheBackground() {
        List<Runnable> queued = new ArrayList<>();
        EventStore store = new EventStore(file(), queued::add);
        store.markFired(Arrays.asList("a@2026-10-26"), LocalDate.of(2026, 10, 26));
        assertFalse(file().exists());
        assertEquals(1, queued.size());
        queued.get(0).run();
        assertTrue(store().fired().contains("a@2026-10-26"));
    }

    @Test
    public void anOlderCopyNeverOverwritesANewerOne() {
        List<Runnable> queued = new ArrayList<>();
        EventStore store = new EventStore(file(), queued::add);
        store.markFired(Arrays.asList("a@2026-10-26"), LocalDate.of(2026, 10, 26));
        store.add(event(1));
        queued.get(0).run();
        EventStore again = store();
        assertEquals(1, again.events().size());
        assertTrue(again.fired().contains("a@2026-10-26"));
    }

    @Test
    public void skipsUnreadableEvents() {
        List<Event> events = new ArrayList<>();
        Set<String> fired = new HashSet<>();
        EventStore.parse("{\"events\":[{\"id\":\"../../etc\",\"title\":\"x\",\"date\":\"2026-01-01\"},"
                + "{\"id\":\"0123456789ab\",\"title\":\"\",\"date\":\"2026-01-01\"},"
                + "{\"id\":\"0123456789ab\",\"title\":\"ok\",\"date\":\"2026-13-01\"},"
                + "{\"id\":\"0123456789ab\",\"title\":\"ok\",\"date\":\"2026-01-01\",\"color\":17},"
                + "{\"id\":\"0123456789ac\",\"title\":\"kept\",\"date\":\"2026-01-01\"}, 5],"
                + "\"fired\":[\"a@2026-01-01\"]}", events, fired);
        // A colour out of range falls back to the name's colour instead of losing the event.
        assertEquals(2, events.size());
        assertEquals(0, events.get(0).color);
        assertEquals("kept", events.get(1).title);
        assertEquals(1, fired.size());
    }

    @Test
    public void forgetsOldFiredKeys() {
        EventStore store = store();
        LocalDate today = LocalDate.of(2026, 10, 26);
        store.markFired(Arrays.asList("a@2026-10-20", "b@2026-10-23", "c@2026-10-26", "junk"), today);
        Set<String> fired = store.fired();
        assertFalse(fired.contains("a@2026-10-20"));
        assertTrue(fired.contains("b@2026-10-23"));
        assertTrue(fired.contains("c@2026-10-26"));
        assertFalse(fired.contains("junk"));
    }
}
