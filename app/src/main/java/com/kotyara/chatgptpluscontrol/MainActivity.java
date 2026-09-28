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
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MainActivity extends Activity {
    private static final int REQ_WRITE = 1701;
    private static final String DATA_ASSET = "CS16-GameData.zip";
    private static final int MASTER_MAX = 300;
    private static final int INFO_MAX = 100;

    private final ExecutorService io = Executors.newCachedThreadPool();
    private boolean pendingInstall;
    private TextView stateLine;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        showMainMenu();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pendingInstall && hasFileAccess()) {
            pendingInstall = false;
            installBundledGame();
        } else if (stateLine != null) {
            refreshState();
        }
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        showMainMenu();
    }

    private void showMainMenu() {
        LinearLayout root = baseScreen();

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(34), dp(24), dp(24), dp(8));
        root.addView(header, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.05f));

        TextView title = label("COUNTER-STRIKE", 38, Color.rgb(225, 226, 210), true);
        header.addView(title);
        TextView sub = label("1.6  •  Android", 20, Color.rgb(178, 164, 105), true);
        header.addView(sub, marginTop(4));
        header.addView(label("Классический клиент • русский интерфейс • GoldSrc 48", 14,
                Color.rgb(150, 154, 146), false), marginTop(16));

        stateLine = label("Проверка установки…", 14, Color.rgb(190, 190, 180), false);
        header.addView(stateLine, marginTop(26));

        LinearLayout menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setGravity(Gravity.CENTER_VERTICAL);
        menu.setPadding(dp(14), dp(18), dp(30), dp(18));
        root.addView(menu, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.95f));

        Button play = menuButton("Новая игра");
        play.setOnClickListener(v -> launchClient(null));
        menu.addView(play, menuLp());

        Button servers = menuButton("Найти серверы");
        servers.setOnClickListener(v -> showServerBrowser());
        menu.addView(servers, menuLp());

        Button install = menuButton("Установить / восстановить файлы игры");
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
        root.setPadding(dp(10), dp(10), dp(10), dp(10));
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(12, 15, 13), Color.rgb(33, 37, 31), Color.rgb(8, 9, 8)});
        root.setBackground(bg);
        return root;
    }

    private void refreshState() {
        if (stateLine == null) return;
        boolean data = gameReady();
        boolean engine = anyInstalled("su.xash.engine.test", "su.xash.engine");
        boolean client = anyInstalled("com.kotyara.cs16client.test", "com.kotyara.cs16client",
                "su.xash.cs16client.test", "su.xash.cs16client");
        String text = "Файлы игры: " + (data ? "готовы" : "не установлены") +
                "\nXash3D: " + (engine ? "установлен" : "не установлен") +
                "\nКлиент: " + (client ? "установлен" : "не установлен") +
                "\nПуть: " + xashRoot().getAbsolutePath();
        stateLine.setText(text);
        stateLine.setTextColor(data && engine && client ? Color.rgb(126, 205, 128) : Color.rgb(218, 178, 100));
    }

    private void showSettings() {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setPadding(dp(26), dp(20), dp(26), dp(20));
        outer.setBackgroundColor(Color.rgb(14, 17, 15));

        outer.addView(label("Настройки", 28, Color.WHITE, true));
        outer.addView(label("Язык игры принудительно: русский\n" +
                "Путь данных: /storage/emulated/0/xash\n" +
                "Протокол серверов: GoldSrc 48\n" +
                "Источник списка: Valve master server, gamedir=cstrike\n\n" +
                "Защищённые Steam-серверы могут требовать Steam-аутентификацию, которую Xash3D на Android поддерживает не на всех серверах. " +
                "Обычные совместимые GoldSrc/ReHLDS серверы запускаются напрямую.",
                16, Color.rgb(190, 195, 186), false), marginTop(18));

        Button access = menuButton(hasFileAccess() ? "Доступ к памяти: разрешён" : "Разрешить доступ к памяти");
        access.setOnClickListener(v -> requestFileAccess(false));
        outer.addView(access, marginTop(18));

        Button back = menuButton("Назад");
        back.setOnClickListener(v -> showMainMenu());
        outer.addView(back, marginTop(10));
        setContentView(outer);
    }

    private void showServerBrowser() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(14), dp(18), dp(12));
        root.setBackgroundColor(Color.rgb(13, 16, 14));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = label("Найти серверы", 25, Color.WHITE, true);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button back = smallButton("Назад");
        back.setOnClickListener(v -> showMainMenu());
        top.addView(back);

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
        GradientDrawable fieldBg = rounded(Color.rgb(26, 30, 26), Color.rgb(70, 74, 67), 6);
        ip.setBackground(fieldBg);
        ip.setPadding(dp(12), dp(8), dp(12), dp(8));
        direct.addView(ip, new LinearLayout.LayoutParams(0, dp(48), 1f));

        Button connect = smallButton("Подключиться");
        connect.setOnClickListener(v -> {
            String addr = normalizeAddress(ip.getText().toString());
            if (addr == null) Toast.makeText(this, "Неверный IP:порт", Toast.LENGTH_LONG).show();
            else launchClient(addr);
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        clp.leftMargin = dp(8);
        direct.addView(connect, clp);

        TextView progressText = label("Запрашиваю список обычных PC-серверов CS 1.6…", 14,
                Color.rgb(185, 187, 177), false);
        root.addView(progressText);

        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(5)));

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        io.execute(() -> loadServers(list, progressText, bar));
    }

    private void loadServers(LinearLayout list, TextView progressText, ProgressBar bar) {
        try {
            Set<String> addresses = queryMaster("hl2master.steampowered.com", 27011, MASTER_MAX);
            if (addresses.isEmpty()) addresses = queryMaster("hl1master.steampowered.com", 27010, MASTER_MAX);
            List<String> items = new ArrayList<>(addresses);
            int total = Math.min(INFO_MAX, items.size());
            runOnUiThread(() -> progressText.setText("Найдено адресов: " + addresses.size() + ". Проверяю серверы…"));
            AtomicInteger done = new AtomicInteger();
            for (int i = 0; i < total; i++) {
                final String address = items.get(i);
                io.execute(() -> {
                    ServerInfo info = queryInfo(address);
                    int n = done.incrementAndGet();
                    runOnUiThread(() -> {
                        progressText.setText("Проверено " + n + " из " + total + " • нажми сервер для подключения");
                        if (info != null && "cstrike".equalsIgnoreCase(info.folder)) addServerRow(list, info);
                        if (n >= total) {
                            bar.setVisibility(View.GONE);
                            if (list.getChildCount() == 0) progressText.setText("Серверы не ответили. Можно подключиться по IP вручную.");
                        }
                    });
                });
            }
            if (total == 0) runOnUiThread(() -> {
                bar.setVisibility(View.GONE);
                progressText.setText("Master server не вернул список. Можно подключиться по IP вручную.");
            });
        } catch (Exception e) {
            runOnUiThread(() -> {
                bar.setVisibility(View.GONE);
                progressText.setText("Не удалось получить список: " + e.getMessage());
            });
        }
    }

    private void addServerRow(LinearLayout list, ServerInfo s) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(9), dp(12), dp(9));
        row.setBackground(rounded(Color.rgb(24, 28, 24), Color.rgb(57, 62, 55), 5));
        TextView name = label(s.name.length() == 0 ? s.address : s.name, 16, Color.WHITE, true);
        TextView meta = label(s.map + "    " + s.players + "/" + s.maxPlayers + " игроков    " + s.ping + " мс    " + s.address,
                13, Color.rgb(174, 180, 168), false);
        row.addView(name);
        row.addView(meta, marginTop(3));
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
            for (int page = 0; page < 8 && result.size() < max; page++) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                out.write(0x31);
                out.write(0xFF);
                out.write(start.getBytes(StandardCharsets.US_ASCII));
                out.write(0);
                out.write("\\gamedir\\cstrike\\empty\\1".getBytes(StandardCharsets.US_ASCII));
                out.write(0);
                byte[] req = out.toByteArray();
                socket.send(new DatagramPacket(req, req.length, master, port));
                byte[] buf = new byte[65507];
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                socket.receive(p);
                int len = p.getLength();
                int pos = 0;
                if (len >= 4 && isFF(buf, 0)) pos = 4;
                if (pos < len && (buf[pos] & 0xFF) == 0x66) pos++;
                if (pos < len && (buf[pos] & 0xFF) == 0x0A) pos++;
                String last = null;
                boolean end = false;
                while (pos + 6 <= len && result.size() < max) {
                    int a = buf[pos++] & 0xFF, b = buf[pos++] & 0xFF, c = buf[pos++] & 0xFF, d = buf[pos++] & 0xFF;
                    int prt = ((buf[pos++] & 0xFF) << 8) | (buf[pos++] & 0xFF);
                    if (a == 0 && b == 0 && c == 0 && d == 0 && prt == 0) { end = true; break; }
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
            byte[] base = new byte[]{(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF,0x54,
                    'S','o','u','r','c','e',' ','E','n','g','i','n','e',' ','Q','u','e','r','y',0};
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setSoTimeout(900);
                long start = SystemClock.elapsedRealtime();
                socket.send(new DatagramPacket(base, base.length, host, port));
                byte[] buf = new byte[4096];
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                socket.receive(p);
                int len = p.getLength();
                if (len >= 9 && isFF(buf,0) && (buf[4] & 0xFF) == 0x41) {
                    byte[] req = new byte[base.length + 4];
                    System.arraycopy(base, 0, req, 0, base.length);
                    System.arraycopy(buf, 5, req, base.length, 4);
                    socket.send(new DatagramPacket(req, req.length, host, port));
                    p = new DatagramPacket(buf, buf.length);
                    socket.receive(p);
                    len = p.getLength();
                }
                int ping = (int)(SystemClock.elapsedRealtime() - start);
                if (len < 10 || !isFF(buf,0) || (buf[4] & 0xFF) != 0x49) return null;
                Cursor cur = new Cursor(buf, 6, len);
                String name = cur.str();
                String map = cur.str();
                String folder = cur.str();
                cur.str();
                cur.skip(2);
                int players = cur.u8();
                int maxPlayers = cur.u8();
                return new ServerInfo(address, name, map, folder, players, maxPlayers, ping);
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean isFF(byte[] b, int p) {
        return p + 3 < b.length && (b[p]&255)==255 && (b[p+1]&255)==255 && (b[p+2]&255)==255 && (b[p+3]&255)==255;
    }

    private void ensureAccessAndInstall() {
        if (!hasFileAccess()) {
            pendingInstall = true;
            requestFileAccess(true);
        } else installBundledGame();
    }

    private void requestFileAccess(boolean installAfter) {
        pendingInstall = installAfter;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                if (installAfter) installBundledGame();
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
        } else if (installAfter) installBundledGame();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_WRITE && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED && pendingInstall) {
            pendingInstall = false;
            installBundledGame();
        }
    }

    private boolean hasFileAccess() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R
                ? checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                : Environment.isExternalStorageManager();
    }

    private void installBundledGame() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(30), dp(30), dp(30), dp(30));
        root.setBackgroundColor(Color.rgb(12, 15, 13));
        TextView title = label("Установка Counter-Strike 1.6", 25, Color.WHITE, true);
        TextView status = label("Распаковываю встроенные valve + cstrike…", 16, Color.rgb(190,195,185), false);
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        root.addView(title);
        root.addView(status, marginTop(14));
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)));
        setContentView(root);

        io.execute(() -> {
            try {
                File xr = xashRoot();
                if (!xr.exists() && !xr.mkdirs()) throw new Exception("Не удалось создать папку xash");
                deleteRecursive(new File(xr, "valve"));
                deleteRecursive(new File(xr, "cstrike"));
                int count = 0;
                long bytes = 0;
                String canonicalRoot = xr.getCanonicalPath() + File.separator;
                try (InputStream raw = getAssets().open(DATA_ASSET);
                     ZipInputStream zin = new ZipInputStream(new BufferedInputStream(raw, 256 * 1024))) {
                    ZipEntry e;
                    byte[] buf = new byte[256 * 1024];
                    while ((e = zin.getNextEntry()) != null) {
                        String name = e.getName().replace('\\','/');
                        if (!(name.startsWith("valve/") || name.startsWith("cstrike/"))) continue;
                        File out = new File(xr, name);
                        if (!out.getCanonicalPath().startsWith(canonicalRoot)) throw new Exception("Небезопасный путь в архиве");
                        if (e.isDirectory()) {
                            if (!out.exists() && !out.mkdirs()) throw new Exception("Не удалось создать " + name);
                        } else {
                            File parent = out.getParentFile();
                            if (parent != null && !parent.exists() && !parent.mkdirs()) throw new Exception("Не удалось создать папку");
                            try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(out), 256 * 1024)) {
                                int n;
                                while ((n = zin.read(buf)) != -1) { bos.write(buf,0,n); bytes += n; }
                            }
                            count++;
                            if (count % 100 == 0) {
                                int c = count; long mb = bytes / (1024L * 1024L);
                                runOnUiThread(() -> status.setText("Установлено файлов: " + c + " • " + mb + " МБ"));
                            }
                        }
                        zin.closeEntry();
                    }
                }
                verifyGame();
                writeMobileConfig();
                int files = count; long mb = bytes / (1024L * 1024L);
                runOnUiThread(() -> {
                    bar.setVisibility(View.GONE);
                    status.setText("Готово. " + files + " файлов • " + mb + " МБ\nHUD, модели, карты и русская локализация проверены.");
                    Button launch = menuButton("Запустить игру");
                    launch.setOnClickListener(v -> launchClient(null));
                    root.addView(launch, marginTop(18));
                    Button back = menuButton("В главное меню");
                    back.setOnClickListener(v -> showMainMenu());
                    root.addView(back, marginTop(8));
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    bar.setVisibility(View.GONE);
                    status.setTextColor(Color.rgb(255,115,105));
                    status.setText("Ошибка установки: " + e.getMessage());
                    Button back = menuButton("Назад");
                    back.setOnClickListener(v -> showMainMenu());
                    root.addView(back, marginTop(18));
                });
            }
        });
    }

    private void verifyGame() throws Exception {
        File c = new File(xashRoot(), "cstrike");
        File v = new File(xashRoot(), "valve");
        requireDir(v, "valve");
        requireDir(c, "cstrike");
        requireFile(new File(c,"liblist.gam"), "cstrike/liblist.gam");
        requireDir(new File(c,"maps"), "cstrike/maps");
        requireDir(new File(c,"models"), "cstrike/models");
        requireDir(new File(c,"sprites"), "cstrike/sprites");
        File hud = new File(c,"sprites/hud.txt");
        requireFile(hud, "cstrike/sprites/hud.txt");
        byte[] data;
        try (FileInputStream in = new FileInputStream(hud)) {
            data = new byte[(int)Math.min(hud.length(), 65536)];
            int n = in.read(data);
            if (n <= 0 || !new String(data,0,n,StandardCharsets.ISO_8859_1).contains("number_0"))
                throw new Exception("hud.txt повреждён: нет number_0");
        }
        File txt = new File(c,"resource/gameui_russian.txt");
        if (!txt.isFile()) throw new Exception("Нет русской локализации gameui_russian.txt");
    }

    private void writeMobileConfig() {
        try {
            File cfg = new File(new File(xashRoot(),"cstrike"), "autoexec.cfg");
            try (FileOutputStream out = new FileOutputStream(cfg, true)) {
                String s = "\n// CS16 Android Final\ncl_advertise_engine_in_name 0\ncl_ticket_generator revemu2013\n";
                out.write(s.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {}
    }

    private void launchClient(String server) {
        if (!gameReady()) {
            Toast.makeText(this, "Сначала установи файлы игры", Toast.LENGTH_LONG).show();
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
            } catch (Exception ignored) {}
        }
        Toast.makeText(this, "Финальный CS16Client не установлен", Toast.LENGTH_LONG).show();
    }

    private String normalizeAddress(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (!s.matches("^[A-Za-z0-9._-]+(:[0-9]{1,5})?$")) return null;
        if (!s.contains(":")) s += ":27015";
        try {
            int p = Integer.parseInt(s.substring(s.lastIndexOf(':') + 1));
            if (p < 1 || p > 65535) return null;
        } catch (Exception e) { return null; }
        return s;
    }

    private boolean gameReady() {
        try { verifyGame(); return true; } catch (Exception e) { return false; }
    }

    private File xashRoot() { return new File(Environment.getExternalStorageDirectory(), "xash"); }

    private boolean anyInstalled(String... pkgs) {
        for (String p : pkgs) try { getPackageManager().getPackageInfo(p,0); return true; } catch (Exception ignored) {}
        return false;
    }

    private void requireDir(File f, String n) throws Exception { if (!f.isDirectory()) throw new Exception("Нет " + n); }
    private void requireFile(File f, String n) throws Exception { if (!f.isFile() || f.length() == 0) throw new Exception("Нет " + n); }

    private void deleteRecursive(File f) throws Exception {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursive(c);
        }
        if (!f.delete() && f.exists()) throw new Exception("Не удалось удалить " + f.getAbsolutePath());
    }

    private LinearLayout.LayoutParams menuLp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        p.bottomMargin = dp(8); return p;
    }

    private LinearLayout.LayoutParams marginTop(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(top); return p;
    }

    private Button menuButton(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(17);
        b.setTextColor(Color.rgb(224,225,210));
        b.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        b.setPadding(dp(16),0,dp(12),0);
        b.setBackground(rounded(Color.rgb(37,42,35), Color.rgb(83,88,72), 5));
        return b;
    }

    private Button smallButton(String s) {
        Button b = menuButton(s);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(14);
        return b;
    }

    private TextView label(String s, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(size); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private GradientDrawable rounded(int fill, int stroke, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill); g.setCornerRadius(dp(radius)); g.setStroke(dp(1), stroke); return g;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private static final class ServerInfo {
        final String address, name, map, folder; final int players, maxPlayers, ping;
        ServerInfo(String a,String n,String m,String f,int p,int mp,int ping){this.address=a;this.name=n;this.map=m;this.folder=f;this.players=p;this.maxPlayers=mp;this.ping=ping;}
    }

    private static final class Cursor {
        final byte[] b; int p; final int end;
        Cursor(byte[] b,int p,int end){this.b=b;this.p=p;this.end=end;}
        String str(){int s=p;while(p<end&&b[p]!=0)p++;String r=new String(b,s,Math.max(0,p-s),StandardCharsets.UTF_8);if(p<end)p++;return r;}
        int u8(){return p<end?(b[p++]&255):0;}
        void skip(int n){p=Math.min(end,p+n);}
    }
}
