package com.Emil.TCAutoResearch;

import java.util.HashSet;
import java.util.Set;

import thaumcraft.api.aspects.Aspect;
import thaumcraft.api.aspects.AspectList;

/** Selects one server-confirmed synthesis step without mutating inventory. */
final class AspectSynthesisPlanner {

    private AspectSynthesisPlanner() {}

    static Step next(Aspect target, AspectList inventory) {
        if (target == null) return new Step(null, null);
        return find(target, inventory, new HashSet<>());
    }

    private static Step find(Aspect aspect, AspectList inventory, Set<Aspect> visiting) {
        Aspect[] components = aspect.getComponents();
        if (components == null || !visiting.add(aspect)) return new Step(null, aspect);
        int firstNeeded = components[0] == components[1] ? 2 : 1;
        if (inventory.getAmount(components[0]) < firstNeeded) {
            Step result = find(components[0], inventory, visiting);
            visiting.remove(aspect);
            return result;
        }
        if (inventory.getAmount(components[1]) <= 0) {
            Step result = find(components[1], inventory, visiting);
            visiting.remove(aspect);
            return result;
        }
        visiting.remove(aspect);
        return new Step(aspect, null);
    }

    static final class Step {

        final Aspect craft;
        final Aspect missing;

        private Step(Aspect craft, Aspect missing) {
            this.craft = craft;
            this.missing = missing;
        }
    }
}
