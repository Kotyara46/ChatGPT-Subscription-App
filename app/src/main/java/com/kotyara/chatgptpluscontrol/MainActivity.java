package com.kotyara.chatgptpluscontrol;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MainActivity extends Activity {
    private static final int REQ_ZIP = 1201;
    private static final int REQ_WRITE = 1202;
    private static final long MAX_EXTRACTED_BYTES = 12L * 1024L * 1024L * 1024L;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private TextView status;
    private TextView detail;
    private ProgressBar progress;
    private Button installButton;
    private Button permissionButton;
    private Button launchButton;
    private boolean pendingZipPick = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        buildUi();
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        if (pendingZipPick && hasFileAccess()) {
            pendingZipPick = false;
            pickZip();
        }
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(12, 15, 20));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(28));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("CS 1.6 — установщик файлов", 27, Color.WHITE, true);
        root.addView(title, lp(0, 6));

        TextView subtitle = text("Сам положит valve и cstrike в правильную папку Xash3D", 15,
                Color.rgb(169, 178, 192), false);
        root.addView(subtitle, lp(0, 22));

        LinearLayout card = card();
        root.addView(card, lp(0, 16));

        TextView cardTitle = text("Состояние", 16, Color.WHITE, true);
        card.addView(cardTitle, lp(0, 10));

        status = text("Проверяю…", 19, Color.WHITE, true);
        card.addView(status, lp(0, 7));

        detail = text("", 14, Color.rgb(179, 188, 201), false);
        detail.setLineSpacing(0, 1.15f);
        card.addView(detail, lp(0, 8));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        card.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(5)));

        permissionButton = button("1. Разрешить доступ к памяти");
        permissionButton.setOnClickListener(v -> requestFileAccess(false));
        root.addView(permissionButton, lp(0, 10));

        installButton = button("2. Выбрать ZIP и установить CS 1.6");
        installButton.setOnClickListener(v -> {
            if (hasFileAccess()) pickZip();
            else requestFileAccess(true);
        });
        root.addView(installButton, lp(0, 10));

        Button checkButton = secondaryButton("Проверить установленные файлы");
        checkButton.setOnClickListener(v -> refreshStatus());
        root.addView(checkButton, lp(0, 10));

        launchButton = button("Запустить CS16Client");
        launchButton.setOnClickListener(v -> launchCs16());
        root.addView(launchButton, lp(0, 22));

        LinearLayout info = card();
        root.addView(info, lp(0, 0));
        info.addView(text("Что должен содержать ZIP", 16, Color.WHITE, true), lp(0, 8));
        TextView help = text(
                "Архив должен содержать папки valve и cstrike из твоей установленной CS 1.6. " +
                "Можно архивировать всю папку Half-Life — установщик сам найдёт внутри valve/cstrike.\n\n" +
                "Куда установит:\nВнутренняя память/xash/valve\nВнутренняя память/xash/cstrike\n\n" +
                "Карты, модели, звуки и остальные файлы игры в этот APK не встроены — он переносит твою собственную копию.",
                14, Color.rgb(180, 190, 203), false);
        help.setLineSpacing(0, 1.15f);
        info.addView(help, lp(0, 0));

        setContentView(scroll);
    }

    private void requestFileAccess(boolean thenPickZip) {
        pendingZipPick = thenPickZip;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                if (thenPickZip) pickZip();
                return;
            }
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                if (thenPickZip) pickZip();
            } else {
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_WRITE);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_WRITE) {
            boolean ok = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            refreshStatus();
            if (ok && pendingZipPick) {
                pendingZipPick = false;
                pickZip();
            }
        }
    }

    private boolean hasFileAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void pickZip() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/zip", "application/x-zip-compressed", "application/octet-stream"
        });
        startActivityForResult(intent, REQ_ZIP);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ZIP && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
            }
            installFromZip(uri);
        }
    }

    private void installFromZip(Uri uri) {
        setBusy(true, "Распаковываю valve и cstrike…");
        executor.execute(() -> {
            int files = 0;
            long bytes = 0;
            boolean foundValve = false;
            boolean foundCstrike = false;
            try {
                File root = getXashRoot();
                if (!root.exists() && !root.mkdirs()) {
                    throw new Exception("Не удалось создать " + root.getAbsolutePath());
                }
                String rootCanonical = root.getCanonicalPath() + File.separator;

                try (InputStream raw = getContentResolver().openInputStream(uri);
                     ZipInputStream zip = new ZipInputStream(new BufferedInputStream(raw, 128 * 1024))) {
                    if (raw == null) throw new Exception("Не удалось открыть ZIP");
                    ZipEntry entry;
                    byte[] buffer = new byte[128 * 1024];
                    while ((entry = zip.getNextEntry()) != null) {
                        String relative = relevantPath(entry.getName());
                        if (relative == null || relative.isEmpty()) {
                            zip.closeEntry();
                            continue;
                        }
                        String low = relative.toLowerCase(Locale.ROOT);
                        if (low.equals("valve") || low.startsWith("valve/")) foundValve = true;
                        if (low.equals("cstrike") || low.startsWith("cstrike/")) foundCstrike = true;

                        File out = new File(root, relative);
                        String outCanonical = out.getCanonicalPath();
                        if (!outCanonical.equals(root.getCanonicalPath()) && !outCanonical.startsWith(rootCanonical)) {
                            throw new Exception("Небезопасный путь в ZIP: " + entry.getName());
                        }

                        if (entry.isDirectory()) {
                            if (!out.exists() && !out.mkdirs()) throw new Exception("Не удалось создать " + out.getName());
                        } else {
                            File parent = out.getParentFile();
                            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                                throw new Exception("Не удалось создать папку " + parent.getName());
                            }
                            try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(out), 128 * 1024)) {
                                int read;
                                while ((read = zip.read(buffer)) != -1) {
                                    bytes += read;
                                    if (bytes > MAX_EXTRACTED_BYTES) {
                                        throw new Exception("Архив слишком большой");
                                    }
                                    bos.write(buffer, 0, read);
                                }
                            }
                            files++;
                            if (files % 75 == 0) {
                                int shown = files;
                                runOnUiThread(() -> detail.setText("Установлено файлов: " + shown + "\nПапка: " + getXashRoot().getAbsolutePath()));
                            }
                        }
                        zip.closeEntry();
                    }
                }

                if (!foundValve || !foundCstrike) {
                    String missing = !foundValve && !foundCstrike ? "valve и cstrike" : (!foundValve ? "valve" : "cstrike");
                    throw new Exception("В ZIP не найдена папка " + missing + ". Нужен архив из установленной CS 1.6.");
                }

                int finalFiles = files;
                runOnUiThread(() -> {
                    setBusy(false, null);
                    refreshStatus();
                    Toast.makeText(this, "Готово. Установлено файлов: " + finalFiles, Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setBusy(false, null);
                    status.setText("Ошибка установки");
                    status.setTextColor(Color.rgb(255, 105, 105));
                    detail.setText(e.getMessage() == null ? e.toString() : e.getMessage());
                });
            }
        });
    }

    private String relevantPath(String input) {
        if (input == null) return null;
        String n = input.replace('\\', '/');
        while (n.startsWith("/")) n = n.substring(1);
        if (n.contains("../") || n.equals("..")) return null;
        String lower = n.toLowerCase(Locale.ROOT);

        String[] roots = {"cstrike", "valve"};
        for (String root : roots) {
            if (lower.equals(root)) return root;
            if (lower.startsWith(root + "/")) return n;
            String marker = "/" + root + "/";
            int idx = lower.indexOf(marker);
            if (idx >= 0) return n.substring(idx + 1);
            if (lower.endsWith("/" + root)) return root;
        }
        return null;
    }

    private void refreshStatus() {
        boolean access = hasFileAccess();
        permissionButton.setText(access ? "✓ Доступ к памяти разрешён" : "1. Разрешить доступ к памяти");

        File root = getXashRoot();
        File valve = new File(root, "valve");
        File cstrike = new File(root, "cstrike");
        boolean hasValve = valve.isDirectory();
        boolean hasCstrike = cstrike.isDirectory();
        boolean hasMaps = new File(cstrike, "maps").isDirectory();
        boolean hasModels = new File(cstrike, "models").isDirectory();
        boolean hasGameMarker = new File(cstrike, "liblist.gam").isFile() || new File(cstrike, "gameinfo.txt").isFile();
        boolean clientInstalled = isAnyPackageInstalled("su.xash.cs16client.test", "su.xash.cs16client");
        boolean engineInstalled = isAnyPackageInstalled("su.xash.engine.test", "su.xash.engine");

        if (hasValve && hasCstrike && (hasMaps || hasModels || hasGameMarker)) {
            status.setText("✓ Файлы CS 1.6 найдены");
            status.setTextColor(Color.rgb(91, 214, 144));
        } else if (hasValve || hasCstrike) {
            status.setText("Файлы установлены не полностью");
            status.setTextColor(Color.rgb(255, 194, 92));
        } else {
            status.setText("Нужно импортировать игру");
            status.setTextColor(Color.rgb(255, 194, 92));
        }

        detail.setText(
                "Память: " + (access ? "доступ есть" : "нужно разрешение") +
                "\nvalve: " + (hasValve ? "есть" : "нет") +
                "   •   cstrike: " + (hasCstrike ? "есть" : "нет") +
                "\nXash3D: " + (engineInstalled ? "установлен" : "не найден") +
                "   •   CS16Client: " + (clientInstalled ? "установлен" : "не найден") +
                "\nПуть: " + root.getAbsolutePath());

        launchButton.setEnabled(clientInstalled);
    }

    private File getXashRoot() {
        return new File(Environment.getExternalStorageDirectory(), "xash");
    }

    private boolean isAnyPackageInstalled(String... packages) {
        for (String pkg : packages) {
            try {
                getPackageManager().getPackageInfo(pkg, 0);
                return true;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private void launchCs16() {
        String[] packages = {"su.xash.cs16client.test", "su.xash.cs16client"};
        for (String pkg : packages) {
            Intent intent = getPackageManager().getLaunchIntentForPackage(pkg);
            if (intent != null) {
                startActivity(intent);
                return;
            }
        }
        Toast.makeText(this, "CS16Client не найден. Установи APK клиента.", Toast.LENGTH_LONG).show();
    }

    private void setBusy(boolean busy, String message) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        installButton.setEnabled(!busy);
        permissionButton.setEnabled(!busy);
        launchButton.setEnabled(!busy && isAnyPackageInstalled("su.xash.cs16client.test", "su.xash.cs16client"));
        if (busy && message != null) {
            status.setText(message);
            status.setTextColor(Color.WHITE);
        }
    }

    private LinearLayout card() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(16), dp(18), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(25, 30, 39));
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), Color.rgb(48, 57, 70));
        box.setBackground(bg);
        return box;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private Button button(String value) {
        Button b = new Button(this);
        b.setText(value);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(12), 0, dp(12), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(39, 116, 240));
        bg.setCornerRadius(dp(14));
        b.setBackground(bg);
        b.setMinHeight(dp(54));
        return b;
    }

    private Button secondaryButton(String value) {
        Button b = button(value);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(30, 36, 46));
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), Color.rgb(63, 74, 91));
        b.setBackground(bg);
        return b;
    }

    private LinearLayout.LayoutParams lp(int top, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(top);
        p.bottomMargin = dp(bottom);
        return p;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
