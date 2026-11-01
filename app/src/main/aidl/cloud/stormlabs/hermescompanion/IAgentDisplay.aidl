package cloud.stormlabs.hermescompanion;

/** Runs in a Shizuku user service as the shell user: owns Hermes' hidden display. */
interface IAgentDisplay {
    /** Creates the hidden display once (later calls return the same id). */
    int create(int width, int height, int dpi) = 1;
    /** Runs a shell command (am / input / cmd) and returns its output. */
    String exec(String command) = 2;
    /** Latest frame of the hidden display as JPEG, or empty when none is available yet. */
    byte[] screenshot(int quality) = 3;
    /** Removes the display; apps on it are closed. */
    void release() = 4;
    /** Shizuku's reserved transaction for stopping the service. */
    void destroy() = 16777114;
}
