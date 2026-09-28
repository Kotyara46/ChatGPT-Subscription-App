package com.kotyara.chatgptpluscontrol;

import android.Manifest;
import android.app.AlarmManager;
import android.app.DatePickerDialog;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.ColorInt;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS = "plus_control";
    private static final String KEY_STATUS = "status";
    private static final String KEY_DEADLINE = "deadline";
    private static final String KEY_LAST_CHECK = "last_check";
    private static final String KEY_REMINDERS = "reminders";

    private static final int NOTIFICATION_PERMISSION_REQUEST = 42;

    private SharedPreferences prefs;
    private TextView statusBadge;
    private TextView deadlineText;
    private TextView countdownText;
    private TextView lastCheckText;
    private MaterialSwitch reminderSwitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        statusBadge = findViewById(R.id.statusBadge);
        deadlineText = findViewById(R.id.deadlineText);
        countdownText = findViewById(R.id.countdownText);
        lastCheckText = findViewById(R.id.lastCheckText);
        reminderSwitch = findViewById(R.id.reminderSwitch);

        findViewById(R.id.btnCheck).setOnClickListener(v -> checkSubscription());
        findViewById(R.id.btnOpenChatGPT).setOnClickListener(v -> openChatGPT());
        findViewById(R.id.btnBilling).setOnClickListener(v -> openBilling());
        findViewById(R.id.btnBuyPlus).setOnClickListener(v -> openUrl("https://chatgpt.com/ru-RU/plans/plus/"));
        findViewById(R.id.btnGooglePlay).setOnClickListener(v -> openGooglePlaySubscriptions());
        findViewById(R.id.btnChangeStatus).setOnClickListener(v -> showStatusDialog());
        findViewById(R.id.btnSetDeadline).setOnClickListener(v -> showDatePicker());
        findViewById(R.id.btnClearDeadline).setOnClickListener(v -> clearDeadline());
        findViewById(R.id.btnCopyDiagnostics).setOnClickListener(v -> copyDiagnostics());
        findViewById(R.id.btnBillingHelp).setOnClickListener(v -> openUrl("https://help.openai.com/en/articles/9039756-managing-billing-for-chatgpt-and-the-api-platform"));
        findViewById(R.id.btnSupport).setOnClickListener(v -> openUrl("https://help.openai.com/"));

        reminderSwitch.setChecked(prefs.getBoolean(KEY_REMINDERS, true));
        reminderSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(KEY_REMINDERS, isChecked).apply();
            if (isChecked) {
                requestNotificationPermissionIfNeeded();
                scheduleReminders();
            } else {
                cancelReminders();
            }
        });

        requestNotificationPermissionIfNeeded();
        refreshUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUi();
    }

    private void checkSubscription() {
        prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply();
        refreshUi();
        Toast.makeText(this, "В ChatGPT откройте Настройки → Billing и после проверки отметьте статус здесь.", Toast.LENGTH_LONG).show();
        openUrl("https://chatgpt.com/");
    }

    private void openBilling() {
        Toast.makeText(this, "Откройте Настройки → Billing. Там доступны способ оплаты, счета и управление Plus.", Toast.LENGTH_LONG).show();
        openUrl("https://chatgpt.com/");
    }

    private void openChatGPT() {
        Intent launch = getPackageManager().getLaunchIntentForPackage("com.openai.chatgpt");
        if (launch != null) {
            startActivity(launch);
        } else {
            openUrl("https://chatgpt.com/");
        }
    }

    private void openGooglePlaySubscriptions() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/account/subscriptions"));
            intent.setPackage("com.android.vending");
            startActivity(intent);
        } catch (Exception ignored) {
            openUrl("https://play.google.com/store/account/subscriptions");
        }
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show();
        }
    }

    private void showStatusDialog() {
        String[] labels = {"Plus активен", "Проблема с оплатой", "Отменена / заканчивается", "Не проверено"};
        String[] values = {"active", "payment_issue", "cancelled", "unknown"};
        String current = prefs.getString(KEY_STATUS, "unknown");
        int checked = 3;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(current)) {
                checked = i;
                break;
            }
        }
        final int initialChecked = checked;
        new MaterialAlertDialogBuilder(this)
                .setTitle("Статус ChatGPT Plus")
                .setSingleChoiceItems(labels, initialChecked, (dialog, which) -> {
                    prefs.edit().putString(KEY_STATUS, values[which]).apply();
                    dialog.dismiss();
                    refreshUi();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void showDatePicker() {
        LocalDate initial = readDeadline();
        if (initial == null || initial.isBefore(LocalDate.now())) {
            initial = LocalDate.now().plusDays(1);
        }
        DatePickerDialog dialog = new DatePickerDialog(
                this,
                (view, year, month, dayOfMonth) -> {
                    LocalDate chosen = LocalDate.of(year, month + 1, dayOfMonth);
                    prefs.edit().putString(KEY_DEADLINE, chosen.toString()).apply();
                    refreshUi();
                    if (prefs.getBoolean(KEY_REMINDERS, true)) {
                        scheduleReminders();
                    }
                },
                initial.getYear(), initial.getMonthValue() - 1, initial.getDayOfMonth()
        );
        Calendar min = Calendar.getInstance();
        min.add(Calendar.DAY_OF_MONTH, -1);
        dialog.getDatePicker().setMinDate(min.getTimeInMillis());
        dialog.show();
    }

    private void clearDeadline() {
        prefs.edit().remove(KEY_DEADLINE).apply();
        cancelReminders();
        refreshUi();
    }

    private void refreshUi() {
        String status = prefs.getString(KEY_STATUS, "unknown");
        switch (status) {
            case "active":
                setBadge("Plus активен", ContextCompat.getColor(this, R.color.success));
                break;
            case "payment_issue":
                setBadge("Проблема с оплатой", ContextCompat.getColor(this, R.color.danger));
                break;
            case "cancelled":
                setBadge("Отменена / заканчивается", ContextCompat.getColor(this, R.color.warning));
                break;
            default:
                setBadge("Не проверено", ContextCompat.getColor(this, R.color.muted));
                break;
        }

        LocalDate deadline = readDeadline();
        if (deadline == null) {
            deadlineText.setText("Крайний срок не указан");
            countdownText.setText("Укажите дату, чтобы получать напоминания до отключения или списания.");
        } else {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("d MMMM yyyy", new Locale("ru"));
            deadlineText.setText("Срок: " + deadline.format(formatter));
            long days = Duration.between(LocalDate.now().atStartOfDay(), deadline.atStartOfDay()).toDays();
            if (days > 0) {
                countdownText.setText("Осталось дней: " + days);
            } else if (days == 0) {
                countdownText.setText("Срок сегодня");
            } else {
                countdownText.setText("Срок прошёл " + Math.abs(days) + " дн. назад");
            }
        }

        long lastCheck = prefs.getLong(KEY_LAST_CHECK, 0L);
        if (lastCheck == 0L) {
            lastCheckText.setText("Проверка ещё не выполнялась");
        } else {
            SimpleDateFormat formatter = new SimpleDateFormat("d MMMM yyyy, HH:mm", new Locale("ru"));
            lastCheckText.setText("Последняя проверка: " + formatter.format(new Date(lastCheck)));
        }
    }

    private void setBadge(String text, @ColorInt int color) {
        statusBadge.setText(text);
        GradientDrawable background = new GradientDrawable();
        background.setColor(withAlpha(color, 45));
        background.setCornerRadius(dp(18));
        background.setStroke((int) dp(1), color);
        statusBadge.setBackground(background);
        statusBadge.setTextColor(Color.WHITE);
    }

    private int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private float dp(int value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private LocalDate readDeadline() {
        String value = prefs.getString(KEY_DEADLINE, "");
        if (value == null || value.isEmpty()) return null;
        try {
            return LocalDate.parse(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    NOTIFICATION_PERMISSION_REQUEST);
        }
    }

    private void scheduleReminders() {
        cancelReminders();
        LocalDate deadline = readDeadline();
        if (deadline == null) return;

        int[] daysBefore = {3, 1, 0};
        for (int days : daysBefore) {
            LocalDate notifyDate = deadline.minusDays(days);
            if (notifyDate.isBefore(LocalDate.now())) continue;

            Calendar calendar = Calendar.getInstance();
            calendar.setTime(Date.from(notifyDate.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant()));
            if (calendar.getTimeInMillis() <= System.currentTimeMillis()) {
                calendar.add(Calendar.MINUTE, 2);
            }

            String message;
            if (days == 0) message = "Сегодня указан срок оплаты/окончания ChatGPT Plus.";
            else if (days == 1) message = "До указанного срока ChatGPT Plus остался 1 день.";
            else message = "До указанного срока ChatGPT Plus осталось 3 дня.";

            Intent intent = new Intent(this, ReminderReceiver.class);
            intent.putExtra("message", message);
            PendingIntent pending = PendingIntent.getBroadcast(
                    this,
                    700 + days,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            AlarmManager alarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.getTimeInMillis(), pending);
        }
    }

    private void cancelReminders() {
        AlarmManager alarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);
        int[] daysBefore = {3, 1, 0};
        for (int days : daysBefore) {
            PendingIntent pending = PendingIntent.getBroadcast(
                    this,
                    700 + days,
                    new Intent(this, ReminderReceiver.class),
                    PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE
            );
            if (pending != null) {
                alarmManager.cancel(pending);
                pending.cancel();
            }
        }
    }

    private void copyDiagnostics() {
        String status = prefs.getString(KEY_STATUS, "unknown");
        LocalDate deadline = readDeadline();
        long lastCheck = prefs.getLong(KEY_LAST_CHECK, 0L);
        String text = "ChatGPT Plus Контроль\n" +
                "Статус: " + status + "\n" +
                "Срок: " + (deadline == null ? "не указан" : deadline) + "\n" +
                "Последняя проверка: " + (lastCheck == 0L ? "нет" : new Date(lastCheck)) + "\n" +
                "Устройство: " + Build.MANUFACTURER + " " + Build.MODEL + "\n" +
                "Android: " + Build.VERSION.RELEASE;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("ChatGPT Plus диагностика", text));
        Toast.makeText(this, "Диагностика скопирована", Toast.LENGTH_SHORT).show();
    }
}
