package com.aihotspot.core.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AgentServiceTests {
    @Test
    void permitsOnlyBoundedSafeRetries() {
        assertThat(AgentService.canRetry("FAILED", 1, false, false)).isTrue();
        assertThat(AgentService.canRetry("CANCELLED", 2, false, false)).isTrue();
        assertThat(AgentService.canRetry("SUCCEEDED", 1, false, false)).isFalse();
        assertThat(AgentService.canRetry("FAILED", 3, false, false)).isFalse();
        assertThat(AgentService.canRetry("FAILED", 1, true, false)).isFalse();
        assertThat(AgentService.canRetry("FAILED", 1, false, true)).isFalse();
    }

    @Test
    void approvalRequiresTheStoredAndCurrentArgumentHashesToMatch() {
        assertThat(AgentService.approvalHashMatches("same", "same", "same")).isTrue();
        assertThat(AgentService.approvalHashMatches("old", "same", "same")).isFalse();
        assertThat(AgentService.approvalHashMatches("same", "changed", "same")).isFalse();
        assertThat(AgentService.approvalHashMatches("", "", "")).isFalse();
    }
}
