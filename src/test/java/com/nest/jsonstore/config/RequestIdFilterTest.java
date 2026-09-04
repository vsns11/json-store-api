package com.nest.jsonstore.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdFilterTest {

    @Test
    void keepsAWellFormedIdAsItIs() {
        assertThat(RequestIdFilter.sanitize("req-42_ab")).isEqualTo("req-42_ab");
    }

    @Test
    void stripsCharactersThatDoNotBelongInALogLine() {
        assertThat(RequestIdFilter.sanitize("a.b/c d\n")).isEqualTo("abcd");
    }

    @Test
    void cutsAnOverlongIdWithoutFallingOver() {
        String cleaned = RequestIdFilter.sanitize("x".repeat(70) + "!!!");
        assertThat(cleaned).hasSize(RequestIdFilter.MAX_LENGTH);
    }

    @Test
    void makesUpAnIdWhenNothingUsableWasSent() {
        assertThat(RequestIdFilter.sanitize(null)).matches("[0-9a-f-]{36}");
        assertThat(RequestIdFilter.sanitize("!!!")).matches("[0-9a-f-]{36}");
    }
}
