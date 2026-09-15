package com.codepal.model;

import java.time.DayOfWeek;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 高峰计费时段窗口 —— 支持多窗口（如 DeepSeek 官方：工作日 09:00-12:00、14:00-18:00）、
 * 可限定星期、可跨午夜（end &lt; start，如 22:00-06:00）。
 */
public class PeakWindow {

    /** 生效星期（ISO：1=周一 … 7=周日）；空集 = 每天 */
    private final Set<Integer> days;
    private final int startMinutes; // 当天分钟数 [0,1439]
    private final int endMinutes;   // 当天分钟数 [0,1439]，end < start 表示跨午夜

    public PeakWindow(Set<Integer> days, int startMinutes, int endMinutes) {
        this.days = days == null ? Collections.emptySet() : new LinkedHashSet<>(days);
        this.startMinutes = Math.max(0, Math.min(1439, startMinutes));
        this.endMinutes = Math.max(0, Math.min(1439, endMinutes));
    }

    /** 判断某时刻是否落在该窗口内 */
    public boolean matches(int isoDayOfWeek, int minutesOfDay) {
        if (!days.isEmpty() && !days.contains(isoDayOfWeek)) return false;
        if (startMinutes <= endMinutes) {
            return minutesOfDay >= startMinutes && minutesOfDay <= endMinutes;
        }
        return minutesOfDay >= startMinutes || minutesOfDay <= endMinutes; // 跨午夜
    }

    /** 是否仅工作日（周一至周五） */
    public boolean isWeekdayOnly() {
        return days.size() == 5 && days.containsAll(Set.of(1, 2, 3, 4, 5));
    }

    /** "HH:mm"（也兼容纯小时 "9"）→ 当天分钟数；非法/为空返回 fallback */
    public static int parseTimeToMinutes(String s, int fallback) {
        if (s == null) return fallback;
        s = s.trim();
        if (s.isEmpty()) return fallback;
        try {
            int colon = s.indexOf(':');
            int h, m;
            if (colon >= 0) {
                h = Integer.parseInt(s.substring(0, colon).trim());
                m = Integer.parseInt(s.substring(colon + 1).trim());
            } else {
                h = Integer.parseInt(s);
                m = 0;
            }
            h = Math.max(0, Math.min(23, h));
            m = Math.max(0, Math.min(59, m));
            return h * 60 + m;
        } catch (Exception e) {
            return fallback;
        }
    }

    /** 当天分钟数 → "HH:mm" */
    public static String formatTime(int minutesOfDay) {
        int v = Math.max(0, Math.min(1439, minutesOfDay));
        return String.format("%02d:%02d", v / 60, v % 60);
    }

    public Set<Integer> getDays() { return Collections.unmodifiableSet(days); }

    public int getStartMinutes() { return startMinutes; }

    public int getEndMinutes() { return endMinutes; }

    /** 便捷：DayOfWeek → ISO 值 */
    public static int iso(DayOfWeek d) { return d.getValue(); }
}
