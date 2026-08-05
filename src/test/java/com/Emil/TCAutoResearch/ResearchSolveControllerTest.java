package com.Emil.TCAutoResearch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ResearchSolveControllerTest {

    @Test
    void childScreenTransitionKeepsContainerOpenOnlyOnce() {
        ResearchSolveController.prepareChildScreen();

        assertFalse(ResearchSolveController.onResearchGuiClosed());
        assertTrue(ResearchSolveController.onResearchGuiClosed());
    }
}
