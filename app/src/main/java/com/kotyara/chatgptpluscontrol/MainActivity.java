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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_TREE = 1501;
    private static final int REQ_WRITE = 1502;
    private static final long MAX_COPIED_BYTES = 12L * 1024L * 1024L * 1024L;
    private static final String DRIVE_URL =
            "https://drive.google.com/drive/folders/1mO-ZYvd1H7a-yktf78fLKfZj1q0tqsN9?usp=sharing";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private TextView status;
    private TextView detail;
    private ProgressBar progress;
    private Button permissionButton;
    private Button importButton;
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

        root.addView(text("CS 1.6 Android — Drive Installer", 27, Color.WHITE, true), lp(0, 6));
        root.addView(text(
                "Импортирует valve + cstrike прямо через системный доступ к Google Drive. Без парсинга веб-страниц и без ZIP.",
                15, Color.rgb(169, 178, 192), false), lp(0, 22));

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
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6)));

        permissionButton = button("1. Разрешить доступ к памяти");
        permissionButton.setOnClickListener(v -> requestFileAccess(false));
        root.addView(permissionButton, lp(0, 10));

        Button openDriveButton = secondaryButton("2. Открыть исходную папку в Google Drive");
        openDriveButton.setOnClickListener(v -> openDriveFolder());
        root.addView(openDriveButton, lp(0, 10));

        importButton = button("3. Выбрать Half-Life в Google Drive и установить");
        importButton.setOnClickListener(v -> {
            if (hasFileAccess()) pickGameFolder();
            else requestFileAccess(true);
        });
        root.addView(importButton, lp(0, 10));

        Button checkButton = secondaryButton("Проверить установку");
        checkButton.setOnClickListener(v -> refreshStatus());
        root.addView(checkButton, lp(0, 10));

        launchButton = button("Запустить CS16Client");
        launchButton.setOnClickListener(v -> launchCs16());
        root.addView(launchButton, lp(0, 22));

        LinearLayout info = card();
        root.addView(info, lp(0, 0));
        info.addView(text("Как это работает", 16, Color.WHITE, true), lp(0, 8));
        TextView help = text(
                "Источник игры: твоя папка Google Drive «Half-Life».\n\n" +
                "Нажми кнопку 3. В системном окне выбери Google Drive → папку Half-Life, где рядом лежат valve и cstrike. " +
                "Это официальный системный доступ Android к Drive, поэтому он видит все файлы и подпапки, а не первые 50 ссылок веб-страницы.\n\n" +
                "Перед копированием старые неполные valve/cstrike удаляются. Новые файлы ставятся в:\n" +
                "/storage/emulated/0/xash/valve\n" +
                "/storage/emulated/0/xash/cstrike",
                14, Color.rgb(180, 190, 203), false);
        help.setLineSpacing(0, 1.15f);
        info.addView(help, lp(0, 0));

        setContentView(scroll);
    }

    private void openDriveFolder() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(DRIVE_URL));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось открыть Google Drive", Toast.LENGTH_LONG).show();
        }
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
                int flags = data.getFlags() &
                        (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                getContentResolver().takePersistableUriPermission(treeUri, flags);
            } catch (Exception ignored) {
            }
            installFromFolder(treeUri);
        }
    }

    private void installFromFolder(Uri treeUri) {
        setBusy(true, "Открываю папку из Google Drive…");
        executor.execute(() -> {
            try {
                DocumentFile selected = DocumentFile.fromTreeUri(this, treeUri);
                if (selected == null || !selected.isDirectory()) {
                    throw new Exception("Не удалось открыть выбранную папку Google Drive");
                }

                GameRoot gameRoot = findGameRoot(selected, 0);
                if (gameRoot == null) {
                    throw new Exception(
                            "Не нашёл рядом папки valve и cstrike. Выбери папку Half-Life из твоего Google Drive.");
                }

                File xashRoot = getXashRoot();
                if (!xashRoot.exists() && !xashRoot.mkdirs()) {
                    throw new Exception("Не удалось создать " + xashRoot.getAbsolutePath());
                }

                runOnUiThread(() -> detail.setText(
                        "Источник найден: Google Drive / " + safeName(gameRoot.root.getName()) +
                        "\nУдаляю старую неполную установку…"));

                deleteRecursive(new File(xashRoot, "valve"));
                deleteRecursive(new File(xashRoot, "cstrike"));

                CopyStats stats = new CopyStats();
                copyDirectory(gameRoot.valve, new File(xashRoot, "valve"), xashRoot, stats);
                copyDirectory(gameRoot.cstrike, new File(xashRoot, "cstrike"), xashRoot, stats);

                verifyGameFiles();

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

    private GameRoot findGameRoot(DocumentFile dir, int depth) {
        if (dir == null || !dir.isDirectory() || depth > 2) return null;

        DocumentFile valve = findDirectChildDir(dir, "valve");
        DocumentFile cstrike = findDirectChildDir(dir, "cstrike");
        if (valve != null && cstrike != null) {
            return new GameRoot(dir, valve, cstrike);
        }

        if (depth == 2) return null;
        for (DocumentFile child : dir.listFiles()) {
            if (child.isDirectory()) {
                GameRoot nested = findGameRoot(child, depth + 1);
                if (nested != null) return nested;
            }
        }
        return null;
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

    private void copyDirectory(DocumentFile source, File destination, File xashRoot, CopyStats stats)
            throws Exception {
        if (!destination.exists() && !destination.mkdirs()) {
            throw new Exception("Не удалось создать папку " + destination.getAbsolutePath());
        }

        String allowedRoot = xashRoot.getCanonicalPath() + File.separator;
        DocumentFile[] children = source.listFiles();
        for (DocumentFile child : children) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Копирование прервано");
            }

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
                 BufferedOutputStream bos = new BufferedOutputStream(
                         new FileOutputStream(out, false), 128 * 1024)) {
                byte[] buffer = new byte[128 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    stats.bytes += read;
                    if (stats.bytes > MAX_COPIED_BYTES) {
                        throw new Exception("Объём данных превысил безопасный лимит 12 ГБ");
                    }
                    bos.write(buffer, 0, read);
                }
            }
        }

        stats.files++;
        if (stats.files % 25 == 0) {
            int count = stats.files;
            long mb = stats.bytes / (1024L * 1024L);
            String current = source.getName();
            runOnUiThread(() -> detail.setText(
                    "Копирую из Google Drive…\n" +
                    "Файлов: " + count + " • " + mb + " МБ\n" +
                    "Сейчас: " + current + "\n" +
                    "Не закрывай приложение до завершения."));
        }
    }

    private void deleteRecursive(File file) throws Exception {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursive(child);
            }
        }
        if (!file.delete() && file.exists()) {
            throw new Exception("Не удалось удалить старый файл: " + file.getAbsolutePath());
        }
    }

    private void verifyGameFiles() throws Exception {
        File root = getXashRoot();
        File valve = new File(root, "valve");
        File cstrike = new File(root, "cstrike");

        if (!valve.isDirectory()) throw new Exception("После импорта отсутствует папка valve");
        if (!cstrike.isDirectory()) throw new Exception("После импорта отсутствует папка cstrike");
        if (!new File(cstrike, "liblist.gam").isFile()) {
            throw new Exception("После импорта отсутствует cstrike/liblist.gam");
        }
        if (!new File(cstrike, "maps").isDirectory()) {
            throw new Exception("После импорта отсутствует cstrike/maps");
        }
        if (!new File(cstrike, "models").isDirectory()) {
            throw new Exception("После импорта отсутствует cstrike/models");
        }
    }

    private void refreshStatus() {
        boolean access = hasFileAccess();
        if (permissionButton != null) {
            permissionButton.setText(access ? "✓ Доступ к памяти разрешён" : "1. Разрешить доступ к памяти");
        }

        File root = getXashRoot();
        File valve = new File(root, "valve");
        File cstrike = new File(root, "cstrike");

        boolean valveReady = valve.isDirectory() && hasAnyFile(valve);
        boolean cstrikeReady = cstrike.isDirectory()
                && new File(cstrike, "liblist.gam").isFile()
                && new File(cstrike, "maps").isDirectory()
                && new File(cstrike, "models").isDirectory();

        boolean clientInstalled = isAnyPackageInstalled("su.xash.cs16client.test", "su.xash.cs16client");
        boolean engineInstalled = isAnyPackageInstalled("su.xash.engine.test", "su.xash.engine");

        if (valveReady && cstrikeReady) {
            status.setText("✓ CS 1.6 готова к запуску");
            status.setTextColor(Color.rgb(91, 214, 144));
        } else {
            status.setText("Нужно импортировать игру из Google Drive");
            status.setTextColor(Color.rgb(255, 194, 92));
        }

        detail.setText(
                "Память: " + (access ? "доступ есть" : "нужно разрешение") +
                "\nvalve: " + (valveReady ? "готово" : "нет/неполная") +
                "   •   cstrike: " + (cstrikeReady ? "готово" : "нет/неполная") +
                "\nXash3D: " + (engineInstalled ? "установлен" : "не найден") +
                "   •   CS16Client: " + (clientInstalled ? "установлен" : "не найден") +
                "\nПуть: " + root.getAbsolutePath());

        if (launchButton != null) {
            launchButton.setEnabled(clientInstalled && cstrikeReady);
        }
    }

    private boolean hasAnyFile(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return false;
        for (File f : files) {
            if (f.isFile()) return true;
            if (f.isDirectory() && hasAnyFile(f)) return true;
        }
        return false;
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
        Toast.makeText(this, "CS16Client не установлен", Toast.LENGTH_LONG).show();
    }

    private void setBusy(boolean busy, String message) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        progress.setIndeterminate(true);
        importButton.setEnabled(!busy);
        permissionButton.setEnabled(!busy);
        launchButton.setEnabled(!busy && isAnyPackageInstalled("su.xash.cs16client.test", "su.xash.cs16client"));
        if (message != null) {
            status.setText("Импорт из Google Drive…");
            status.setTextColor(Color.WHITE);
            detail.setText(message);
        }
    }

    private String safeName(String name) {
        return name == null || name.trim().isEmpty() ? "выбранная папка" : name.trim();
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(v.getTypeface(), Typeface.BOLD);
        return v;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(15);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setMinHeight(dp(54));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(35, 106, 224));
        bg.setCornerRadius(dp(13));
        b.setBackground(bg);
        return b;
    }

    private Button secondaryButton(String label) {
        Button b = button(label);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(32, 38, 48));
        bg.setStroke(dp(1), Color.rgb(69, 79, 94));
        bg.setCornerRadius(dp(13));
        b.setBackground(bg);
        return b;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(22, 27, 35));
        bg.setStroke(dp(1), Color.rgb(45, 54, 67));
        bg.setCornerRadius(dp(16));
        c.setBackground(bg);
        return c;
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
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static final class CopyStats {
        long bytes = 0;
        int files = 0;
    }

    private static final class GameRoot {
        final DocumentFile root;
        final DocumentFile valve;
        final DocumentFile cstrike;

        GameRoot(DocumentFile root, DocumentFile valve, DocumentFile cstrike) {
            this.root = root;
            this.valve = valve;
            this.cstrike = cstrike;
        }
    }
}
