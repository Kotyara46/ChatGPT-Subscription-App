package com.kotyara.chatgptpluscontrol;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends Activity {
    private static final int REQ_WRITE = 1701;
    private static final String MANIFEST_ASSET = "drive-manifest.tsv";
    private static final int MASTER_MAX = 500;
    private static final int INFO_MAX = 180;

    private final ExecutorService io = Executors.newCachedThreadPool();
    private final ExecutorService serverPool = Executors.newFixedThreadPool(16);
    private boolean pendingInstall;
    private TextView stateLine;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        showMainMenu();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pendingInstall && hasFileAccess()) {
            pendingInstall = false;
            installFromDrive();
        } else if (stateLine != null) {
            refreshState();
        }
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        serverPool.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        showMainMenu();
    }

    private void showMainMenu() {
        LinearLayout root = baseScreen();

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(Gravity.CENTER_VERTICAL);
        left.setPadding(dp(34), dp(24), dp(22), dp(24));
        root.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.12f));

        TextView title = label("COUNTER-STRIKE", 39, Color.rgb(224, 225, 208), true);
        left.addView(title);
        left.addView(label("1.6", 25, Color.rgb(191, 167, 85), true), marginTop(2));
        left.addView(label("ANDROID • GOLDSRC 48", 13, Color.rgb(143, 150, 137), true), marginTop(12));

        stateLine = label("Проверка установки…", 14, Color.rgb(194, 190, 173), false);
        left.addView(stateLine, marginTop(28));

        LinearLayout menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setGravity(Gravity.CENTER_VERTICAL);
        menu.setPadding(dp(12), dp(20), dp(34), dp(20));
        root.addView(menu, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.88f));

        Button play = menuButton("Новая игра");
        play.setOnClickListener(v -> launchClient(null));
        menu.addView(play, menuLp());

        Button servers = menuButton("Найти серверы");
        servers.setOnClickListener(v -> showServerBrowser());
        menu.addView(servers, menuLp());

        Button install = menuButton("Установить / восстановить игру");
        install.setOnClickListener(v -> ensureAccessAndInstall());
        menu.addView(install, menuLp());

        Button settings = menuButton("Настройки");
        settings.setOnClickListener(v -> showSettings());
        menu.addView(settings, menuLp());

        Button exit = menuButton("Выход");
        exit.setOnClickListener(v -> finishAndRemoveTask());
        menu.addView(exit, menuLp());

        setContentView(root);
        refreshState();
    }

    private LinearLayout baseScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(8, 11, 9), Color.rgb(31, 36, 29), Color.rgb(8, 9, 8)});
        root.setBackground(bg);
        return root;
    }

    private void refreshState() {
        if (stateLine == null) return;
        boolean data = gameReady();
        boolean engine = anyInstalled("su.xash.engine.test", "su.xash.engine");
        boolean client = anyInstalled("com.kotyara.cs16client.test", "com.kotyara.cs16client",
                "su.xash.cs16client.test", "su.xash.cs16client");
        stateLine.setText(
                "Файлы игры: " + (data ? "ГОТОВЫ" : "НЕ УСТАНОВЛЕНЫ") +
                        "\nДвижок Xash3D: " + (engine ? "ГОТОВ" : "НЕ УСТАНОВЛЕН") +
                        "\nКлиент CS 1.6: " + (client ? "ГОТОВ" : "НЕ УСТАНОВЛЕН") +
                        "\n\n/storage/emulated/0/xash");
        stateLine.setTextColor(data && engine && client
                ? Color.rgb(128, 204, 125) : Color.rgb(222, 177, 91));
    }

    private void showSettings() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(26), dp(20), dp(26), dp(20));
        root.setBackgroundColor(Color.rgb(12, 15, 13));
        root.addView(label("Настройки", 28, Color.WHITE, true));
        root.addView(label(
                "Язык: русский\n" +
                        "Каталог игры: /storage/emulated/0/xash\n" +
                        "Сетевой протокол: GoldSrc 48\n" +
                        "Список серверов: Valve Master Server, cstrike\n" +
                        "Источник игровых файлов: твоя папка Google Drive\n\n" +
                        "Лаунчер сохраняет уже скачанные файлы. Если загрузка прервётся, нажми установку ещё раз — она продолжится.",
                16, Color.rgb(191, 196, 187), false), marginTop(18));

        Button access = menuButton(hasFileAccess() ? "Доступ к памяти: разрешён" : "Разрешить доступ к памяти");
        access.setOnClickListener(v -> requestFileAccess(false));
        root.addView(access, marginTop(18));

        Button reinstall = menuButton("Удалить игровые файлы и установить заново");
        reinstall.setOnClickListener(v -> {
            if (!hasFileAccess()) {
                Toast.makeText(this, "Сначала разреши доступ к памяти", Toast.LENGTH_LONG).show();
                requestFileAccess(false);
                return;
            }
            io.execute(() -> {
                deleteRecursive(new File(xashRoot(), "valve"));
                deleteRecursive(new File(xashRoot(), "cstrike"));
                runOnUiThread(this::installFromDrive);
            });
        });
        root.addView(reinstall, marginTop(10));

        Button back = menuButton("Назад");
        back.setOnClickListener(v -> showMainMenu());
        root.addView(back, marginTop(10));
        setContentView(root);
    }

    private void showServerBrowser() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(14), dp(18), dp(12));
        root.setBackgroundColor(Color.rgb(12, 15, 13));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        TextView title = label("Найти серверы", 25, Color.WHITE, true);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button back = smallButton("Назад");
        back.setOnClickListener(v -> showMainMenu());
        top.addView(back);
        root.addView(top);

        LinearLayout direct = new LinearLayout(this);
        direct.setOrientation(LinearLayout.HORIZONTAL);
        direct.setPadding(0, dp(10), 0, dp(8));
        root.addView(direct);

        EditText ip = new EditText(this);
        ip.setSingleLine(true);
        ip.setHint("IP:порт, например 123.45.67.89:27015");
        ip.setHintTextColor(Color.rgb(120, 125, 118));
        ip.setTextColor(Color.WHITE);
        ip.setTextSize(15);
        ip.setInputType(InputType.TYPE_CLASS_TEXT);
        ip.setBackground(rounded(Color.rgb(25, 29, 25), Color.rgb(73, 78, 69), 5));
        ip.setPadding(dp(12), dp(8), dp(12), dp(8));
        direct.addView(ip, new LinearLayout.LayoutParams(0, dp(48), 1f));

        Button connect = smallButton("Подключиться");
        connect.setOnClickListener(v -> {
            String addr = normalizeAddress(ip.getText().toString());
            if (addr == null) Toast.makeText(this, "Неверный адрес сервера", Toast.LENGTH_LONG).show();
            else launchClient(addr);
        });
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        cp.leftMargin = dp(8);
        direct.addView(connect, cp);

        TextView head = label("СЕРВЕР                                  КАРТА           ИГРОКИ      ПИНГ", 12,
                Color.rgb(158, 164, 152), true);
        root.addView(head, marginTop(4));

        TextView progress = label("Получаю список серверов CS 1.6…", 14, Color.rgb(190, 193, 183), false);
        root.addView(progress, marginTop(5));
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(5)));

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        io.execute(() -> loadServers(list, progress, bar));
    }

    private void loadServers(LinearLayout list, TextView progress, ProgressBar bar) {
        try {
            Set<String> addresses = queryMaster("hl2master.steampowered.com", 27011, MASTER_MAX);
            if (addresses.isEmpty()) addresses = queryMaster("hl1master.steampowered.com", 27010, MASTER_MAX);
            List<String> items = new ArrayList<>(addresses);
            int total = Math.min(INFO_MAX, items.size());
            runOnUiThread(() -> progress.setText("Получено адресов: " + addresses.size() + ". Проверяю ответы…"));
            if (total == 0) {
                runOnUiThread(() -> {
                    bar.setVisibility(View.GONE);
                    progress.setText("Master Server не вернул список. Подключись по IP:порт.");
                });
                return;
            }
            AtomicInteger done = new AtomicInteger();
            AtomicInteger shown = new AtomicInteger();
            for (int i = 0; i < total; i++) {
                String address = items.get(i);
                serverPool.execute(() -> {
                    ServerInfo info = queryInfo(address);
                    int n = done.incrementAndGet();
                    runOnUiThread(() -> {
                        if (info != null && "cstrike".equalsIgnoreCase(info.folder)) {
                            addServerRow(list, info);
                            shown.incrementAndGet();
                        }
                        progress.setText("Проверено " + n + "/" + total + " • показано " + shown.get());
                        if (n >= total) {
                            bar.setVisibility(View.GONE);
                            if (shown.get() == 0) progress.setText("Серверы не ответили. Можно подключиться по IP:порт.");
                        }
                    });
                });
            }
        } catch (Exception e) {
            runOnUiThread(() -> {
                bar.setVisibility(View.GONE);
                progress.setText("Ошибка списка серверов: " + safeMessage(e));
            });
        }
    }

    private void addServerRow(LinearLayout list, ServerInfo s) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(9), dp(12), dp(9));
        row.setBackground(rounded(Color.rgb(23, 27, 23), Color.rgb(56, 62, 54), 4));
        row.addView(label(s.name.isEmpty() ? s.address : s.name, 16, Color.WHITE, true));
        row.addView(label(s.map + "   •   " + s.players + "/" + s.maxPlayers + " игроков   •   " + s.ping + " мс   •   " + s.address,
                13, Color.rgb(174, 180, 168), false), marginTop(3));
        row.setOnClickListener(v -> launchClient(s.address));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(5);
        list.addView(row, lp);
    }

    private Set<String> queryMaster(String host, int port, int max) throws Exception {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        InetAddress master = InetAddress.getByName(host);
        String start = "0.0.0.0:0";
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(2500);
            for (int page = 0; page < 16 && result.size() < max; page++) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                out.write(0x31);
                out.write(0xFF);
                out.write(start.getBytes(StandardCharsets.US_ASCII));
                out.write(0);
                out.write("\\gamedir\\cstrike".getBytes(StandardCharsets.US_ASCII));
                out.write(0);
                byte[] req = out.toByteArray();
                socket.send(new DatagramPacket(req, req.length, master, port));
                byte[] buf = new byte[65507];
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet);
                int len = packet.getLength();
                int pos = 0;
                if (len >= 4 && isFF(buf, 0)) pos = 4;
                if (pos < len && (buf[pos] & 255) == 0x66) pos++;
                if (pos < len && (buf[pos] & 255) == 0x0A) pos++;
                String last = null;
                boolean end = false;
                while (pos + 6 <= len && result.size() < max) {
                    int a = buf[pos++] & 255;
                    int b = buf[pos++] & 255;
                    int c = buf[pos++] & 255;
                    int d = buf[pos++] & 255;
                    int prt = ((buf[pos++] & 255) << 8) | (buf[pos++] & 255);
                    if (a == 0 && b == 0 && c == 0 && d == 0 && prt == 0) {
                        end = true;
                        break;
                    }
                    last = a + "." + b + "." + c + "." + d + ":" + prt;
                    result.add(last);
                }
                if (end || last == null || last.equals(start)) break;
                start = last;
            }
        }
        return result;
    }

    private ServerInfo queryInfo(String address) {
        String[] hp = address.split(":");
        if (hp.length != 2) return null;
        try {
            InetAddress host = InetAddress.getByName(hp[0]);
            int port = Integer.parseInt(hp[1]);
            byte[] base = new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x54,
                    'S', 'o', 'u', 'r', 'c', 'e', ' ', 'E', 'n', 'g', 'i', 'n', 'e', ' ',
                    'Q', 'u', 'e', 'r', 'y', 0};
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setSoTimeout(1100);
                long started = SystemClock.elapsedRealtime();
                socket.send(new DatagramPacket(base, base.length, host, port));
                byte[] buf = new byte[8192];
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet);
                int len = packet.getLength();
                if (len >= 9 && isFF(buf, 0) && (buf[4] & 255) == 0x41) {
                    byte[] req = new byte[base.length + 4];
                    System.arraycopy(base, 0, req, 0, base.length);
                    System.arraycopy(buf, 5, req, base.length, 4);
                    socket.send(new DatagramPacket(req, req.length, host, port));
                    packet = new DatagramPacket(buf, buf.length);
                    socket.receive(packet);
                    len = packet.getLength();
                }
                int ping = (int) (SystemClock.elapsedRealtime() - started);
                if (len < 8 || !isFF(buf, 0)) return null;
                int type = buf[4] & 255;
                if (type == 0x49) {
                    Cursor c = new Cursor(buf, 6, len);
                    String name = c.str();
                    String map = c.str();
                    String folder = c.str();
                    c.str();
                    c.skip(2);
                    int players = c.u8();
                    int maxPlayers = c.u8();
                    return new ServerInfo(address, name, map, folder, players, maxPlayers, ping);
                }
                if (type == 0x6D) {
                    Cursor c = new Cursor(buf, 5, len);
                    c.str();
                    String name = c.str();
                    String map = c.str();
                    String folder = c.str();
                    c.str();
                    int players = c.u8();
                    int maxPlayers = c.u8();
                    return new ServerInfo(address, name, map, folder, players, maxPlayers, ping);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void ensureAccessAndInstall() {
        if (!hasFileAccess()) {
            pendingInstall = true;
            requestFileAccess(true);
        } else {
            installFromDrive();
        }
    }

    private void requestFileAccess(boolean installAfter) {
        pendingInstall = installAfter;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                if (installAfter) installFromDrive();
                return;
            }
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_WRITE);
        } else if (installAfter) {
            installFromDrive();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_WRITE && grantResults.length > 0 &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED && pendingInstall) {
            pendingInstall = false;
            installFromDrive();
        }
    }

    private boolean hasFileAccess() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R
                ? checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                : Environment.isExternalStorageManager();
    }

    private void installFromDrive() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(30), dp(30), dp(30), dp(30));
        root.setBackgroundColor(Color.rgb(12, 15, 13));
        TextView title = label("Установка Counter-Strike 1.6", 25, Color.WHITE, true);
        TextView status = label("Читаю список файлов Google Drive…", 16, Color.rgb(190, 195, 185), false);
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(false);
        bar.setMax(1000);
        root.addView(title);
        root.addView(status, marginTop(14));
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(9)));
        setContentView(root);

        io.execute(() -> {
            try {
                File xr = xashRoot();
                if (!xr.exists() && !xr.mkdirs()) throw new Exception("Не удалось создать папку xash");
                List<DriveFile> files = readDriveManifest();
                if (files.size() < 1000) throw new Exception("Манифест игры неполный: " + files.size() + " файлов");

                AtomicInteger finished = new AtomicInteger();
                AtomicInteger downloaded = new AtomicInteger();
                List<String> failures = Collections.synchronizedList(new ArrayList<>());
                ExecutorService downloads = Executors.newFixedThreadPool(3);
                List<Future<?>> futures = new ArrayList<>(files.size());

                runOnUiThread(() -> status.setText("Источник: Google Drive • файлов: " + files.size() + "\nСкачивание можно продолжить после обрыва."));

                for (DriveFile item : files) {
                    futures.add(downloads.submit(() -> {
                        try {
                            File dest = safeGameFile(xr, item.path);
                            boolean force = isCritical(item.path);
                            if (!dest.exists() || force) {
                                downloadDriveFile(item.id, dest, item.path);
                                downloaded.incrementAndGet();
                            }
                        } catch (Exception e) {
                            failures.add(item.path + ": " + safeMessage(e));
                        }
                        int n = finished.incrementAndGet();
                        if (n % 20 == 0 || n == files.size()) {
                            int progress = (int) ((n * 1000L) / files.size());
                            int dl = downloaded.get();
                            int bad = failures.size();
                            runOnUiThread(() -> {
                                bar.setProgress(progress);
                                status.setText("Google Drive → телефон\nПроверено " + n + "/" + files.size() +
                                        " • скачано " + dl + " • ошибок " + bad);
                            });
                        }
                    }));
                }

                for (Future<?> f : futures) f.get();
                downloads.shutdownNow();

                if (!failures.isEmpty()) {
                    int bad = failures.size();
                    String first = failures.get(0);
                    runOnUiThread(() -> {
                        status.setTextColor(Color.rgb(255, 140, 110));
                        status.setText("Не докачано файлов: " + bad + "\nПервая ошибка: " + first +
                                "\n\nНажми «Продолжить» — готовые файлы повторно качаться не будут.");
                        Button retry = menuButton("Продолжить установку");
                        retry.setOnClickListener(v -> installFromDrive());
                        root.addView(retry, marginTop(18));
                        Button back = menuButton("В главное меню");
                        back.setOnClickListener(v -> showMainMenu());
                        root.addView(back, marginTop(8));
                    });
                    return;
                }

                verifyGame();
                writeMobileConfig();
                runOnUiThread(() -> {
                    bar.setProgress(1000);
                    status.setTextColor(Color.rgb(128, 204, 125));
                    status.setText("ГОТОВО\nvalve + cstrike установлены. HUD, карты, модели и русская локализация проверены.");
                    Button launch = menuButton("Запустить Counter-Strike 1.6");
                    launch.setOnClickListener(v -> launchClient(null));
                    root.addView(launch, marginTop(18));
                    Button back = menuButton("В главное меню");
                    back.setOnClickListener(v -> showMainMenu());
                    root.addView(back, marginTop(8));
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    status.setTextColor(Color.rgb(255, 120, 105));
                    status.setText("Ошибка установки: " + safeMessage(e));
                    Button retry = menuButton("Повторить");
                    retry.setOnClickListener(v -> installFromDrive());
                    root.addView(retry, marginTop(18));
                    Button back = menuButton("Назад");
                    back.setOnClickListener(v -> showMainMenu());
                    root.addView(back, marginTop(8));
                });
            }
        });
    }

    private List<DriveFile> readDriveManifest() throws Exception {
        List<DriveFile> result = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                getAssets().open(MANIFEST_ASSET), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] p = line.split("\\t", 2);
                if (p.length != 2) continue;
                String path = p[1].replace('\\', '/');
                if (!(path.startsWith("valve/") || path.startsWith("cstrike/"))) continue;
                result.add(new DriveFile(p[0], path));
            }
        }
        return result;
    }

    private void downloadDriveFile(String id, File dest, String path) throws Exception {
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new Exception("Не удалось создать папку");
        File part = new File(dest.getAbsolutePath() + ".part");
        if (part.exists()) part.delete();

        Exception last = null;
        for (int attempt = 0; attempt < 8; attempt++) {
            HttpURLConnection con = null;
            try {
                String u = "https://drive.usercontent.google.com/download?id=" + id + "&export=download&confirm=t";
                con = (HttpURLConnection) new URL(u).openConnection();
                con.setInstanceFollowRedirects(true);
                con.setConnectTimeout(20000);
                con.setReadTimeout(60000);
                con.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) CS16-Mobile/3.0");
                con.setRequestProperty("Accept", "*/*");
                int code = con.getResponseCode();
                if (code != 200) throw new Exception("HTTP " + code);
                String ct = con.getContentType();
                String lower = path.toLowerCase(Locale.ROOT);
                if (ct != null && ct.toLowerCase(Locale.ROOT).contains("text/html") &&
                        !(lower.endsWith(".html") || lower.endsWith(".htm"))) {
                    throw new Exception("Google Drive вернул HTML вместо файла");
                }
                try (InputStream in = new BufferedInputStream(con.getInputStream(), 128 * 1024);
                     BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(part), 128 * 1024)) {
                    byte[] buf = new byte[128 * 1024];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                }
                if (dest.exists() && !dest.delete()) throw new Exception("Не удалось заменить файл");
                if (!part.renameTo(dest)) throw new Exception("Не удалось сохранить файл");
                return;
            } catch (Exception e) {
                last = e;
                if (part.exists()) part.delete();
                long pause = Math.min(45000L, 1500L * (1L << Math.min(attempt, 5)));
                SystemClock.sleep(pause);
            } finally {
                if (con != null) con.disconnect();
            }
        }
        throw last != null ? last : new Exception("Неизвестная ошибка загрузки");
    }

    private File safeGameFile(File root, String path) throws Exception {
        File out = new File(root, path);
        String base = root.getCanonicalPath() + File.separator;
        if (!out.getCanonicalPath().startsWith(base)) throw new Exception("Небезопасный путь");
        return out;
    }

    private boolean isCritical(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        return p.equals("cstrike/liblist.gam") ||
                p.equals("cstrike/sprites/hud.txt") ||
                p.equals("cstrike/resource/gameui_russian.txt") ||
                p.equals("cstrike/resource/serverbrowser_russian.txt") ||
                p.equals("valve/resource/valve_russian.txt");
    }

    private void verifyGame() throws Exception {
        File c = new File(xashRoot(), "cstrike");
        File v = new File(xashRoot(), "valve");
        requireDir(v, "valve");
        requireDir(c, "cstrike");
        requireFile(new File(c, "liblist.gam"), "cstrike/liblist.gam");
        requireDir(new File(c, "maps"), "cstrike/maps");
        requireDir(new File(c, "models"), "cstrike/models");
        requireDir(new File(c, "sprites"), "cstrike/sprites");
        File hud = new File(c, "sprites/hud.txt");
        requireFile(hud, "cstrike/sprites/hud.txt");
        byte[] data;
        try (FileInputStream in = new FileInputStream(hud)) {
            data = new byte[(int) Math.min(hud.length(), 65536)];
            int n = in.read(data);
            if (n <= 0 || !new String(data, 0, n, StandardCharsets.ISO_8859_1).contains("number_0"))
                throw new Exception("hud.txt повреждён: нет number_0");
        }
        requireFile(new File(c, "resource/gameui_russian.txt"), "русская локализация GameUI");
        requireFile(new File(c, "resource/serverbrowser_russian.txt"), "русская локализация браузера серверов");
        requireFile(new File(v, "resource/valve_russian.txt"), "русская локализация Valve");
    }

    private void writeMobileConfig() {
        try {
            File cfg = new File(new File(xashRoot(), "cstrike"), "autoexec.cfg");
            try (FileOutputStream out = new FileOutputStream(cfg, true)) {
                String s = "\n// CS16 Android Final RU\ncl_advertise_engine_in_name 0\ncl_ticket_generator revemu2013\n";
                out.write(s.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
    }

    private void launchClient(String server) {
        if (!gameReady()) {
            Toast.makeText(this, "Сначала установи файлы игры", Toast.LENGTH_LONG).show();
            return;
        }
        if (!anyInstalled("su.xash.engine.test", "su.xash.engine")) {
            Toast.makeText(this, "Сначала установи Xash3D FWGS из комплекта", Toast.LENGTH_LONG).show();
            return;
        }
        String[] pkgs = {"com.kotyara.cs16client.test", "com.kotyara.cs16client",
                "su.xash.cs16client.test", "su.xash.cs16client"};
        for (String pkg : pkgs) {
            try {
                getPackageManager().getPackageInfo(pkg, 0);
                Intent i = new Intent();
                i.setComponent(new ComponentName(pkg, "su.xash.cs16client.MainActivity"));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                if (server != null) i.putExtra("server", server);
                startActivity(i);
                return;
            } catch (Exception ignored) {
            }
        }
        Toast.makeText(this, "Не установлен клиент CS16 из комплекта", Toast.LENGTH_LONG).show();
    }

    private boolean gameReady() {
        File c = new File(xashRoot(), "cstrike");
        File v = new File(xashRoot(), "valve");
        return v.isDirectory() && c.isDirectory() &&
                new File(c, "liblist.gam").isFile() &&
                new File(c, "maps").isDirectory() &&
                new File(c, "models").isDirectory() &&
                new File(c, "sprites/hud.txt").isFile() &&
                new File(c, "resource/gameui_russian.txt").isFile();
    }

    private File xashRoot() {
        return new File(Environment.getExternalStorageDirectory(), "xash");
    }

    private boolean anyInstalled(String... pkgs) {
        for (String pkg : pkgs) {
            try {
                getPackageManager().getPackageInfo(pkg, 0);
                return true;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private void requireDir(File f, String label) throws Exception {
        if (!f.isDirectory()) throw new Exception("Нет папки " + label);
    }

    private void requireFile(File f, String label) throws Exception {
        if (!f.isFile()) throw new Exception("Нет файла " + label);
    }

    private void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursive(c);
        }
        f.delete();
    }

    private String normalizeAddress(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.matches("^[A-Za-z0-9._-]+:[0-9]{1,5}$")) return s;
        if (s.matches("^[A-Za-z0-9._-]+$")) return s + ":27015";
        return null;
    }

    private boolean isFF(byte[] b, int p) {
        return p + 3 < b.length && (b[p] & 255) == 255 && (b[p + 1] & 255) == 255 &&
                (b[p + 2] & 255) == 255 && (b[p + 3] & 255) == 255;
    }

    private String safeMessage(Throwable t) {
        if (t == null || t.getMessage() == null || t.getMessage().trim().isEmpty()) return t == null ? "неизвестно" : t.getClass().getSimpleName();
        return t.getMessage();
    }

    private LinearLayout.LayoutParams menuLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(49));
        lp.topMargin = dp(7);
        return lp;
    }

    private LinearLayout.LayoutParams marginTop(int dp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = this.dp(dp);
        return lp;
    }

    private TextView label(String text, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private Button menuButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.rgb(225, 226, 214));
        b.setTextSize(16);
        b.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        b.setAllCaps(false);
        b.setPadding(dp(16), 0, dp(12), 0);
        b.setBackground(rounded(Color.rgb(29, 34, 28), Color.rgb(75, 81, 70), 4));
        return b;
    }

    private Button smallButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(14);
        b.setBackground(rounded(Color.rgb(36, 42, 35), Color.rgb(77, 84, 73), 4));
        return b;
    }

    private GradientDrawable rounded(int fill, int stroke, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        g.setStroke(dp(1), stroke);
        return g;
    }

    private int dp(int n) {
        return (int) (n * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static String decodeServerText(byte[] data, int start, int len) {
        if (len <= 0) return "";
        String utf = new String(data, start, len, StandardCharsets.UTF_8);
        if (!utf.contains("\uFFFD")) return utf;
        return new String(data, start, len, Charset.forName("windows-1251"));
    }

    private static final class Cursor {
        final byte[] data;
        int pos;
        final int end;

        Cursor(byte[] data, int pos, int end) {
            this.data = data;
            this.pos = Math.max(0, pos);
            this.end = Math.min(end, data.length);
        }

        int u8() {
            if (pos >= end) return 0;
            return data[pos++] & 255;
        }

        void skip(int n) {
            pos = Math.min(end, pos + n);
        }

        String str() {
            int start = pos;
            while (pos < end && data[pos] != 0) pos++;
            String s = decodeServerText(data, start, pos - start);
            if (pos < end) pos++;
            return s;
        }
    }

    private static final class ServerInfo {
        final String address;
        final String name;
        final String map;
        final String folder;
        final int players;
        final int maxPlayers;
        final int ping;

        ServerInfo(String address, String name, String map, String folder, int players, int maxPlayers, int ping) {
            this.address = address;
            this.name = name == null ? "" : name;
            this.map = map == null ? "" : map;
            this.folder = folder == null ? "" : folder;
            this.players = players;
            this.maxPlayers = maxPlayers;
            this.ping = ping;
        }
    }

    private static final class DriveFile {
        final String id;
        final String path;

        DriveFile(String id, String path) {
            this.id = id;
            this.path = path;
        }
    }
}
