package com.aihotspot.core.source;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SourceQualityServiceTests {

    @Test
    void officialSourcesAreNeverAutomaticallyDownrankedOrPaused() {
        assertThat(SourceQualityService.decideAction("OFFICIAL", "UNDERPERFORMING", 3, 20.0, 0.01))
                .isEqualTo(SourceQualityService.QualityAction.OBSERVE);
        assertThat(SourceQualityService.decideAction("FIRST_PARTY", "UNDERPERFORMING", 3, 20.0, 0.01))
                .isEqualTo(SourceQualityService.QualityAction.OBSERVE);
    }

    @Test
    void thirdPartyRequiresConsecutiveWeeksBeforeAutomaticAction() {
        assertThat(SourceQualityService.decideAction("THIRD_PARTY", "UNDERPERFORMING", 1, 30.0, 0.05))
                .isEqualTo(SourceQualityService.QualityAction.OBSERVE);
        assertThat(SourceQualityService.decideAction("THIRD_PARTY", "UNDERPERFORMING", 2, 40.0, 0.15))
                .isEqualTo(SourceQualityService.QualityAction.DOWNRANK);
        assertThat(SourceQualityService.decideAction("THIRD_PARTY", "UNDERPERFORMING", 3, 30.0, 0.05))
                .isEqualTo(SourceQualityService.QualityAction.PAUSE);
    }

    @Test
    void healthyAssessmentRestoresOnlyTheQualityWeight() {
        assertThat(SourceQualityService.decideAction("THIRD_PARTY", "HEALTHY", 0, 80.0, 0.7))
                .isEqualTo(SourceQualityService.QualityAction.RESTORE);
    }
}
