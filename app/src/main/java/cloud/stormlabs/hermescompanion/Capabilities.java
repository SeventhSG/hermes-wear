package cloud.stormlabs.hermescompanion;

/** Features the selected backend can expose in the app. */
public final class Capabilities {
    public final boolean jobs, skills, toolsets, approvals, steer, reasoning, sessionSearch, images, hub, models, forks, deletes;

    public Capabilities(boolean jobs, boolean skills, boolean toolsets, boolean approvals,
            boolean steer, boolean reasoning, boolean sessionSearch, boolean images, boolean hub, boolean models, boolean forks, boolean deletes) {
        this.jobs = jobs; this.skills = skills; this.toolsets = toolsets;
        this.approvals = approvals; this.steer = steer; this.reasoning = reasoning;
        this.sessionSearch = sessionSearch; this.images = images; this.hub = hub; this.models = models; this.forks = forks; this.deletes = deletes;
    }

    public static final Capabilities HERMES = new Capabilities(true, true, true, true, true, true, true, true, true, true, true, true);

    /** Keeps command suggestions and command execution behind the same capability check. */
    public boolean allowsCommand(String name) {
        switch (name) {
            case "jobs": return jobs;
            case "skills": return skills;
            case "tools": return toolsets;
            case "steer": return steer;
            case "model": return models;
            case "fork": return forks;
            case "delete": return deletes;
            case "server": case "home": case "media": case "status":
            case "undo": case "retry": case "rollback": return hub;
            default: return true;
        }
    }
}
