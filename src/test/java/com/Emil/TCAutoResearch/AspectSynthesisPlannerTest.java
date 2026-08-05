package com.Emil.TCAutoResearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import thaumcraft.api.aspects.Aspect;
import thaumcraft.api.aspects.AspectList;

class AspectSynthesisPlannerTest {

    @Test
    void craftsTargetWhenBothComponentsExist() {
        AspectList inventory = new AspectList().add(Aspect.AIR, 1)
            .add(Aspect.FIRE, 1);

        AspectSynthesisPlanner.Step step = AspectSynthesisPlanner.next(Aspect.LIGHT, inventory);

        assertEquals(Aspect.LIGHT, step.craft);
        assertNull(step.missing);
    }

    @Test
    void craftsMissingIntermediateBeforeTarget() {
        AspectList inventory = new AspectList().add(Aspect.AIR, 2)
            .add(Aspect.ORDER, 1);

        AspectSynthesisPlanner.Step step = AspectSynthesisPlanner.next(Aspect.FLIGHT, inventory);

        assertEquals(Aspect.MOTION, step.craft);
        assertNull(step.missing);
    }

    @Test
    void reportsTheMissingPrimal() {
        AspectList inventory = new AspectList().add(Aspect.AIR, 1);

        AspectSynthesisPlanner.Step step = AspectSynthesisPlanner.next(Aspect.LIGHT, inventory);

        assertNull(step.craft);
        assertEquals(Aspect.FIRE, step.missing);
    }
}
