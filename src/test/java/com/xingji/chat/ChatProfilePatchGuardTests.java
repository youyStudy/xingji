package com.xingji.chat;

import com.xingji.dto.AnchorStateDTO;
import com.xingji.dto.ChatProfileDTO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ChatProfilePatchGuardTests {

    private final ChatProfilePatchGuard guard = new ChatProfilePatchGuard();

    @Test
    void bareAcknowledgementDoesNotTurnAnOpenQuestionIntoThreeDays() {
        ChatProfileDTO patch = new ChatProfileDTO();
        patch.setDays(3);
        patch.setBudget(BigDecimal.valueOf(1000));

        ChatProfileDTO guarded = guard.guard(
                patch, "你大概什么时候想去？玩几天？预算多少？", true);

        assertNull(guarded.getDays());
        assertNull(guarded.getBudget());
    }

    @Test
    void bareAcknowledgementCanConfirmAValueTheAssistantActuallyProposed() {
        ChatProfileDTO patch = new ChatProfileDTO();
        patch.setCity("杭州");
        patch.setDays(2);

        ChatProfileDTO guarded = guard.guard(
                patch, "那先按杭州两天市区慢逛搭个骨架，行吗？", true);

        assertEquals("杭州", guarded.getCity());
        assertEquals(2, guarded.getDays());
    }

    @Test
    void bareAcknowledgementCannotInventAnchorDetails() {
        ChatProfileDTO patch = new ChatProfileDTO();
        AnchorStateDTO arrival = new AnchorStateDTO();
        arrival.setDate("2026-12-15");
        arrival.setTime("10:00");
        arrival.setLocation("杭州东站");
        patch.setArrival(arrival);

        ChatProfileDTO guarded = guard.guard(patch, "你大概哪天到杭州？", true);

        assertNull(guarded.getArrival());
    }

    @Test
    void nonBareTurnKeepsPatchUnchanged() {
        ChatProfileDTO patch = new ChatProfileDTO();
        patch.setDays(3);

        assertSame(patch, guard.guard(patch, "你想玩几天？", false));
    }
}
