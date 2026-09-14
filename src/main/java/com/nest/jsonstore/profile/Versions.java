package com.nest.jsonstore.profile;

import com.nest.jsonstore.error.PreconditionRequiredException;

import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A profile's ETag is its version number. Reads send it, and a change must send it back in
 * {@code If-Match}, so the server can refuse a change made to a copy that is out of date.
 */
final class Versions {

    private static final Pattern TAG = Pattern.compile("(?:W/)?\"(\\d{1,18})\"");

    private Versions() {
    }

    static String etag(long version) {
        return "\"" + version + "\"";
    }

    /**
     * The version a change was made to: empty for {@code *}, which overwrites whatever is stored. An
     * If-Match naming no version this API issued can match nothing, so it reads as a version that
     * never exists and the change is refused as out of date.
     *
     * @throws PreconditionRequiredException when there is no If-Match at all
     */
    static OptionalLong expected(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new PreconditionRequiredException();
        }
        String value = ifMatch.trim();
        if (value.equals("*")) {
            return OptionalLong.empty();
        }
        // A list is allowed; a change made to any of the versions listed may go ahead.
        for (String candidate : value.split(",")) {
            Matcher matcher = TAG.matcher(candidate.trim());
            if (matcher.matches()) {
                return OptionalLong.of(Long.parseLong(matcher.group(1)));
            }
        }
        return OptionalLong.of(-1);
    }
}
