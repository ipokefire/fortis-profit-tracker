package com.fortisprofit;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative parser of Colosseum-related game messages, not a verified game-state varbit. */
final class WaveMessages
{
    private static final Pattern WAVE = Pattern.compile("\\bwave\\s*(?:number\\s*)?[:#-]?\\s*(1[0-2]|[1-9])(?:\\s*/\\s*12)?\\b");
    private static final Pattern COMPLETED = Pattern.compile(
        "(?:\\bcompleted\\s+(?:the\\s+)?wave\\b)|(?:\\b(?:wave\\s*(?:number\\s*)?[:#-]?\\s*(?:1[0-2]|[1-9]))\\s+(?:is\\s+)?(?:complete|completed|cleared|finished)\\b)");
    // The in-game reward message has no Colosseum name, so recognize its distinctive
    // full text rather than requiring the player to have an active tracked attempt.
    private static final Pattern COLOSSEUM_WAVE_COMPLETED = Pattern.compile(
        "\\bwave\\s+(1[0-2]|[1-9])\\s+completed!\\s+wave\\s+duration:\\s*\\d{1,2}:\\d{2}(?::\\d{2})?\\b",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern OSRS_COLOR_CODES = Pattern.compile("@[a-zA-Z0-9_]+@");
    private WaveMessages() { }

    static String normalize(String message)
    {
        if (message == null) return "";
        return OSRS_COLOR_CODES.matcher(message).replaceAll("")
            .toLowerCase(Locale.ROOT).trim();
    }

    static int confirmedColosseumCompletion(String message)
    {
        Matcher matcher = COLOSSEUM_WAVE_COMPLETED.matcher(normalize(message));
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    static int wave(String message)
    {
        Matcher match = WAVE.matcher(message.toLowerCase(Locale.ROOT));
        if (!match.find()) return 0;
        return Integer.parseInt(match.group(1));
    }

    static boolean completed(String message)
    {
        return COMPLETED.matcher(message.toLowerCase(Locale.ROOT)).find();
    }
}
