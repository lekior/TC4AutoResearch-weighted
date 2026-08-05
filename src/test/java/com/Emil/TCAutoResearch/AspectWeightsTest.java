package com.Emil.TCAutoResearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class AspectWeightsTest {

    @Test
    void percentileWeightsArePowersOfTwoAndDecreaseWithStock() {
        Map<String, Integer> stock = new LinkedHashMap<>();
        stock.put("missing", 0);
        for (int amount = 1; amount <= 100; amount++) stock.put("aspect" + amount, amount);

        Map<String, Integer> costs = AspectWeights.inventoryCosts(stock);

        int previous = Integer.MAX_VALUE;
        for (int amount = 1; amount <= 100; amount++) {
            int cost = costs.get("aspect" + amount);
            assertTrue(cost <= previous);
            assertTrue((cost & cost - 1) == 0);
            previous = cost;
        }
        assertEquals(512, costs.get("missing"));
        assertEquals(256, costs.get("aspect10"));
        assertEquals(64, costs.get("aspect30"));
        assertEquals(16, costs.get("aspect50"));
        assertEquals(4, costs.get("aspect70"));
        assertEquals(1, costs.get("aspect90"));
        assertEquals(1, costs.get("aspect98"));
        assertEquals(1, costs.get("aspect100"));
    }

    @Test
    void tiesUseTheirSharedMidrank() {
        Map<String, Integer> stock = new LinkedHashMap<>();
        stock.put("air", 16);
        stock.put("fire", 16);
        stock.put("water", 16);

        Map<String, Integer> costs = AspectWeights.inventoryCosts(stock);

        assertEquals(16, costs.get("air"));
        assertEquals(costs.get("air"), costs.get("fire"));
        assertEquals(costs.get("fire"), costs.get("water"));
    }

    @Test
    void manualWeightsAreClampedToTheConfiguredRange() {
        assertEquals(0, AspectWeights.clamp(-1));
        assertEquals(512, AspectWeights.clamp(513));
    }
}
