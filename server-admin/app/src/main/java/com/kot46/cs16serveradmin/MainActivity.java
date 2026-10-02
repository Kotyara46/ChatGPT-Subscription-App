package com.kot46.cs16serveradmin;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    static final int PICK_FILE = 46;
    static final String PREFS = "kot46_admin";
    static final String KEY_URI = "cloud_uri";
    static final String DEFAULT_LIST =
            "# KoT46 CS16 central favorites v1\n" +
            "46.174.54.149:27015 | |͇̿P͇̿U͇̿B͇̿| АДСКАЯ СТРАНА 18+\n" +
            "62.122.214.56:27015 | |͇̿P͇̿U͇̿B͇̿| РАЙСКАЯ СТРАНА [18+]\n" +
            "62.122.215.73:27015 | [ZM] .:ГНИЛАЯ ПЛОТЬ:. [BiOHAZARD] Night ViP\n" +
            "45.95.31.209:27015 | |͇̿P͇̿U͇̿B͇̿| ПИВНОЙ |18+|\n" +
            "212.76.137.58:27026 | [SAPPHIRE CSDM] СМЕРТЕЛЬНАЯ АРЕНА 18+\n" +
            "45.136.205.159:27015 | [HARD•CS] AutoMix\n";

    EditText editor;
    TextView status;
    SharedPreferences prefs;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(24));
        scroll.addView(root);

        TextView title = text("KoT46 — Центральные серверы", 23);
        title.setGravity(Gravity.CENTER);
        root.addView(title, full());

        TextView hint = text(
                "Это АДМИН-приложение. Оно меняет один файл KoT46-servers.txt на Google Диске. " +
                "У игроков приложение синхронизации автоматически скачивает этот файл и обновляет «Избранное».\n\n" +
                "Формат: IP:PORT | название", 15);
        hint.setPadding(0, dp(12), 0, dp(12));
        root.addView(hint, full());

        editor = new EditText(this);
        editor.setMinLines(10);
        editor.setGravity(Gravity.TOP);
        editor.setText(DEFAULT_LIST);
        root.addView(editor, full());

        Button choose = button("ВЫБРАТЬ KoT46-servers.txt НА GOOGLE ДИСКЕ");
        choose.setOnClickListener(v -> chooseFile());
        root.addView(choose, buttonParams());

        Button load = button("ЗАГРУЗИТЬ ТЕКУЩИЙ СПИСОК ИЗ ОБЛАКА");
        load.setOnClickListener(v -> loadCloud());
        root.addView(load, buttonParams());

        Button save = button("СОХРАНИТЬ ДЛЯ ВСЕХ ИГРОКОВ");
        save.setOnClickListener(v -> saveCloud());
        root.addView(save, buttonParams());

        Button reset = button("ВЕРНУТЬ 6 СЕРВЕРОВ");
        reset.setOnClickListener(v -> editor.setText(DEFAULT_LIST));
        root.addView(reset, buttonParams());

        status = text(prefs.contains(KEY_URI)
                ? "Облачный файл уже выбран. Можно менять список."
                : "Первый раз выбери KoT46-servers.txt из папки Half-Life на Google Диске.", 14);
        status.setPadding(0, dp(12), 0, 0);
        root.addView(status, full());

        setContentView(scroll);
    }

    TextView text(String s, int size) { TextView v = new TextView(this); v.setText(s); v.setTextSize(size); return v; }
    Button button(String s) { Button b = new Button(this); b.setText(s); return b; }
    LinearLayout.LayoutParams full() { return new LinearLayout.LayoutParams(-1, -2); }
    LinearLayout.LayoutParams buttonParams() { LinearLayout.LayoutParams p = full(); p.topMargin = dp(8); return p; }
    int dp(int x) { return Math.round(x * getResources().getDisplayMetrics().density); }

    void chooseFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("text/plain");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, PICK_FILE);
    }

    @Override protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req != PICK_FILE || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception ignored) {}
        prefs.edit().putString(KEY_URI, uri.toString()).apply();
        status.setText("KoT46-servers.txt выбран. Загружаю текущий список...");
        loadCloud();
    }

    Uri cloudUri() {
        String s = prefs.getString(KEY_URI, null);
        return s == null ? null : Uri.parse(s);
    }

    void loadCloud() {
        Uri uri = cloudUri();
        if (uri == null) { status.setText("Сначала выбери облачный KoT46-servers.txt."); chooseFile(); return; }
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("не удалось открыть файл");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            String text = new String(out.toByteArray(), StandardCharsets.UTF_8).replace("\u0000", "");
            editor.setText(text);
            status.setText("Облачный список загружен.");
        } catch (Exception e) {
            status.setText("Ошибка чтения: " + e.getMessage());
        }
    }

    void saveCloud() {
        Uri uri = cloudUri();
        if (uri == null) { status.setText("Сначала выбери облачный KoT46-servers.txt."); chooseFile(); return; }
        String text = editor.getText().toString().trim() + "\n";
        try {
            validate(text);
            try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                if (out == null) throw new IOException("не удалось открыть файл на запись");
                out.write(text.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            status.setText("ГОТОВО. Центральный список обновлён. Игроки получат его при следующей синхронизации.");
            Toast.makeText(this, "Серверы опубликованы для всех", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            status.setText("Ошибка сохранения: " + e.getMessage());
        }
    }

    void validate(String text) {
        String[] lines = text.replace("\r", "").split("\n");
        int count = 0;
        for (int i=0; i<lines.length; i++) {
            String q = lines[i].trim();
            if (q.isEmpty() || q.startsWith("#")) continue;
            int k = q.indexOf('|');
            String addr = (k < 0 ? q : q.substring(0, k)).trim();
            if (!addr.matches("^[A-Za-z0-9.-]+:[0-9]{1,5}$"))
                throw new IllegalArgumentException("Строка " + (i+1) + ": нужен формат IP:PORT | название");
            int port = Integer.parseInt(addr.substring(addr.lastIndexOf(':') + 1));
            if (port < 1 || port > 65535) throw new IllegalArgumentException("Строка " + (i+1) + ": неверный порт");
            count++;
        }
        if (count == 0) throw new IllegalArgumentException("Нужен хотя бы один сервер");
    }
}
