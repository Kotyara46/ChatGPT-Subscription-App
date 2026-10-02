package com.kot46.cs16serversync;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

public class SyncWorker extends Worker {
    public SyncWorker(@NonNull Context appContext, @NonNull WorkerParameters params) {
        super(appContext, params);
    }

    @NonNull @Override public Result doWork() {
        try {
            SyncEngine.sync(getApplicationContext(), false);
            return Result.success();
        } catch (IllegalStateException e) {
            return Result.success();
        } catch (Exception e) {
            getApplicationContext().getSharedPreferences(SyncEngine.PREFS, Context.MODE_PRIVATE)
                    .edit().putString(SyncEngine.KEY_STATUS, "Ошибка автообновления: " + e.getMessage()).apply();
            return Result.retry();
        }
    }
}
