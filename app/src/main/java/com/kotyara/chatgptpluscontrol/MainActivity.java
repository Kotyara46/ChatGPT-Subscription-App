package com.kotyara.chatgptpluscontrol;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.speech.RecognizerIntent;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS = "gpt_direct";
    private static final String KEY_MODE = "mode";
    private static final String KEY_PROXY_URL = "proxy_url";
    private static final String KEY_PROXY_TOKEN = "proxy_token";
    private static final String KEY_MODEL = "model";
    private static final String KEY_REASONING = "reasoning";
    private static final String KEY_WEB_SEARCH = "web_search";
    private static final String KEY_PREVIOUS_RESPONSE = "previous_response_id";
    private static final String KEY_HISTORY = "history";

    private static final int REQUEST_IMAGE = 201;
    private static final int REQUEST_SPEECH = 202;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ArrayList<Message> messages = new ArrayList<>();

    private SharedPreferences prefs;
    private LinearLayout chatContainer;
    private ScrollView chatScroll;
    private EditText inputMessage;
    private TextView connectionText;
    private TextView modelLabel;
    private View attachmentCard;
    private ImageView attachmentPreview;
    private TextView attachmentName;
    private MaterialButton btnSend;

    private Uri pendingImageUri;
    private boolean sending = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        chatContainer = findViewById(R.id.chatContainer);
        chatScroll = findViewById(R.id.chatScroll);
        inputMessage = findViewById(R.id.inputMessage);
        connectionText = findViewById(R.id.connectionText);
        modelLabel = findViewById(R.id.modelLabel);
        attachmentCard = findViewById(R.id.attachmentCard);
        attachmentPreview = findViewById(R.id.attachmentPreview);
        attachmentName = findViewById(R.id.attachmentName);
        btnSend = findViewById(R.id.btnSend);

        btnSend.setOnClickListener(v -> sendMessage());
        findViewById(R.id.btnSettings).setOnClickListener(v -> showSettingsDialog());
        findViewById(R.id.btnNewChat).setOnClickListener(v -> confirmNewChat());
        findViewById(R.id.btnAttach).setOnClickListener(v -> pickImage());
        findViewById(R.id.btnMic).setOnClickListener(v -> startVoiceInput());
        findViewById(R.id.btnRemoveAttachment).setOnClickListener(v -> clearAttachment());

        loadHistory();
        if (messages.isEmpty()) addWelcome();
        else renderAllMessages();
        refreshHeader();

        if (!isConfigured()) chatScroll.postDelayed(this::showFirstRunDialog, 350);
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void showFirstRunDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Подключение к GPT-5.6")
                .setMessage("Теперь это настоящий AI-клиент, а не оболочка со ссылками. Для работы нужен OpenAI API. Самый быстрый личный вариант — ввести свой API-ключ. Безопаснее — подключить сервер-посредник в настройках.")
                .setPositiveButton("Настроить", (d, w) -> showSettingsDialog())
                .setNegativeButton("Позже", null)
                .show();
    }

    private void refreshHeader() {
        String model = prefs.getString(KEY_MODEL, "gpt-5.6");
        modelLabel.setText(prettyModel(model));
        String mode = prefs.getString(KEY_MODE, "direct");
        if ("proxy".equals(mode)) {
            String url = prefs.getString(KEY_PROXY_URL, "");
            boolean ready = url != null && !url.trim().isEmpty();
            connectionText.setText(ready ? "Защищённый сервер • готово" : "Сервер не настроен");
            connectionText.setTextColor(ContextCompat.getColor(this, ready ? R.color.success : R.color.warning));
        } else {
            boolean hasKey = !SecureStore.getApiKey(this).isEmpty();
            connectionText.setText(hasKey ? "OpenAI API • прямое подключение" : "API-ключ не задан");
            connectionText.setTextColor(ContextCompat.getColor(this, hasKey ? R.color.success : R.color.warning));
        }
    }

    private boolean isConfigured() {
        String mode = prefs.getString(KEY_MODE, "direct");
        if ("proxy".equals(mode)) {
            String url = prefs.getString(KEY_PROXY_URL, "");
            return url != null && !url.trim().isEmpty();
        }
        return !SecureStore.getApiKey(this).isEmpty();
    }

    private void showSettingsDialog() {
        int pad = (int) dp(20);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, 0, pad, 0);

        TextView note = new TextView(this);
        note.setText("Прямой режим работает сразу с OpenAI API, но ключ находится на телефоне. Для постоянного использования безопаснее серверный режим: API-ключ хранится только на сервере.");
        note.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        note.setTextSize(13);
        box.addView(note, fullWidth(0, 10));

        MaterialSwitch proxySwitch = new MaterialSwitch(this);
        proxySwitch.setText("Использовать защищённый сервер");
        proxySwitch.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        proxySwitch.setChecked("proxy".equals(prefs.getString(KEY_MODE, "direct")));
        box.addView(proxySwitch, fullWidth(8, 4));

        EditText apiKeyInput = makeEditText("OpenAI API key (sk-...)");
        apiKeyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        String existingKey = SecureStore.getApiKey(this);
        if (!existingKey.isEmpty()) apiKeyInput.setHint("Ключ уже сохранён • введите новый для замены");
        box.addView(apiKeyInput, fullWidth(6, 4));

        EditText proxyUrlInput = makeEditText("URL сервера, например https://.../api/chat");
        proxyUrlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        proxyUrlInput.setText(prefs.getString(KEY_PROXY_URL, ""));
        box.addView(proxyUrlInput, fullWidth(6, 4));

        EditText proxyTokenInput = makeEditText("Токен приложения (если сервер его требует)");
        proxyTokenInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        proxyTokenInput.setText(prefs.getString(KEY_PROXY_TOKEN, ""));
        box.addView(proxyTokenInput, fullWidth(6, 4));

        box.addView(label("Модель"), fullWidth(12, 2));
        Spinner modelSpinner = new Spinner(this);
        String[] modelLabels = {"GPT-5.6 Sol — максимум качества", "GPT-5.6 Terra — баланс", "GPT-5.6 Luna — дешевле и быстрее"};
        String[] modelValues = {"gpt-5.6", "gpt-5.6-terra", "gpt-5.6-luna"};
        modelSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, modelLabels));
        modelSpinner.setSelection(indexOf(modelValues, prefs.getString(KEY_MODEL, "gpt-5.6")));
        box.addView(modelSpinner, fullWidth(0, 4));

        box.addView(label("Глубина рассуждения"), fullWidth(10, 2));
        Spinner reasoningSpinner = new Spinner(this);
        String[] reasoningLabels = {"Без доп. рассуждения", "Низкая", "Средняя", "Высокая"};
        String[] reasoningValues = {"none", "low", "medium", "high"};
        reasoningSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, reasoningLabels));
        reasoningSpinner.setSelection(indexOf(reasoningValues, prefs.getString(KEY_REASONING, "medium")));
        box.addView(reasoningSpinner, fullWidth(0, 4));

        CheckBox webSearch = new CheckBox(this);
        webSearch.setText("Разрешить встроенный поиск в интернете");
        webSearch.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        webSearch.setChecked(prefs.getBoolean(KEY_WEB_SEARCH, true));
        box.addView(webSearch, fullWidth(8, 0));

        Runnable updateVisibility = () -> {
            boolean proxy = proxySwitch.isChecked();
            apiKeyInput.setVisibility(proxy ? View.GONE : View.VISIBLE);
            proxyUrlInput.setVisibility(proxy ? View.VISIBLE : View.GONE);
            proxyTokenInput.setVisibility(proxy ? View.VISIBLE : View.GONE);
        };
        proxySwitch.setOnCheckedChangeListener((buttonView, isChecked) -> updateVisibility.run());
        updateVisibility.run();

        ScrollView wrapper = new ScrollView(this);
        wrapper.addView(box);

        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Настройки подключения")
                .setView(wrapper)
                .setNegativeButton("Отмена", null)
                .setNeutralButton("Где взять API-ключ", (d, w) -> openUrl("https://platform.openai.com/api-keys"))
                .setPositiveButton("Сохранить", null)
                .create();

        dialog.setOnShowListener(v -> dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(btn -> {
            try {
                boolean proxy = proxySwitch.isChecked();
                if (proxy) {
                    String url = proxyUrlInput.getText().toString().trim();
                    if (!url.startsWith("https://")) {
                        Toast.makeText(this, "Для сервера нужен HTTPS URL", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    prefs.edit()
                            .putString(KEY_MODE, "proxy")
                            .putString(KEY_PROXY_URL, url)
                            .putString(KEY_PROXY_TOKEN, proxyTokenInput.getText().toString().trim())
                            .putString(KEY_MODEL, modelValues[modelSpinner.getSelectedItemPosition()])
                            .putString(KEY_REASONING, reasoningValues[reasoningSpinner.getSelectedItemPosition()])
                            .putBoolean(KEY_WEB_SEARCH, webSearch.isChecked())
                            .apply();
                } else {
                    String enteredKey = apiKeyInput.getText().toString().trim();
                    if (!enteredKey.isEmpty()) SecureStore.saveApiKey(this, enteredKey);
                    if (SecureStore.getApiKey(this).isEmpty()) {
                        Toast.makeText(this, "Введите API-ключ", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    prefs.edit()
                            .putString(KEY_MODE, "direct")
                            .putString(KEY_MODEL, modelValues[modelSpinner.getSelectedItemPosition()])
                            .putString(KEY_REASONING, reasoningValues[reasoningSpinner.getSelectedItemPosition()])
                            .putBoolean(KEY_WEB_SEARCH, webSearch.isChecked())
                            .apply();
                }
                refreshHeader();
                dialog.dismiss();
                Toast.makeText(this, "Подключение сохранено", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Не удалось сохранить ключ: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }));
        dialog.show();
    }

    private EditText makeEditText(String hint) {
        EditText edit = new EditText(this);
        edit.setHint(hint);
        edit.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        edit.setHintTextColor(ContextCompat.getColor(this, R.color.muted));
        edit.setTextSize(14);
        edit.setSingleLine(true);
        return edit;
    }

    private TextView label(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        view.setTextSize(13);
        view.setTypeface(null, android.graphics.Typeface.BOLD);
        return view;
    }

    private LinearLayout.LayoutParams fullWidth(int topDp, int bottomDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) dp(topDp);
        lp.bottomMargin = (int) dp(bottomDp);
        return lp;
    }

    private int indexOf(String[] values, String value) {
        if (value == null) return 0;
        for (int i = 0; i < values.length; i++) if (value.equals(values[i])) return i;
        return 0;
    }

    private void sendMessage() {
        if (sending) return;
        String text = inputMessage.getText() == null ? "" : inputMessage.getText().toString().trim();
        if (text.isEmpty() && pendingImageUri == null) return;
        if (!isConfigured()) {
            Toast.makeText(this, "Сначала настройте подключение к OpenAI API", Toast.LENGTH_LONG).show();
            showSettingsDialog();
            return;
        }

        final Uri imageUri = pendingImageUri;
        String visibleText = text.isEmpty() ? "📷 Изображение" : text + (imageUri != null ? "\n\n📷 Изображение прикреплено" : "");
        addMessage("user", visibleText, true);
        inputMessage.setText("");
        clearAttachment();
        setSending(true);

        executor.execute(() -> {
            try {
                String imageData = imageUri == null ? null : encodeImage(imageUri);
                JSONObject request = buildRequest(text.isEmpty() ? "Опиши и проанализируй это изображение." : text, imageData);
                JSONObject response = callModel(request);
                String answer = extractText(response);
                String responseId = response.optString("id", "");
                if (!responseId.isEmpty()) prefs.edit().putString(KEY_PREVIOUS_RESPONSE, responseId).apply();
                if (answer.trim().isEmpty()) answer = "Модель вернула ответ без текстового содержимого.";
                final String finalAnswer = answer;
                runOnUiThread(() -> {
                    addMessage("assistant", finalAnswer, true);
                    setSending(false);
                });
            } catch (Exception e) {
                String message = friendlyError(e.getMessage());
                runOnUiThread(() -> {
                    addMessage("error", message, false);
                    setSending(false);
                });
            }
        });
    }

    private JSONObject buildRequest(String text, String imageData) throws Exception {
        JSONObject root = new JSONObject();
        root.put("model", prefs.getString(KEY_MODEL, "gpt-5.6"));
        root.put("store", true);
        root.put("instructions", "Ты ChatGPT. Отвечай на языке пользователя. Будь точным, полезным и прямым. Если пользователь пишет по-русски — отвечай по-русски. Форматируй код и списки читабельно.");

        String previous = prefs.getString(KEY_PREVIOUS_RESPONSE, "");
        if (previous != null && !previous.isEmpty()) root.put("previous_response_id", previous);

        JSONObject reasoningObject = new JSONObject();
        reasoningObject.put("effort", prefs.getString(KEY_REASONING, "medium"));
        root.put("reasoning", reasoningObject);

        if (prefs.getBoolean(KEY_WEB_SEARCH, true)) {
            JSONArray tools = new JSONArray();
            tools.put(new JSONObject().put("type", "web_search"));
            root.put("tools", tools);
        }

        JSONArray content = new JSONArray();
        content.put(new JSONObject().put("type", "input_text").put("text", text));
        if (imageData != null && !imageData.isEmpty()) {
            content.put(new JSONObject().put("type", "input_image").put("image_url", imageData));
        }
        root.put("input", new JSONArray().put(new JSONObject().put("role", "user").put("content", content)));
        return root;
    }

    private JSONObject callModel(JSONObject request) throws Exception {
        String mode = prefs.getString(KEY_MODE, "direct");
        String endpoint;
        String key = "";
        String proxyToken = "";
        if ("proxy".equals(mode)) {
            endpoint = prefs.getString(KEY_PROXY_URL, "");
            proxyToken = prefs.getString(KEY_PROXY_TOKEN, "");
        } else {
            endpoint = "https://api.openai.com/v1/responses";
            key = SecureStore.getApiKey(this);
        }
        if (endpoint == null || endpoint.isEmpty()) throw new Exception("Адрес API не настроен");

        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(30_000);
        connection.setReadTimeout(180_000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Accept", "application/json");
        if (!key.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + key);
        if (proxyToken != null && !proxyToken.isEmpty()) connection.setRequestProperty("X-App-Token", proxyToken);

        byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(body.length);
        try (OutputStream out = connection.getOutputStream()) {
            out.write(body);
        }

        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
        String responseText = readAll(stream);
        connection.disconnect();

        if (code < 200 || code >= 300) {
            String details = responseText;
            try {
                JSONObject err = new JSONObject(responseText);
                JSONObject error = err.optJSONObject("error");
                if (error != null) details = error.optString("message", responseText);
            } catch (Exception ignored) {}
            throw new Exception("HTTP " + code + ": " + details);
        }
        return new JSONObject(responseText);
    }

    private String extractText(JSONObject response) {
        StringBuilder result = new StringBuilder();
        JSONArray output = response.optJSONArray("output");
        if (output == null) return "";
        for (int i = 0; i < output.length(); i++) {
            JSONObject item = output.optJSONObject(i);
            if (item == null || !"message".equals(item.optString("type"))) continue;
            JSONArray content = item.optJSONArray("content");
            if (content == null) continue;
            for (int j = 0; j < content.length(); j++) {
                JSONObject part = content.optJSONObject(j);
                if (part != null && "output_text".equals(part.optString("type"))) {
                    if (result.length() > 0) result.append("\n");
                    result.append(part.optString("text", ""));
                }
            }
        }
        return result.toString();
    }

    private String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line).append('\n');
        }
        return result.toString();
    }

    private String encodeImage(Uri uri) throws Exception {
        String mime = getContentResolver().getType(uri);
        if (mime == null || mime.isEmpty()) mime = "image/jpeg";
        try (InputStream in = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) throw new Exception("Не удалось открыть изображение");
            byte[] buffer = new byte[8192];
            int read;
            int total = 0;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > 15 * 1024 * 1024) throw new Exception("Изображение больше 15 МБ");
                out.write(buffer, 0, read);
            }
            return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(out.toByteArray());
        }
    }

    private String friendlyError(String raw) {
        if (raw == null || raw.isEmpty()) return "Не удалось получить ответ.";
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.contains("401") || lower.contains("invalid api key") || lower.contains("incorrect api key")) return "Ошибка авторизации API. Проверь API-ключ в настройках.";
        if (lower.contains("429") || lower.contains("quota") || lower.contains("billing")) return "OpenAI API отклонил запрос из-за лимита или баланса. Проверь API Billing и лимиты проекта.";
        if (lower.contains("timeout") || lower.contains("timed out")) return "Ответ не успел прийти. Повтори запрос.";
        return "Ошибка: " + raw;
    }

    private void setSending(boolean value) {
        sending = value;
        btnSend.setEnabled(!value);
        btnSend.setText(value ? "…" : "➤");
        if (value) connectionText.setText("GPT думает…");
        else refreshHeader();
    }

    private void addWelcome() {
        addMessage("assistant", "Готов к работе. Это нативный чат: сообщения уходят напрямую в OpenAI API и ответы показываются здесь. Нажми ⚙, подключи API и пиши мне без браузера.", false);
    }

    private void addMessage(String role, String text, boolean persist) {
        Message message = new Message(role, text);
        messages.add(message);
        renderMessage(message);
        if (persist) saveHistory();
    }

    private void renderAllMessages() {
        chatContainer.removeAllViews();
        for (Message message : messages) renderMessage(message);
    }

    private void renderMessage(Message message) {
        boolean user = "user".equals(message.role);
        boolean error = "error".equals(message.role);

        LinearLayout bubble = new LinearLayout(this);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setPadding((int) dp(14), (int) dp(10), (int) dp(14), (int) dp(11));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(18));
        if (user) bg.setColor(Color.rgb(37, 99, 235));
        else if (error) bg.setColor(Color.rgb(82, 33, 37));
        else bg.setColor(ContextCompat.getColor(this, R.color.card_bg));
        bg.setStroke((int) dp(1), user ? Color.rgb(59, 130, 246) : ContextCompat.getColor(this, R.color.card_stroke));
        bubble.setBackground(bg);

        TextView who = new TextView(this);
        who.setText(user ? "Вы" : error ? "Ошибка" : prettyModel(prefs.getString(KEY_MODEL, "gpt-5.6")));
        who.setTextColor(user ? Color.rgb(219, 234, 254) : error ? ContextCompat.getColor(this, R.color.danger) : ContextCompat.getColor(this, R.color.accent));
        who.setTextSize(11);
        who.setTypeface(null, android.graphics.Typeface.BOLD);
        bubble.addView(who);

        TextView body = new TextView(this);
        body.setText(message.text);
        body.setTextColor(Color.WHITE);
        body.setTextSize(15);
        body.setTextIsSelectable(true);
        body.setLineSpacing(0, 1.12f);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bodyLp.topMargin = (int) dp(4);
        bubble.addView(body, bodyLp);

        bubble.setOnLongClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("GPT Direct", message.text));
            Toast.makeText(this, "Текст скопирован", Toast.LENGTH_SHORT).show();
            return true;
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) dp(5);
        lp.bottomMargin = (int) dp(5);
        if (user) lp.leftMargin = (int) dp(46);
        else lp.rightMargin = (int) dp(46);
        chatContainer.addView(bubble, lp);
        scrollToBottom();
    }

    private void scrollToBottom() {
        chatScroll.post(() -> chatScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void confirmNewChat() {
        if (messages.size() <= 1) {
            newChat();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("Новый чат?")
                .setMessage("Текущая локальная история будет очищена.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Новый чат", (d, w) -> newChat())
                .show();
    }

    private void newChat() {
        messages.clear();
        prefs.edit().remove(KEY_PREVIOUS_RESPONSE).remove(KEY_HISTORY).apply();
        chatContainer.removeAllViews();
        addWelcome();
    }

    private void saveHistory() {
        try {
            JSONArray array = new JSONArray();
            int start = Math.max(0, messages.size() - 80);
            for (int i = start; i < messages.size(); i++) {
                Message m = messages.get(i);
                if ("error".equals(m.role)) continue;
                array.put(new JSONObject().put("role", m.role).put("text", m.text));
            }
            prefs.edit().putString(KEY_HISTORY, array.toString()).apply();
        } catch (Exception ignored) {}
    }

    private void loadHistory() {
        String raw = prefs.getString(KEY_HISTORY, "");
        if (raw == null || raw.isEmpty()) return;
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.optJSONObject(i);
                if (obj != null) messages.add(new Message(obj.optString("role", "assistant"), obj.optString("text", "")));
            }
        } catch (Exception ignored) {}
    }

    private void pickImage() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, REQUEST_IMAGE);
    }

    private void startVoiceInput() {
        try {
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
            intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Говорите");
            startActivityForResult(intent, REQUEST_SPEECH);
        } catch (Exception e) {
            Toast.makeText(this, "Голосовой ввод недоступен на этом устройстве", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == REQUEST_IMAGE) {
            Uri uri = data.getData();
            if (uri != null) {
                try {
                    getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {}
                pendingImageUri = uri;
                attachmentPreview.setImageURI(uri);
                attachmentName.setText(getDisplayName(uri));
                attachmentCard.setVisibility(View.VISIBLE);
            }
        } else if (requestCode == REQUEST_SPEECH) {
            ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (results != null && !results.isEmpty()) {
                String current = inputMessage.getText() == null ? "" : inputMessage.getText().toString();
                inputMessage.setText(current.isEmpty() ? results.get(0) : current + " " + results.get(0));
                inputMessage.setSelection(inputMessage.length());
            }
        }
    }

    private String getDisplayName(Uri uri) {
        try (android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return cursor.getString(index);
            }
        } catch (Exception ignored) {}
        return "Изображение готово к отправке";
    }

    private void clearAttachment() {
        pendingImageUri = null;
        attachmentPreview.setImageDrawable(null);
        attachmentCard.setVisibility(View.GONE);
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show();
        }
    }

    private String prettyModel(String model) {
        if (model == null) return "GPT-5.6";
        if (model.contains("luna")) return "GPT-5.6 Luna";
        if (model.contains("terra")) return "GPT-5.6 Terra";
        return "GPT-5.6 Sol";
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private static final class Message {
        final String role;
        final String text;
        Message(String role, String text) {
            this.role = role;
            this.text = text == null ? "" : text;
        }
    }
}
