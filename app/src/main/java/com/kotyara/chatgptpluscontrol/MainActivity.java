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

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final int REQ_WRITE = 1401;

    // Папки valve и cstrike из переданной пользователем публичной Google Drive папки.
    private static final String VALVE_FOLDER_ID = "13ES7ete84kf1bqZEV36QUHUxWbpUPfyU";
    private static final String CSTRIKE_FOLDER_ID = "1q0sK9tdBaXM2XiqOD1t7jdec3OuuSzE2";

    private static final long MAX_TOTAL_BYTES = 12L * 1024L * 1024L * 1024L;
    private static final int MAX_DEPTH = 24;
    private static final int CONNECT_TIMEOUT_MS = 25_000;
    private static final int READ_TIMEOUT_MS = 60_000;
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36";

    private static final Pattern FILE_LINK = Pattern.compile(
            "https?://drive\\.google\\.com/file/d/([-\\w]{20,})/view",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FOLDER_LINK = Pattern.compile(
            "https?://drive\\.google\\.com/drive/folders/([-\\w]{20,})",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DOWNLOAD_URL_JSON = Pattern.compile(
            "\\\"downloadUrl\\\":\\\"([^\\\"]+)\\\"");

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);

    private TextView status;
    private TextView detail;
    private ProgressBar progress;
    private Button installButton;
    private Button permissionButton;
    private Button launchButton;
    private volatile boolean pendingInstall = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CookieHandler.setDefault(cookieManager);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        buildUi();
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        if (pendingInstall && hasFileAccess()) {
            pendingInstall = false;
            installAutomatically();
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

        root.addView(text("CS 1.6 Android — OneTap", 27, Color.WHITE, true), lp(0, 6));
        root.addView(text("Сам скачает valve + cstrike из твоей Drive-папки и установит их для Xash3D", 15,
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
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6)));

        permissionButton = button("1. Разрешить доступ к памяти");
        permissionButton.setOnClickListener(v -> requestFileAccess(false));
        root.addView(permissionButton, lp(0, 10));

        installButton = button("2. Скачать и установить CS 1.6");
        installButton.setOnClickListener(v -> {
            if (hasFileAccess()) installAutomatically();
            else requestFileAccess(true);
        });
        root.addView(installButton, lp(0, 10));

        Button checkButton = secondaryButton("Проверить установку");
        checkButton.setOnClickListener(v -> refreshStatus());
        root.addView(checkButton, lp(0, 10));

        launchButton = button("Запустить CS16Client");
        launchButton.setOnClickListener(v -> launchCs16());
        root.addView(launchButton, lp(0, 22));

        LinearLayout info = card();
        root.addView(info, lp(0, 0));
        info.addView(text("Что делает эта сборка", 16, Color.WHITE, true), lp(0, 8));
        TextView help = text(
                "Никакие ZIP и выбор папки больше не нужны. Приложение напрямую использует две папки valve/cstrike из той Google Drive-ссылки, которую ты дал.\n\n" +
                "Установка идёт в:\n" +
                "/storage/emulated/0/xash/valve\n" +
                "/storage/emulated/0/xash/cstrike\n\n" +
                "Движок Xash3D FWGS и CS16Client ставятся отдельными APK. После них этот установщик докачивает игровые данные и запускает клиент.",
                14, Color.rgb(180, 190, 203), false);
        help.setLineSpacing(0, 1.15f);
        info.addView(help, lp(0, 0));

        setContentView(scroll);
    }

    private void requestFileAccess(boolean thenInstall) {
        pendingInstall = thenInstall;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                if (thenInstall) installAutomatically();
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
                if (thenInstall) installAutomatically();
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
            if (ok && pendingInstall) {
                pendingInstall = false;
                installAutomatically();
            }
        }
    }

    private boolean hasFileAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void installAutomatically() {
        if (!hasFileAccess()) {
            requestFileAccess(true);
            return;
        }

        setBusy(true, "Читаю структуру твоей Google Drive папки…");
        executor.execute(() -> {
            try {
                File xashRoot = getXashRoot();
                if (!xashRoot.exists() && !xashRoot.mkdirs()) {
                    throw new Exception("Не удалось создать " + xashRoot.getAbsolutePath());
                }

                List<RemoteFile> files = new ArrayList<>();
                Set<String> visitedFolders = new HashSet<>();

                discoverFolder(VALVE_FOLDER_ID, "valve", files, visitedFolders, 0);
                discoverFolder(CSTRIKE_FOLDER_ID, "cstrike", files, visitedFolders, 0);

                if (files.isEmpty()) {
                    throw new Exception("Google Drive вернул пустую папку. Проверь, что ссылка всё ещё открыта по доступу 'всем по ссылке'.");
                }

                int total = files.size();
                runOnUiThread(() -> {
                    progress.setIndeterminate(false);
                    progress.setMax(total);
                    progress.setProgress(0);
                    status.setText("Скачиваю игру…");
                    detail.setText("Найдено файлов: " + total + "\nИсточник: твоя Google Drive папка");
                });

                long totalBytes = 0;
                int done = 0;
                for (RemoteFile remote : files) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException("Установка прервана");
                    }

                    File out = safeOutputFile(xashRoot, remote.relativePath);
                    long downloaded = downloadWithRetry(remote.id, out, totalBytes);
                    totalBytes += downloaded;
                    if (totalBytes > MAX_TOTAL_BYTES) {
                        throw new Exception("Объём данных превысил безопасный лимит 12 ГБ");
                    }

                    done++;
                    int shownDone = done;
                    long shownMb = totalBytes / (1024L * 1024L);
                    String shownPath = remote.relativePath;
                    runOnUiThread(() -> {
                        progress.setProgress(shownDone);
                        detail.setText(
                                "Файл " + shownDone + " из " + total + "\n" +
                                shownPath + "\n" +
                                "Скачано примерно: " + shownMb + " МБ");
                    });
                }

                verifyGameFiles();
                int finalDone = done;
                long finalMb = totalBytes / (1024L * 1024L);
                runOnUiThread(() -> {
                    setBusy(false, null);
                    refreshStatus();
                    Toast.makeText(this,
                            "Готово: " + finalDone + " файлов, примерно " + finalMb + " МБ",
                            Toast.LENGTH_LONG).show();
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

    private void discoverFolder(String folderId,
                                String relativeDir,
                                List<RemoteFile> files,
                                Set<String> visitedFolders,
                                int depth) throws Exception {
        if (depth > MAX_DEPTH) throw new Exception("Слишком глубокая структура папок Drive");
        if (!visitedFolders.add(folderId)) return;

        String listingUrl = "https://drive.google.com/embeddedfolderview?id=" + folderId;
        String html = fetchText(listingUrl, 6 * 1024 * 1024);
        Document doc = Jsoup.parse(html, listingUrl);

        Map<String, Child> children = new LinkedHashMap<>();
        for (Element a : doc.select("a[href]")) {
            String href = a.attr("href");
            String name = sanitizeName(a.text());
            if (name.isEmpty()) continue;

            Matcher fileMatcher = FILE_LINK.matcher(href);
            if (fileMatcher.find()) {
                String id = fileMatcher.group(1);
                children.putIfAbsent("f:" + id, new Child(id, name, false));
                continue;
            }

            Matcher folderMatcher = FOLDER_LINK.matcher(href);
            if (folderMatcher.find()) {
                String id = folderMatcher.group(1);
                if (!id.equals(folderId)) {
                    children.putIfAbsent("d:" + id, new Child(id, name, true));
                }
            }
        }

        if (children.isEmpty()) {
            String title = doc.title().toLowerCase(Locale.ROOT);
            if (title.contains("sign in") || html.toLowerCase(Locale.ROOT).contains("request access")) {
                throw new Exception("Нет публичного доступа к папке Google Drive: " + relativeDir);
            }
        }

        for (Child child : children.values()) {
            String relative = relativeDir + "/" + child.name;
            if (child.folder) {
                discoverFolder(child.id, relative, files, visitedFolders, depth + 1);
            } else {
                files.add(new RemoteFile(child.id, relative));
            }
        }
    }

    private String fetchText(String urlString, int maxBytes) throws Exception {
        HttpURLConnection conn = openConnection(urlString);
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 400) {
                throw new Exception("Drive вернул HTTP " + code);
            }
            try (InputStream raw = new BufferedInputStream(conn.getInputStream(), 64 * 1024);
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[64 * 1024];
                int read;
                int total = 0;
                while ((read = raw.read(buf)) != -1) {
                    total += read;
                    if (total > maxBytes) throw new Exception("Слишком большой ответ Google Drive");
                    out.write(buf, 0, read);
                }
                return out.toString("UTF-8");
            }
        } finally {
            conn.disconnect();
        }
    }

    private long downloadWithRetry(String fileId, File out, long alreadyDownloaded) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return downloadGoogleDriveFile(fileId, out, alreadyDownloaded);
            } catch (Exception e) {
                last = e;
                File part = new File(out.getAbsolutePath() + ".part");
                if (part.exists()) part.delete();
                if (attempt < 3) Thread.sleep(800L * attempt);
            }
        }
        throw new Exception("Не удалось скачать " + out.getName() + ": " +
                (last == null ? "неизвестная ошибка" : last.getMessage()), last);
    }

    private long downloadGoogleDriveFile(String fileId, File out, long alreadyDownloaded) throws Exception {
        File parent = out.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new Exception("Не удалось создать папку " + parent.getAbsolutePath());
        }

        String currentUrl = "https://drive.google.com/uc?export=download&id=" + fileId;
        HttpURLConnection conn = null;

        for (int hop = 0; hop < 6; hop++) {
            if (conn != null) conn.disconnect();
            conn = openConnection(currentUrl);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 400) {
                throw new Exception("HTTP " + code + " для файла " + out.getName());
            }

            String disposition = conn.getHeaderField("Content-Disposition");
            String contentType = conn.getContentType();
            boolean attachment = disposition != null && disposition.toLowerCase(Locale.ROOT).contains("attachment");
            boolean html = !attachment && contentType != null &&
                    contentType.toLowerCase(Locale.ROOT).startsWith("text/html");

            if (!html) break;

            String page;
            try (InputStream in = new BufferedInputStream(conn.getInputStream(), 32 * 1024);
                 ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                byte[] b = new byte[32 * 1024];
                int n;
                int total = 0;
                while ((n = in.read(b)) != -1) {
                    total += n;
                    if (total > 4 * 1024 * 1024) throw new Exception("Страница подтверждения Drive слишком большая");
                    bos.write(b, 0, n);
                }
                page = bos.toString("UTF-8");
            }

            String next = resolveConfirmationUrl(page, currentUrl);
            if (next == null || next.equals(currentUrl)) {
                throw new Exception("Google Drive не отдал ссылку на скачивание файла " + out.getName());
            }
            currentUrl = next;
        }

        if (conn == null) throw new Exception("Не удалось открыть файл " + out.getName());

        File temp = new File(out.getAbsolutePath() + ".part");
        long written = 0;
        try (InputStream raw = new BufferedInputStream(conn.getInputStream(), 128 * 1024);
             BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(temp, false), 128 * 1024)) {
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = raw.read(buffer)) != -1) {
                written += read;
                if (alreadyDownloaded + written > MAX_TOTAL_BYTES) {
                    throw new Exception("Объём данных превысил 12 ГБ");
                }
                bos.write(buffer, 0, read);
            }
        } finally {
            conn.disconnect();
        }

        if (out.exists() && !out.delete()) {
            throw new Exception("Не удалось заменить " + out.getAbsolutePath());
        }
        if (!temp.renameTo(out)) {
            copyLocalFile(temp, out);
            if (!temp.delete()) temp.deleteOnExit();
        }
        return written;
    }

    private String resolveConfirmationUrl(String html, String baseUrl) {
        try {
            Document doc = Jsoup.parse(html, baseUrl);
            Element form = doc.selectFirst("form#download-form");
            if (form != null) {
                String action = form.absUrl("action");
                if (action == null || action.isEmpty()) action = form.attr("action");
                if (action != null && !action.isEmpty()) {
                    StringBuilder url = new StringBuilder(action);
                    boolean first = !action.contains("?");
                    for (Element input : form.select("input[type=hidden][name]")) {
                        String name = input.attr("name");
                        String value = input.attr("value");
                        url.append(first ? '?' : '&');
                        first = false;
                        url.append(URLEncoder.encode(name, "UTF-8"));
                        url.append('=');
                        url.append(URLEncoder.encode(value, "UTF-8"));
                    }
                    return url.toString().replace("&amp;", "&");
                }
            }

            Element link = doc.selectFirst("a[href^=/uc?export=download]");
            if (link != null) {
                String href = link.attr("href").replace("&amp;", "&");
                return "https://docs.google.com" + href;
            }

            Matcher matcher = DOWNLOAD_URL_JSON.matcher(html);
            if (matcher.find()) {
                return matcher.group(1)
                        .replace("\\u003d", "=")
                        .replace("\\u0026", "&")
                        .replace("\\u0025", "%")
                        .replace("\\/", "/");
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private HttpURLConnection openConnection(String urlString) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Accept", "*/*");
        conn.setRequestProperty("Accept-Language", "en-US,en;q=0.8");
        conn.setUseCaches(false);
        return conn;
    }

    private File safeOutputFile(File root, String relativePath) throws Exception {
        File out = new File(root, relativePath);
        String rootCanonical = root.getCanonicalPath() + File.separator;
        String outCanonical = out.getCanonicalPath();
        if (!outCanonical.startsWith(rootCanonical)) {
            throw new Exception("Небезопасный путь: " + relativePath);
        }
        return out;
    }

    private String sanitizeName(String name) {
        if (name == null) return "";
        String cleaned = name.replace('\u0000', ' ').trim();
        cleaned = cleaned.replace('/', '_').replace('\\', '_');
        if (cleaned.equals(".") || cleaned.equals("..")) return "_";
        return cleaned;
    }

    private void copyLocalFile(File source, File target) throws Exception {
        try (InputStream in = new BufferedInputStream(new java.io.FileInputStream(source), 128 * 1024);
             BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(target, false), 128 * 1024)) {
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        }
    }

    private void verifyGameFiles() throws Exception {
        File root = getXashRoot();
        File valve = new File(root, "valve");
        File cstrike = new File(root, "cstrike");
        if (!valve.isDirectory()) throw new Exception("После загрузки отсутствует папка valve");
        if (!cstrike.isDirectory()) throw new Exception("После загрузки отсутствует папка cstrike");
        if (!new File(cstrike, "liblist.gam").isFile()) throw new Exception("Отсутствует cstrike/liblist.gam");
        if (!new File(cstrike, "maps").isDirectory()) throw new Exception("Отсутствует cstrike/maps");
        if (!new File(cstrike, "models").isDirectory()) throw new Exception("Отсутствует cstrike/models");
    }

    private void refreshStatus() {
        boolean access = hasFileAccess();
        if (permissionButton != null) {
            permissionButton.setText(access ? "✓ Доступ к памяти разрешён" : "1. Разрешить доступ к памяти");
        }

        File root = getXashRoot();
        File valve = new File(root, "valve");
        File cstrike = new File(root, "cstrike");
        boolean hasValve = valve.isDirectory();
        boolean hasCstrike = cstrike.isDirectory();
        boolean hasMaps = new File(cstrike, "maps").isDirectory();
        boolean hasModels = new File(cstrike, "models").isDirectory();
        boolean hasGameMarker = new File(cstrike, "liblist.gam").isFile();
        boolean clientInstalled = isAnyPackageInstalled("su.xash.cs16client.test", "su.xash.cs16client");
        boolean engineInstalled = isAnyPackageInstalled("su.xash.engine.test", "su.xash.engine");

        if (hasValve && hasCstrike && hasMaps && hasModels && hasGameMarker) {
            status.setText("✓ CS 1.6 готова к запуску");
            status.setTextColor(Color.rgb(91, 214, 144));
        } else if (hasValve || hasCstrike) {
            status.setText("Установка не завершена");
            status.setTextColor(Color.rgb(255, 194, 92));
        } else {
            status.setText("Игра ещё не установлена");
            status.setTextColor(Color.rgb(255, 194, 92));
        }

        detail.setText(
                "Память: " + (access ? "доступ есть" : "нужно разрешение") +
                "\nvalve: " + (hasValve ? "есть" : "нет") +
                "   •   cstrike: " + (hasCstrike ? "есть" : "нет") +
                "\nXash3D: " + (engineInstalled ? "установлен" : "не найден") +
                "   •   CS16Client: " + (clientInstalled ? "установлен" : "не найден") +
                "\nПуть: " + root.getAbsolutePath());

        launchButton.setEnabled(clientInstalled && hasCstrike);
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
        if (busy) {
            progress.setIndeterminate(true);
        } else {
            progress.setIndeterminate(true);
            progress.setProgress(0);
        }
        installButton.setEnabled(!busy);
        permissionButton.setEnabled(!busy);
        launchButton.setEnabled(!busy && isAnyPackageInstalled("su.xash.cs16client.test", "su.xash.cs16client"));
        if (message != null) {
            status.setText("Установка…");
            status.setTextColor(Color.WHITE);
            detail.setText(message);
        }
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD);
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

    private static final class Child {
        final String id;
        final String name;
        final boolean folder;

        Child(String id, String name, boolean folder) {
            this.id = id;
            this.name = name;
            this.folder = folder;
        }
    }

    private static final class RemoteFile {
        final String id;
        final String relativePath;

        RemoteFile(String id, String relativePath) {
            this.id = id;
            this.relativePath = relativePath;
        }
    }
}
