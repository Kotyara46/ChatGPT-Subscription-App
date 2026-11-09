package dev.kot.phonebridge;
import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.database.Cursor;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
  private static final int PICK=22, LIMIT=262144;
  private final Map<String,Doc> docs=new LinkedHashMap<>();
  private final ScheduledExecutorService executor=Executors.newSingleThreadScheduledExecutor();
  private ScheduledFuture<?> polling;
  private volatile boolean active=false;
  private volatile String session="";
  private String endpoint="", token="";
  private EditText address,secret;
  private TextView state,selection;
  private Button startButton,stopButton;
  private static final class Doc {
    final String id; final Uri uri; final String name,mime; final long size;
    Doc(String i,Uri u,String n,String m,long s){id=i;uri=u;name=n;mime=m;size=s;}
  }
  @Override public void onCreate(Bundle b){
    super.onCreate(b);
    LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(32,32,32,32);
    TextView header=new TextView(this);header.setText("Мой телефон ↔ ChatGPT");header.setTextSize(22);root.addView(header);
    label(root,"Адрес защищённого сервера (https://...)");
    address=new EditText(this);address.setSingleLine(true);address.setHint("https://example.com");
    address.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);root.addView(address);
    label(root,"Секрет устройства — вводится только здесь");
    secret=new EditText(this);secret.setSingleLine(true);
    secret.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
    root.addView(secret);
    Button choose=new Button(this);choose.setText("Выбрать файлы");
    choose.setOnClickListener(v->choose());root.addView(choose);
    selection=new TextView(this);selection.setText("Выбрано файлов: 0");root.addView(selection);
    startButton=new Button(this);startButton.setText("ВКЛЮЧИТЬ ДОСТУП");
    startButton.setOnClickListener(v->start());root.addView(startButton);
    stopButton=new Button(this);stopButton.setText("ОТКЛЮЧИТЬ ДОСТУП");
    stopButton.setEnabled(false);stopButton.setOnClickListener(v->disconnect());root.addView(stopButton);
    state=new TextView(this);state.setText("Доступ выключен.");root.addView(state);
    label(root,"Приложение передаёт только выбранные текстовые файлы до 256 КБ. При выходе из приложения доступ отключается. Никакого скрытого доступа.");
    ScrollView scroll=new ScrollView(this);scroll.addView(root);setContentView(scroll);
  }
  private void label(LinearLayout root,String text){TextView v=new TextView(this);v.setText(text);v.setPadding(0,12,0,8);root.addView(v);}
  private void show(String text){runOnUiThread(()->state.setText(text));}
  private void buttons(boolean online){runOnUiThread(()->{startButton.setEnabled(!online);stopButton.setEnabled(online);});}
  private void choose(){
    if(active){show("Сначала отключи доступ.");return;}
    Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("*/*");
    i.addCategory(Intent.CATEGORY_OPENABLE);i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivityForResult(i,PICK);
  }
  @Override protected void onActivityResult(int request,int result,Intent intent){
    super.onActivityResult(request,result,intent);
    if(request!=PICK||result!=RESULT_OK||intent==null||active)return;
    ArrayList<Uri> uris=new ArrayList<>();
    if(intent.getData()!=null)uris.add(intent.getData());
    if(intent.getClipData()!=null)for(int n=0;n<intent.getClipData().getItemCount();n++)
      uris.add(intent.getClipData().getItemAt(n).getUri());
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
        docs.put(UUID.randomUUID().toString(),new Doc(UUID.randomUUID().toString(),uri,name.substring(0,Math.min(name.length(),180)),mime.substring(0,Math.min(mime.length(),100)),size));
      }catch(Exception e){show("Не удалось выбрать один из файлов.");}
    }
    selection.setText("Выбрано файлов: "+docs.size()+"\n"+String.join("\n",names()));
  }
  private List<String> names(){List<String> n=new ArrayList<>();for(Doc d:docs.values())n.add(d.name);return n;}
  private JSONArray catalog() throws Exception{
    JSONArray a=new JSONArray();
    for(Doc d:docs.values())a.put(new JSONObject().put("id",d.id).put("name",d.name).put("size",d.size).put("mime",d.mime));
    return a;
  }
  private void start(){
    String u=address.getText().toString().trim(),t=secret.getText().toString().trim();
    if(!u.startsWith("https://")||u.length()<12||t.length()<32||docs.isEmpty()){
      show("Нужны HTTPS-адрес, секрет длиной 32+ символа и выбранные файлы.");return;
    }
    endpoint=u.replaceAll("/+$","");token=t;active=true;buttons(true);show("Подключение...");
    executor.execute(()->{
      try {
        String id=post("/device/start",new JSONObject().put("files",catalog())).getString("session_id");
        session=id;
        if(!active){try{post("/device/stop",new JSONObject().put("session_id",id));}catch(Exception ignored){}return;}
        show("Доступ включён. Покинешь приложение — отключится.");
        polling=executor.scheduleWithFixedDelay(this::poll,0,2,TimeUnit.SECONDS);
      }catch(Exception e){active=false;session="";buttons(false);show("Ошибка подключения: "+e.getMessage());}
    });
  }
  private void poll(){
    if(!active||session.isEmpty())return;
    try{
      JSONObject response=post("/device/poll",new JSONObject().put("session_id",session).put("files",catalog()));
      if(!active)return;
      JSONObject cmd=response.optJSONObject("command");
      if(cmd==null||!"read_text".equals(cmd.optString("type")))return;
      String fileId=cmd.optString("file_id"),requestId=cmd.optString("request_id");
      Doc target=null;
      for(Doc d:docs.values())if(d.id.equals(fileId))target=d;
      JSONObject reply=new JSONObject().put("session_id",session).put("request_id",requestId).put("file_id",fileId);
      if(target==null)reply.put("error","Файл больше не выбран");
      else {
        try{
          if(target.size>LIMIT)throw new IOException("Файл больше 256 КБ");
          byte[] data=read(target.uri);
          for(byte b:data)if(b==0)throw new IOException("Двоичный файл");
          reply.put("base64_data",android.util.Base64.encodeToString(data,android.util.Base64.NO_WRAP));
        }catch(Exception e){reply.put("error",String.valueOf(e.getMessage()));}
      }
      if(active)post("/device/reply",reply);
    }catch(Exception e){if(active)show("Нет соединения; сервер отменит доступ после тайм-аута.");}
  }
  private byte[] read(Uri uri)throws Exception{
    try(InputStream in=getContentResolver().openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()){
      if(in==null)throw new IOException("Файл недоступен");
      byte[] buffer=new byte[8192];int count;
      while((count=in.read(buffer))!=-1){if(out.size()+count>LIMIT)throw new IOException("Слишком большой файл");out.write(buffer,0,count);}
      return out.toByteArray();
    }
  }
  private JSONObject post(String path,JSONObject data)throws Exception {
    HttpURLConnection c=(HttpURLConnection)new URL(endpoint+path).openConnection();
    try{
      c.setInstanceFollowRedirects(false);c.setRequestMethod("POST");c.setDoOutput(true);
      c.setConnectTimeout(4000);c.setReadTimeout(4000);
      c.setRequestProperty("Content-Type","application/json");
      c.setRequestProperty("Authorization","Bearer "+token);
      try(OutputStream out=c.getOutputStream()){out.write(data.toString().getBytes(StandardCharsets.UTF_8));}
      int status=c.getResponseCode();if(status<200||status>=300)throw new IOException("HTTP "+status);
      try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
        byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){if(out.size()+n>1048576)throw new IOException("Oversized response");out.write(b,0,n);}
        return new JSONObject(out.toString("UTF-8"));
      }
    }finally{c.disconnect();}
  }
  private void disconnect(){
    active=false;
    if(polling!=null){polling.cancel(false);polling=null;}
    buttons(false);String old=session;session="";
    show("Локально отключено.");
    if(!old.isEmpty())executor.execute(()->{
      try{post("/device/stop",new JSONObject().put("session_id",old));show("Доступ отключён и на сервере.");}
      catch(Exception e){show("Локально отключено. Сервер закроет сеанс через 15 секунд без связи.");}
    });
  }
  @Override protected void onStop(){if(active)disconnect();super.onStop();}
  @Override protected void onDestroy(){if(active)disconnect();executor.shutdown();super.onDestroy();}
}