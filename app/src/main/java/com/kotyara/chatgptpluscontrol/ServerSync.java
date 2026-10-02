package com.kotyara.chatgptpluscontrol;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.os.Environment;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public final class ServerSync {
    private static final int JOB_ID = 46013;
    private static final long PERIOD_MS = 15L * 60L * 1000L;
    private static final String REMOTE_URL =
            "https://drive.usercontent.google.com/download?id=1zNEmEf6kgGxZKRER-mlrnsVODvIx7QX_&export=download&confirm=t";

    private ServerSync() {}

    static final class Server {
        final String address;
        final String name;
        Server(String address, String name) {
            this.address = address;
            this.name = name;
        }
    }

    public static void schedule(Context context) {
        try {
            JobScheduler js = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            JobInfo job = new JobInfo.Builder(
                    JOB_ID,
                    new ComponentName(context, ServerSyncJob.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .setPeriodic(PERIOD_MS)
                    .build();
            js.schedule(job);
        } catch (Exception ignored) {
        }
    }

    public static String syncNow(Context context) throws Exception {
        if (!hasFileAccess()) return "нет доступа к памяти";

        File xash = new File(Environment.getExternalStorageDirectory(), "xash");
        File cstrike = new File(xash, "cstrike");
        File valve = new File(xash, "valve");
        if (!cstrike.isDirectory() || !valve.isDirectory()) return "игра ещё не установлена";

        String remote = download();
        List<Server> servers = parse(remote);
        if (servers.isEmpty()) throw new IOException("центральный список серверов пуст");

        restoreRussianUi(context, cstrike, valve);

        File platformConfig = new File(xash, "platform/config");
        File config = new File(xash, "config");
        if (!platformConfig.exists() && !platformConfig.mkdirs())
            throw new IOException("не удалось создать platform/config");
        if (!config.exists() && !config.mkdirs())
            throw new IOException("не удалось создать config");

        String normal = buildServerBrowser(servers);
        String rev = buildRevServerBrowser(servers);
        writeTextWithNull(new File(platformConfig, "ServerBrowser.vdf"), normal);
        writeTextWithNull(new File(platformConfig, "rev_ServerBrowser.vdf"), rev);
        writeTextWithNull(new File(config, "ServerBrowser.vdf"), normal);
        writeTextWithNull(new File(config, "rev_ServerBrowser.vdf"), rev);

        return "обновлено серверов: " + servers.size();
    }

    private static boolean hasFileAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            return Environment.isExternalStorageManager();
        return Environment.getExternalStorageDirectory().canWrite();
    }

    private static String download() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(REMOTE_URL).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) KoT46-CS16-Installer/13");
        c.setRequestProperty("Accept", "text/plain,*/*");
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) throw new IOException("Google Drive HTTP " + code);
        try (InputStream in = c.getInputStream();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            String s = new String(out.toByteArray(), StandardCharsets.UTF_8).replace("\u0000", "");
            if (s.trim().isEmpty()) throw new IOException("пустой ответ центрального списка");
            return s;
        } finally {
            c.disconnect();
        }
    }

    private static List<Server> parse(String text) {
        List<Server> out = new ArrayList<>();
        String[] lines = text.replace("\r", "").split("\n");
        for (int i = 0; i < lines.length; i++) {
            String q = lines[i].trim();
            if (q.isEmpty() || q.startsWith("#")) continue;
            int k = q.indexOf('|');
            String address = (k < 0 ? q : q.substring(0, k)).trim();
            String name = (k < 0 ? address : q.substring(k + 1)).trim();
            if (name.isEmpty()) name = address;
            if (!address.matches("^[A-Za-z0-9.-]+:[0-9]{1,5}$"))
                throw new IllegalArgumentException("неверная строка " + (i + 1));
            int port = Integer.parseInt(address.substring(address.lastIndexOf(':') + 1));
            if (port < 1 || port > 65535)
                throw new IllegalArgumentException("неверный порт в строке " + (i + 1));
            out.add(new Server(address, name));
        }
        return out;
    }

    private static void restoreRussianUi(Context context, File cstrike, File valve) throws Exception {
        File cRes = new File(cstrike, "resource");
        File vRes = new File(valve, "resource");
        if (!cRes.exists() && !cRes.mkdirs()) throw new IOException("не удалось создать cstrike/resource");
        if (!vRes.exists() && !vRes.mkdirs()) throw new IOException("не удалось создать valve/resource");

        copyAsset(context, "russian_ui/GameMenu.res", new File(cRes, "GameMenu.res"));
        copyAsset(context, "russian_ui/gameui_russian.txt", new File(cRes, "gameui_russian.txt"));
        copyAsset(context, "russian_ui/serverbrowser_russian.txt", new File(cRes, "serverbrowser_russian.txt"));
        copyAsset(context, "russian_ui/vgui_russian.txt", new File(cRes, "vgui_russian.txt"));
        copyAsset(context, "russian_ui/valve_russian.txt", new File(vRes, "valve_russian.txt"));
    }

    private static void copyAsset(Context context, String asset, File dest) throws Exception {
        try (InputStream in = context.getAssets().open(asset);
             FileOutputStream out = new FileOutputStream(dest, false)) {
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            out.flush();
        }
    }

    private static void writeTextWithNull(File f, String text) throws Exception {
        try (FileOutputStream out = new FileOutputStream(f, false)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.write(0);
            out.flush();
        }
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String entries(List<Server> servers, String indent) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < servers.size(); i++) {
            Server s = servers.get(i);
            b.append(indent).append('"').append(i).append("\"\n");
            b.append(indent).append("{\n");
            b.append(indent).append("\t\"name\"\t\t\"").append(esc(s.name)).append("\"\n");
            b.append(indent).append("\t\"address\"\t\t\"").append(esc(s.address)).append("\"\n");
            b.append(indent).append("\t\"lastplayed\"\t\t\"0\"\n");
            b.append(indent).append("\t\"appID\"\t\t\"10\"\n");
            b.append(indent).append("\t\"gamedir\"\t\t\"cstrike\"\n");
            b.append(indent).append("}\n");
        }
        return b.toString();
    }

    private static String buildRevServerBrowser(List<Server> servers) {
        return "\"filters\"\n{\n" +
                "\t\"favorites\"\n\t{\n" +
                entries(servers, "\t\t") +
                "\t}\n\t\"history\"\n\t{\n\t}\n}\n";
    }

    private static String buildServerBrowser(List<Server> servers) {
        StringBuilder b = new StringBuilder();
        b.append("\"Filters\"\n{\n");
        b.append("\t\"gamelist\"\t\t\"favorites\"\n");
        b.append("\t\"favorites\"\n\t{\n");
        b.append(entries(servers, "\t\t"));
        b.append("\t}\n\t\"history\"\n\t{\n\t}\n");
        b.append("\t\"Filters\"\n\t{\n");
        String[] groups = {"InternetGames","SpectateGames","FavoriteGames","LanGames","FriendsGames","HistoryGames"};
        for (String g : groups) {
            b.append("\t\t\"").append(g).append("\"\n\t\t{\n");
            b.append("\t\t\t\"ping\"\t\t\"0\"\n");
            b.append("\t\t\t\"NoFull\"\t\t\"0\"\n");
            b.append("\t\t\t\"NoEmpty\"\t\t\"0\"\n");
            b.append("\t\t\t\"NoPassword\"\t\t\"0\"\n");
            b.append("\t\t\t\"ValidSteamAccount\"\t\t\"0\"\n");
            b.append("\t\t\t\"secure\"\t\t\"0\"\n");
            b.append("\t\t}\n");
        }
        b.append("\t}\n}\n");
        return b.toString();
    }
}
