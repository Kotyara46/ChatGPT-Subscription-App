package com.kotyara.chatgptpluscontrol;

import android.app.job.JobParameters;
import android.app.job.JobService;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ServerSyncJob extends JobService {
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    @Override
    public boolean onStartJob(JobParameters params) {
        io.execute(() -> {
            try {
                ServerSync.syncNow(getApplicationContext());
            } catch (Exception ignored) {
            } finally {
                jobFinished(params, false);
            }
        });
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }

    @Override
    public void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }
}
