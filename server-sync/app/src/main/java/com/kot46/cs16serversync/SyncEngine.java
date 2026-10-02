package com.kot46.cs16serversync;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import androidx.documentfile.provider.DocumentFile;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;

public final class SyncEngine {
    public static final String PREFS = "kot46_sync";
    public static final String KEY_TREE = "xash_tree";
    public static final String KEY_HASH = "last_hash";
    public static final String KEY_STATUS = "last_status";
    public static final String KEY_TIME = "last_time";
    public static final String REMOTE_URL =
            "https://drive.usercontent.google.com/download?id=1zNEmEf6kgGxZKRER-mlrnsVODvIx7QX_&export=download&confirm=t";

    static final class Server {
        final String address;
        final String name;
        Server(String address, String name) { this.address = address; this.name = name; }
    }

    private SyncEngine() {}

    public static String sync(Context ctx, boolean force) throws Exception {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String tree = p.getString(KEY_TREE, null);
        if (tree == null) throw new IllegalStateException("Сначала выбери папку xash");

        String remote = download();
        List<Server> servers = parse(remote);
        if (servers.isEmpty()) throw new IOException("Центральный список серверов пуст");

        String hash = sha256(remote);
        String old = p.getString(KEY_HASH, "");
        if (!force && hash.equals(old)) {
            String msg = "Без изменений: " + servers.size() + " серверов";
            saveStatus(p, msg);
            return msg;
        }

        DocumentFile root = DocumentFile.fromTreeUri(ctx, Uri.parse(tree));
        if (root == null || !root.canWrite()) throw new IOException("Нет доступа к выбранной папке xash");

        DocumentFile platformConfig = dir(dir(root, "platform"), "config");
        DocumentFile config = dir(root, "config");

        String normal = buildServerBrowser(servers);
        String rev = buildRevServerBrowser(servers);

        put(ctx, platformConfig, "ServerBrowser.vdf", normal);
        put(ctx, platformConfig, "rev_ServerBrowser.vdf", rev);
        put(ctx, config, "ServerBrowser.vdf", normal);
        put(ctx, config, "rev_ServerBrowser.vdf", rev);

        String msg = "Обновлено: " + servers.size() + " серверов";
        p.edit().putString(KEY_HASH, hash).apply();
        saveStatus(p, msg);
        return msg;
    }

    static void saveStatus(SharedPreferences p, String status) {
        String time = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(new Date());
        p.edit().putString(KEY_STATUS, status).putString(KEY_TIME, time).apply();
    }

    static String download() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(REMOTE_URL).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) KoT46-CS16-Sync/1.0");
        c.setRequestProperty("Accept", "text/plain,*/*");
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) throw new IOException("Google Drive HTTP " + code);
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            String s = new String(out.toByteArray(), StandardCharsets.UTF_8).replace("\u0000", "");
            if (s.trim().isEmpty()) throw new IOException("Пустой ответ от сервера");
            return s;
        } finally {
            c.disconnect();
        }
    }

    static List<Server> parse(String text) {
        List<Server> out = new ArrayList<>();
        String[] lines = text.replace("\r", "").split("\n");
        for (int i=0; i<lines.length; i++) {
            String q = lines[i].trim();
            if (q.isEmpty() || q.startsWith("#")) continue;
            int k = q.indexOf('|');
            String address = (k < 0 ? q : q.substring(0, k)).trim();
            String name = (k < 0 ? address : q.substring(k + 1)).trim();
            if (name.isEmpty()) name = address;
            if (!address.matches("^[A-Za-z0-9.-]+:[0-9]{1,5}$"))
                throw new IllegalArgumentException("Неверная строка " + (i+1) + " в центральном списке");
            int port = Integer.parseInt(address.substring(address.lastIndexOf(':') + 1));
            if (port < 1 || port > 65535)
                throw new IllegalArgumentException("Неверный порт в строке " + (i+1));
            out.add(new Server(address, name));
        }
        return out;
    }

    static DocumentFile dir(DocumentFile parent, String name) throws Exception {
        DocumentFile f = parent.findFile(name);
        if (f != null && f.isDirectory()) return f;
        f = parent.createDirectory(name);
        if (f == null) throw new IOException("Не удалось создать " + name);
        return f;
    }

    static void put(Context ctx, DocumentFile dir, String name, String text) throws Exception {
        DocumentFile f = dir.findFile(name);
        if (f == null) f = dir.createFile("application/octet-stream", name);
        if (f == null) throw new IOException("Не удалось создать " + name);
        try (OutputStream out = ctx.getContentResolver().openOutputStream(f.getUri(), "wt")) {
            if (out == null) throw new IOException("Не удалось открыть " + name);
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.write(0);
            out.flush();
        }
    }

    static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    static String entries(List<Server> servers, String indent) {
        StringBuilder b = new StringBuilder();
        for (int i=0; i<servers.size(); i++) {
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

    static String buildRevServerBrowser(List<Server> servers) {
        return "\"filters\"\n{\n" +
                "\t\"favorites\"\n\t{\n" +
                entries(servers, "\t\t") +
                "\t}\n\t\"history\"\n\t{\n\t}\n}\n";
    }

    static String buildServerBrowser(List<Server> servers) {
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

    static String sha256(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder b = new StringBuilder();
        for (byte x : d) b.append(String.format(Locale.US, "%02x", x));
        return b.toString();
    }
}
