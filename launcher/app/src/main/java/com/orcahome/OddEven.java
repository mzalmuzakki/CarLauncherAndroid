package com.orcahome;

import java.util.Calendar;

/**
 * Jakarta's odd-even plate rule (ganjil-genap): on weekdays that aren't public holidays,
 * 06:00-10:00 and 16:00-21:00, only plates whose last digit matches the date (odd/even) may
 * use the odd-even roads. Says so on the home screen from 05:00 to 21:00 on days the car's
 * plate does not match.
 */
final class OddEven {
    private OddEven() {}

    /** The reminder for now, or null. plate: 1 = odd, 0 = even, -1 = off. */
    static String reminder(int plate, Calendar now, boolean holiday) {
        if (plate < 0 || holiday) return null;
        int dow = now.get(Calendar.DAY_OF_WEEK), hour = now.get(Calendar.HOUR_OF_DAY);
        if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY || hour < 5 || hour >= 21) return null;
        int day = now.get(Calendar.DAY_OF_MONTH);
        if (day % 2 == plate) return null;
        return (day % 2 == 1 ? "Ganjil" : "Genap") + " today: your " + (plate == 1 ? "odd" : "even")
                + " plate can't use odd-even roads 06–10 & 16–21";
    }
}
