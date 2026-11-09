package dev.kot.phonebridge;

import android.app.Activity;
import android.os.Bundle;
import android.os.Build;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.text.InputType;
import android.view.WindowInsets;
import android.widget.*;
import java.util.*;

public final class MainActivity extends Activity {
  private static final int PICK=22;
  private final Map<String,BridgeService.Doc> docs = new LinkedHashMap<>();
  private EditText address,secret;
  private TextView state,selection;
  private Button startButton,stopButton;
  private LinearLayout root;
  @Override public void onCreate(Bundle b){
    super.onCreate(b);
    root=new LinearLayout(this);root.setOrientation(1);root.setPadding(24,64,24,32);
    root.setOnApplyWindowInsetsListener((v,insets)->{
       root.setPadding(24, 12+insets.getSystemWindowInsetTop(),24,16+insets.getSystemWindowInsetBottom());
       return insets;
    });
    TextView header=new TextView(this);header.setText("Мой телефон ↔ ChatGPT");header.setTextSize(22);root.addView(header);
    label("Адрес защищённого сервера (HTTPS)");
    address=new EditText(this);address.setSingleLine(true);
    address.setHint("https://xiaomi-phone-bridge.onrender.com");
    address.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
    root.addView(address);
    label("Секрет устройства (не ключ ChatGPT)");
    secret=new EditText(this);secret.setSingleLine(true);
    secret.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
    root.addView(secret);
    Button choose=new Button(this);choose.setText("ВЫБРАТЬ ФАЙЛЫ");
    choose.setOnClickListener(v->choose());root.addView(choose);
    selection=new TextView(this);selection.setText("Выбрано файлов: 0");root.addView(selection);
    startButton=new Button(this);startButton.setText("ВКЛЮЧИТЬ ДОСТУП");
    startButton.setOnClickListener(v->enable());root.addView(startButton);
    stopButton=new Button(this);stopButton.setText("ОТКЛЮЧИТЬ ДОСТУП");
    stopButton.setOnClickListener(v->disable());root.addView(stopButton);
    state=new TextView(this);root.addView(state);
    label("После включения доступ к выбранным текстовым файлам можно разрешить в фоне. Постоянное уведомление содержит кнопку отключения. Без уведомления фоновая работа не гарантируется.");
    ScrollView scroll=new ScrollView(this);scroll.addView(root);setContentView(scroll);
    if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},105);
    }
    refresh();
  }
  private void label(String text){TextView v=new TextView(this);v.setText(text);v.setPadding(0,12,0,8);root.addView(v);}
  @Override protected void onResume(){super.onResume();refresh();}
  private void refresh(){
    if(state==null)return;
    boolean enabled=BridgeService.isActive();
    startButton.setEnabled(!enabled);stopButton.setEnabled(enabled);
    state.setText(BridgeService.getStatus());
  }
  private void choose(){
    if(BridgeService.isActive()){state.setText("Сначала отключи доступ.");return;}
    Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
    i.setType("*/*");i.addCategory(Intent.CATEGORY_OPENABLE);
    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    startActivityForResult(i,PICK);
  }
  @Override protected void onActivityResult(int request,int result,Intent intent){
    super.onActivityResult(request,result,intent);
    if(request!=PICK||result!=RESULT_OK||intent==null||BridgeService.isActive())return;
    ArrayList<Uri> uris=new ArrayList<>();
    if(intent.getData()!=null)uris.add(intent.getData());
    if(intent.getClipData()!=null)
      for(int n=0;n<intent.getClipData().getItemCount();n++) uris.add(intent.getClipData().getItemAt(n).getUri());
    for(Uri uri:uris){
      if(docs.size()>=30)break;
      try{
        String name="document",mime=getContentResolver().getType(uri);
        long size=-1;
        try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE},null,null,null)){
          if(c!=null&&c.moveToFirst()){
            int n=c.getColumnIndex(OpenableColumns.DISPLAY_NAME),s=c.getColumnIndex(OpenableColumns.SIZE);
            if(n>=0&&!c.isNull(n))name=c.getString(n);
            if(s>=0&&!c.isNull(s))size=c.getLong(s);
          }
        }
        if(mime==null)mime="application/octet-stream";
        String id=UUID.randomUUID().toString();
        docs.put(id,new BridgeService.Doc(id,uri,name.substring(0,Math.min(name.length(),180)),mime.substring(0,Math.min(mime.length(),100)),size));
      }catch(Exception e){state.setText("Ошибка выбора: "+e.getClass().getSimpleName());}
    }
    selection.setText("Выбрано файлов: "+docs.size());
  }
  private void enable(){
    String url=address.getText().toString().trim(),key=secret.getText().toString().trim();
    if(!url.startsWith("https://")||key.length()<32||docs.isEmpty()){
       state.setText("Нужны HTTPS-адрес, секрет 32+ символа и выбранные файлы.");return;
    }
    try{
      BridgeService.enable(this,url,key,new ArrayList<>(docs.values()));
      state.setText("Запуск фонового сеанса...");
      refresh();
    }catch(Exception e){state.setText("Не удалось запустить: "+e.getMessage());}
  }
  private void disable(){
    BridgeService.disable(this);
    state.setText("Отключение доступа...");
    startButton.setEnabled(true);stopButton.setEnabled(false);
  }
}
