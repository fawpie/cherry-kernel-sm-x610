package dev.fawpie.cherrymanager;

enum TuningOption {
    CACHE_PRESSURE("cache-pressure", "Filesystem cache pressure", 1, 300),
    RAM_PLUS("ram-plus", "RAM Plus size at next boot"),
    READ_AHEAD("read-ahead", "Read-ahead"),
    QUEUE_DEPTH("queue-depth", "Request queue depth"),
    ECN("ecn", "Explicit Congestion Notification"),
    SACK("sack", "Selective acknowledgements"),
    TIMESTAMPS("timestamps", "TCP timestamps"),
    FAST_OPEN("fast-open", "TCP Fast Open"),
    WINDOW_SCALE("window-scale", "Window animation scale"),
    TRANSITION_SCALE("transition-scale", "Transition animation scale"),
    ANIMATOR_SCALE("animator-scale", "Animator duration scale"),
    POWER_SAVER("power-saver", "Android Battery Saver");

    final String key;
    final String title;
    final int minimum;
    final int maximum;

    TuningOption(String key, String title) {
        this(key, title, 0, 0);
    }

    TuningOption(String key, String title, int minimum, int maximum) {
        this.key = key;
        this.title = title;
        this.minimum = minimum;
        this.maximum = maximum;
    }

    String display(String value) {
        if (value.isEmpty()) return "—";
        if (value.equals("null")) return "System default";
        switch (this) {
            case RAM_PLUS:
                try { return (Integer.parseInt(value) / 1024) + " GiB"; }
                catch (NumberFormatException ignored) { return value; }
            case READ_AHEAD: return value + " KiB";
            case WINDOW_SCALE:
            case TRANSITION_SCALE:
            case ANIMATOR_SCALE: return value + "×";
            case SACK:
            case TIMESTAMPS:
            case POWER_SAVER:
                if (value.equals("0")) return "Off";
                if (value.equals("1")) return "On";
                return value;
            case ECN:
                if (value.equals("0")) return "Off";
                if (value.equals("1")) return "Accept and request";
                if (value.equals("2")) return "Accept only";
                return value;
            case FAST_OPEN:
                if (value.equals("0")) return "Off";
                if (value.equals("1")) return "Client";
                if (value.equals("2")) return "Server";
                if (value.equals("3")) return "Client and server";
                return value;
            default: return value;
        }
    }
}
