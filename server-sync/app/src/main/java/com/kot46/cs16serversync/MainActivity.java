package com.kot46.cs16serversync;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.*;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {
    static final int PICK_XASH = 47;
    EditText dummy;
    TextView status;
    SharedPreferences prefs;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences(SyncEngine.PREFS, MODE_PRIVATE);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(24));
        scroll.addView(root);

        TextView title = text("KoT46 — Автоизбранное CS 1.6", 23);
        title.setGravity(Gravity.CENTER);
        root.addView(title, full());

        TextView hint = text(
                "Это приложение для ИГРОКОВ. Один раз выбери папку /storage/emulated/0/xash. " +
                "После этого список серверов будет автоматически проверяться каждые 15 минут. " +
                "Когда администратор меняет центральный список, «Избранное» на телефоне обновляется автоматически.", 15);
        hint.setPadding(0, dp(12), 0, dp(12));
        root.addView(hint, full());

        Button choose = button("ВЫБРАТЬ ПАПКУ XASH");
        choose.setOnClickListener(v -> chooseXash());
        root.addView(choose, buttonParams());

        Button sync = button("СИНХРОНИЗИРОВАТЬ СЕЙЧАС");
        sync.setOnClickListener(v -> syncNow(true));
        root.addView(sync, buttonParams());

        Button open = button("ЗАПУСТИТЬ CS 1.6");
        open.setOnClickListener(v -> openGame());
        root.addView(open, buttonParams());

        status = text("", 14);
        status.setPadding(0, dp(12), 0, 0);
        root.addView(status, full());

        setContentView(scroll);
        refreshStatus();

        if (prefs.contains(SyncEngine.KEY_TREE)) {
            schedule();
            syncNow(false);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (status != null) refreshStatus();
    }

    TextView text(String s, int size) { TextView v = new TextView(this); v.setText(s); v.setTextSize(size); return v; }
    Button button(String s) { Button b = new Button(this); b.setText(s); return b; }
    LinearLayout.LayoutParams full() { return new LinearLayout.LayoutParams(-1, -2); }
    LinearLayout.LayoutParams buttonParams() { LinearLayout.LayoutParams p = full(); p.topMargin = dp(8); return p; }
    int dp(int x) { return Math.round(x * getResources().getDisplayMetrics().density); }

    void chooseXash() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(i, PICK_XASH);
    }

    @Override protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req != PICK_XASH || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception ignored) {}
        prefs.edit().putString(SyncEngine.KEY_TREE, uri.toString()).apply();
        schedule();
        status.setText("Папка xash сохранена. Выполняю первую синхронизацию...");
        syncNow(true);
    }

    void schedule() {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(
                SyncWorker.class, 15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build();

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "kot46-cs16-server-sync",
                ExistingPeriodicWorkPolicy.UPDATE,
                req);
    }

    void syncNow(boolean force) {
        if (!prefs.contains(SyncEngine.KEY_TREE)) {
            status.setText("Сначала выбери папку xash.");
            chooseXash();
            return;
        }
        status.setText("Проверяю центральный список...");
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                String msg = SyncEngine.sync(getApplicationContext(), force);
                runOnUiThread(() -> {
                    refreshStatus();
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("Ошибка синхронизации: " + e.getMessage()));
            }
        });
    }

    void refreshStatus() {
        if (!prefs.contains(SyncEngine.KEY_TREE)) {
            status.setText("Первый запуск: выбери папку /storage/emulated/0/xash.");
            return;
        }
        String s = prefs.getString(SyncEngine.KEY_STATUS, "Папка xash выбрана. Синхронизация ещё не выполнялась.");
        String t = prefs.getString(SyncEngine.KEY_TIME, "");
        status.setText(s + (t.isEmpty() ? "" : "\nПоследняя проверка: " + t) +
                "\nАвтопроверка: каждые 15 минут при наличии интернета.");
    }

    void openGame() {
        String[] packages = {
                "su.xash.engine.test",
                "su.xash.engine",
                "su.xash.cs16client.test",
                "su.xash.cs16client"
        };
        for (String pkg : packages) {
            Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null) {
                startActivity(i);
                return;
            }
        }
        Toast.makeText(this, "Не нашёл Xash3D/CS16. Запусти игру вручную.", Toast.LENGTH_LONG).show();
    }
}
