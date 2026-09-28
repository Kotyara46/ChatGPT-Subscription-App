package com.kotyara.chatgptpluscontrol;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.documentfile.provider.DocumentFile;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_TREE = 1301;
    private static final int REQ_WRITE = 1302;
    private static final long MAX_COPIED_BYTES = 12L * 1024L * 1024L * 1024L;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private TextView status;
    private TextView detail;
    private ProgressBar progress;
    private Button importButton;
    private Button permissionButton;
    private Button launchButton;
    private boolean pendingTreePick = false;

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
        if (pendingTreePick && hasFileAccess()) {
            pendingTreePick = false;
            pickGameFolder();
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

        root.addView(text("CS 1.6 — импорт с Google Drive", 27, Color.WHITE, true), lp(0, 6));
        root.addView(text("Выбери папку, где лежат valve и cstrike — остальное приложение сделает само", 15,
                Color.rgb(169, 178, 192), false), lp(0, 22));

        LinearLayout card = card();
        root.addView(card, lp(0, 16));
        card.addView(text("Состояние", 16, Color.WHITE, true), lp(0, 10));

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

        importButton = button("2. Выбрать папку CS 1.6 и установить");
        importButton.setOnClickListener(v -> {
            if (hasFileAccess()) pickGameFolder();
            else requestFileAccess(true);
        });
        root.addView(importButton, lp(0, 10));

        Button checkButton = secondaryButton("Проверить установленные файлы");
        checkButton.setOnClickListener(v -> refreshStatus());
        root.addView(checkButton, lp(0, 10));

        launchButton = button("Запустить CS16Client");
        launchButton.setOnClickListener(v -> launchCs16());
        root.addView(launchButton, lp(0, 22));

        LinearLayout info = card();
        root.addView(info, lp(0, 0));
        info.addView(text("Как выбрать твою папку из Drive", 16, Color.WHITE, true), lp(0, 8));
        TextView help = text(
                "1) Нажми «Выбрать папку CS 1.6».\n" +
                "2) В системном выборе файлов открой Google Drive.\n" +
                "3) Выбери корневую папку из твоей ссылки — ту, где рядом лежат папки valve и cstrike.\n\n" +
                "Приложение скопирует только valve и cstrike в:\n" +
                "Внутренняя память/xash/valve\n" +
                "Внутренняя память/xash/cstrike\n\n" +
                "Windows-файлы рядом с ними (hl.exe, dll из корня и т.п.) не нужны и не копируются.",
                14, Color.rgb(180, 190, 203), false);
        help.setLineSpacing(0, 1.15f);
        info.addView(help, lp(0, 0));

        setContentView(scroll);
    }

    private void requestFileAccess(boolean thenPickTree) {
        pendingTreePick = thenPickTree;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                if (thenPickTree) pickGameFolder();
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
                if (thenPickTree) pickGameFolder();
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
            if (ok && pendingTreePick) {
                pendingTreePick = false;
                pickGameFolder();
            }
        }
    }

    private boolean hasFileAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void pickGameFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION |
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, REQ_TREE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_TREE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri treeUri = data.getData();
            try {
                int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                getContentResolver().takePersistableUriPermission(treeUri, flags);
            } catch (Exception ignored) {
            }
            installFromFolder(treeUri);
        }
    }

    private void installFromFolder(Uri treeUri) {
        setBusy(true, "Ищу valve и cstrike…");
        executor.execute(() -> {
            try {
                DocumentFile selected = DocumentFile.fromTreeUri(this, treeUri);
                if (selected == null || !selected.isDirectory()) {
                    throw new Exception("Не удалось открыть выбранную папку");
                }

                DocumentFile valve = findDirectChildDir(selected, "valve");
                DocumentFile cstrike = findDirectChildDir(selected, "cstrike");

                if (valve == null || cstrike == null) {
                    throw new Exception("В выбранной папке должны лежать две папки рядом: valve и cstrike");
                }

                File xashRoot = getXashRoot();
                if (!xashRoot.exists() && !xashRoot.mkdirs()) {
                    throw new Exception("Не удалось создать " + xashRoot.getAbsolutePath());
                }

                CopyStats stats = new CopyStats();
                copyDirectory(valve, new File(xashRoot, "valve"), xashRoot, stats);
                copyDirectory(cstrike, new File(xashRoot, "cstrike"), xashRoot, stats);

                int copiedFiles = stats.files;
                long copiedMb = stats.bytes / (1024L * 1024L);
                runOnUiThread(() -> {
                    setBusy(false, null);
                    refreshStatus();
                    Toast.makeText(this,
                            "Готово: " + copiedFiles + " файлов, примерно " + copiedMb + " МБ",
                            Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setBusy(false, null);
                    status.setText("Ошибка импорта");
                    status.setTextColor(Color.rgb(255, 105, 105));
                    detail.setText(e.getMessage() == null ? e.toString() : e.getMessage());
                });
            }
        });
    }

    private DocumentFile findDirectChildDir(DocumentFile parent, String wanted) {
        for (DocumentFile child : parent.listFiles()) {
            String name = child.getName();
            if (child.isDirectory() && name != null && wanted.equalsIgnoreCase(name.trim())) {
                return child;
            }
        }
        return null;
    }

    private void copyDirectory(DocumentFile source, File destination, File xashRoot, CopyStats stats) throws Exception {
        if (!destination.exists() && !destination.mkdirs()) {
            throw new Exception("Не удалось создать папку " + destination.getAbsolutePath());
        }

        String allowedRoot = xashRoot.getCanonicalPath() + File.separator;
        for (DocumentFile child : source.listFiles()) {
            String name = child.getName();
            if (name == null || name.isEmpty()) continue;
            if (name.contains("/") || name.contains("\\") || name.equals(".") || name.equals("..")) {
                throw new Exception("Некорректное имя файла: " + name);
            }

            File out = new File(destination, name);
            String canonical = out.getCanonicalPath();
            if (!canonical.startsWith(allowedRoot)) {
                throw new Exception("Небезопасный путь: " + name);
            }

            if (child.isDirectory()) {
                copyDirectory(child, out, xashRoot, stats);
            } else if (child.isFile()) {
                copyOneFile(child, out, stats);
            }
        }
    }

    private void copyOneFile(DocumentFile source, File out, CopyStats stats) throws Exception {
        File parent = out.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new Exception("Не удалось создать " + parent.getAbsolutePath());
        }

        try (InputStream raw = getContentResolver().openInputStream(source.getUri())) {
            if (raw == null) throw new Exception("Не удалось открыть " + source.getName());
            try (BufferedInputStream in = new BufferedInputStream(raw, 128 * 1024);
                 BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(out, false), 128 * 1024)) {
                byte[] buffer = new byte[128 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    stats.bytes += read;
                    if (stats.bytes > MAX_COPIED_BYTES) throw new Exception("Слишком большой объём данных");
                    bos.write(buffer, 0, read);
                }
            }
        }

        stats.files++;
        if (stats.files % 40 == 0) {
            int count = stats.files;
            long mb = stats.bytes / (1024L * 1024L);
            runOnUiThread(() -> detail.setText(
                    "Копирую файлы из Google Drive…\n" +
                    "Скопировано: " + count + " файлов • " + mb + " МБ\n" +
                    "Не закрывай приложение до завершения."));
        }
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
        Toast.makeText(this, "CS16Client не найден. Сначала установи APK клиента.", Toast.LENGTH_LONG).show();
    }

    private void setBusy(boolean busy, String message) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        importButton.setEnabled(!busy);
        permissionButton.setEnabled(!busy);
        launchButton.setEnabled(!busy && isAnyPackageInstalled("su.xash.cs16client.test", "su.xash.cs16client"));
        if (busy && message != null) {
            status.setText(message);
            status.setTextColor(Color.WHITE);
        }
    }

    private TextView text(String value, int sizeSp, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextSize(sizeSp);
        tv.setTextColor(color);
        if (bold) tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        return tv;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(15);
        b.setTextColor(Color.WHITE);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(47, 113, 230));
        bg.setCornerRadius(dp(12));
        b.setBackground(bg);
        b.setAllCaps(false);
        b.setPadding(dp(12), dp(12), dp(12), dp(12));
        return b;
    }

    private Button secondaryButton(String label) {
        Button b = button(label);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(34, 40, 50));
        bg.setStroke(dp(1), Color.rgb(70, 78, 92));
        bg.setCornerRadius(dp(12));
        b.setBackground(bg);
        return b;
    }

    private LinearLayout card() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(24, 29, 37));
        bg.setCornerRadius(dp(14));
        box.setBackground(bg);
        return box;
    }

    private LinearLayout.LayoutParams lp(int top, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(top);
        p.bottomMargin = dp(bottom);
        return p;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class CopyStats {
        int files = 0;
        long bytes = 0;
    }
}
