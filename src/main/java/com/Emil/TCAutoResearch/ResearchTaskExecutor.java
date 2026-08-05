package com.Emil.TCAutoResearch;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class ResearchTaskExecutor {

    private static final Object LOCK = new Object();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "TC Auto Research Worker");
        thread.setDaemon(true);
        return thread;
    });
    private static Future<?> activeTask;

    private ResearchTaskExecutor() {}

    public static void submitReplacing(Runnable task) {
        synchronized (LOCK) {
            if (activeTask != null) activeTask.cancel(true);
            activeTask = WORKER.submit(task);
        }
    }

    public static void cancel() {
        synchronized (LOCK) {
            if (activeTask != null) activeTask.cancel(true);
            activeTask = null;
        }
    }
}
