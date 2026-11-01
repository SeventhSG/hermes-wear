package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class JobsScheduleTest {
    @Test public void humanizesSupportedSchedules() {
        assertEquals("Every 30 min", JobsActivity.humanSchedule("every 30m"));
        assertEquals("Every 6 h", JobsActivity.humanSchedule("every 360m"));
        assertEquals("Every 90 min", JobsActivity.humanSchedule("every 90m"));
        assertEquals("Every day", JobsActivity.humanSchedule("every 1440m"));
        assertEquals("Daily at 09:00, 21:00", JobsActivity.humanSchedule("0 9,21 * * *"));
    }

    @Test public void preservesOtherSchedules() {
        assertEquals("weekdays 9am", JobsActivity.humanSchedule("weekdays 9am"));
        assertEquals("0 9 * * 1-5", JobsActivity.humanSchedule("0 9 * * 1-5"));
        assertEquals("70 9 * * *", JobsActivity.humanSchedule("70 9 * * *"));
    }
}
