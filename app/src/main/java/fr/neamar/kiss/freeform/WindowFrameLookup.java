package fr.neamar.kiss.freeform;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parse only observed WindowState records, never synthesize a frame from task configuration. */
final class WindowFrameLookup {
    private static final Pattern HEADER = Pattern.compile("^\\s*Window #\\d+ Window\\{([0-9a-fA-F]+) u(\\d+) (.+?)\\}:\\s*$");
    private static final Pattern TASK = Pattern.compile("\\bmDisplayId=(\\d+)(?:\\s+taskId=(\\d+))?\\b");
    private static final Pattern FRAME = Pattern.compile("\\bframe=\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]");
    private static final Pattern MAIN = Pattern.compile("\\bty=(?:BASE_APPLICATION|APPLICATION|1|2)(?:\\s|\\}|$)");
    static final class Window {
        String hash, name;
        int user, display = -1, task = -1;
        boolean visible, surface, main, base, exiting;
        int[] frame;
        boolean valid() {
            return display == 0 && visible && surface && !exiting && frame != null
                    && frame[0] < frame[2] && frame[1] < frame[3];
        }
    }
    private WindowFrameLookup() {}
    static List<Window> parse(String text) {
        List<Window> rows = new ArrayList<>();
        Window current = null;
        for (String line : text.split("\\r?\\n")) {
            Matcher header = HEADER.matcher(line);
            if (header.matches()) {
                current = new Window();
                current.hash = header.group(1);
                current.user = Integer.parseInt(header.group(2));
                current.name = header.group(3);
                current.exiting = current.name.endsWith(" EXITING");
                rows.add(current);
                continue;
            }
            if (current == null) continue;
            Matcher task = TASK.matcher(line);
            if (task.find()) {
                current.display = Integer.parseInt(task.group(1));
                if (task.group(2) != null) current.task = Integer.parseInt(task.group(2));
            }
            if (line.trim().startsWith("mAttrs=")) {
                current.main = MAIN.matcher(line).find();
                current.base = line.matches(".*\\bty=(BASE_APPLICATION|1)(\\s|\\}|$).*");
            }
            if (line.contains("mHasSurface=true")) current.surface = true;
            if (line.trim().equals("isVisible=true")) current.visible = true;
            if (line.contains("mAnimatingExit=true") || line.contains("mDestroying=true")
                    || line.contains("mRemoved=true")) current.exiting = true;
            if (line.trim().startsWith("Frames:")) {
                Matcher frame = FRAME.matcher(line);
                if (frame.find()) {
                    current.frame = new int[4];
                    for (int i=0; i<4; i++) current.frame[i] = Integer.parseInt(frame.group(i+1));
                }
            }
        }
        return rows;
    }
    static Window find(List<Window> windows, int task, int user) {
        Window application = null;
        for (Window window : windows) {
            if (!window.valid() || !window.main || window.task != task || window.user != user) continue;
            if (window.base) return window;
            if (application == null) application = window;
        }
        return application;
    }
}
