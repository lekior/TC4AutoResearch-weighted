package com.Emil.TCAutoResearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ContainerTransferControllerTest {

    @AfterEach
    void clearTransfer() {
        ContainerTransferController.clear();
    }

    @Test
    void acceptsOnlyTheMatchingContainerTransaction() {
        ContainerTransferController.beginTracking(7, (short) 12);

        ContainerTransferController.onConfirmation(8, (short) 12, true);
        assertEquals(ContainerTransferController.Status.WAITING, ContainerTransferController.status());

        ContainerTransferController.onConfirmation(7, (short) 11, true);
        assertEquals(ContainerTransferController.Status.WAITING, ContainerTransferController.status());

        ContainerTransferController.onConfirmation(7, (short) 12, true);
        assertEquals(ContainerTransferController.Status.ACCEPTED, ContainerTransferController.status());
    }

    @Test
    void exposesARejectedContainerTransaction() {
        ContainerTransferController.beginTracking(7, (short) 13);

        ContainerTransferController.onConfirmation(7, (short) 13, false);

        assertEquals(ContainerTransferController.Status.REJECTED, ContainerTransferController.status());
    }

    @Test
    void waitsForTheMatchingFullInventoryResynchronization() {
        ContainerTransferController.beginTracking(7, (short) 13);
        ContainerTransferController.onConfirmation(7, (short) 13, false);

        ContainerTransferController.onWindowItems(8);
        assertEquals(ContainerTransferController.Status.REJECTED, ContainerTransferController.status());

        ContainerTransferController.onWindowItems(7);
        assertEquals(ContainerTransferController.Status.RESYNCHRONIZED, ContainerTransferController.status());
    }

    @Test
    void acceptedTransfersUseAFiveTickStabilityWindow() {
        assertFalse(ContainerTransferController.hasSettled(14, 10));
        assertTrue(ContainerTransferController.hasSettled(15, 10));
    }
}
