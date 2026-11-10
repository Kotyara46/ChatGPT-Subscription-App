package dev.kot.phonebridge;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.*;
import android.util.Base64;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public final class BridgeService extends Service {
  public static final class Doc {
    public final String id,name,mime; public final Uri uri; public final long size;
    Doc(String id,Uri uri,String name,String mime,long size){this.id=id;this.uri=uri;this.name=name;this.mime=mime;this.size=size;}
  }
  private static final String CHANNEL="phone_bridge_active", STOP="dev.kot.phonebridge.STOP", START="dev.kot.phonebridge.START";
  private static final int NOTIFICATION_ID=51, LIMIT=262144;
  private static volatile boolean active=false;
  private static volatile String status="Доступ отключён.";
  private static String stagedUrl,stagedKey;
  private static ArrayList<Doc> stagedFiles;
  private ScheduledExecutorService executor;
  private ScheduledFuture<?> polling;
  private volatile String session="",endpoint="",token="";
  private int reconnectAttempts=0;
  private volatile long lastServerContact=0;
  private Map<String,Doc> docs=new LinkedHashMap<>();

  public static boolean isActive(){return active;}
  public static String getStatus(){return status;}
  public static synchronized void enable(Context context,String url,String key,ArrayList<Doc> files){
    if(active)return;
    stagedUrl=url.replaceAll("/+$","");
    stagedKey=key;
    stagedFiles=new ArrayList<>(files);
    Intent i=new Intent(context,BridgeService.class);i.setAction(START);
    context.startForegroundService(i);
    active=true;status="Подключение к серверу...";
  }
  public static void disable(Context context){
    active=false;status="Отключено локально. Сервер закроет сеанс после остановки или тайм-аута.";
    Intent i=new Intent(context,BridgeService.class);i.setAction(STOP);
    context.startService(i);
  }
  @Override public void onCreate(){
    super.onCreate();
    executor=Executors.newSingleThreadScheduledExecutor();
    NotificationChannel c=new NotificationChannel(CHANNEL,"Доступ ChatGPT к выбранным файлам",NotificationManager.IMPORTANCE_LOW);
    ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);
  }
  @Override public int onStartCommand(Intent intent,int flags,int id){
    if(intent==null||STOP.equals(intent.getAction())){shutdown();return START_NOT_STICKY;}
    if(!START.equals(intent.getAction())){shutdown();return START_NOT_STICKY;}
    startForeground(NOTIFICATION_ID,notifyUser(),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
    synchronized(BridgeService.class){
      if(stagedUrl==null||stagedKey==null||stagedFiles==null){shutdown();return START_NOT_STICKY;}
      endpoint=stagedUrl;token=stagedKey;
      for(Doc d:stagedFiles)docs.put(d.id,d);
      stagedUrl=null;stagedKey=null;stagedFiles=null;
    }
    // Retry startup and recover from Render restarts without leaving an orphan session.
    // A visible foreground notification remains present and the owner can always stop.
    if(polling==null)polling=executor.scheduleWithFixedDelay(this::poll,0,3,TimeUnit.SECONDS);
    return START_NOT_STICKY;
  }
  private Notification notifyUser(){
    Intent stop=new Intent(this,BridgeService.class).setAction(STOP);
    PendingIntent stopIntent=PendingIntent.getService(this,55,stop,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
    Intent open=new Intent(this,MainActivity.class);
    PendingIntent openIntent=PendingIntent.getActivity(this,56,open,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
    return new Notification.Builder(this,CHANNEL)
      .setSmallIcon(android.R.drawable.stat_sys_upload)
      .setContentTitle("Xiaomi Phone Bridge: доступ включён")
      .setContentText("Только выбранные файлы. Нажми «Отключить» для остановки.")
      .setContentIntent(openIntent).setOngoing(true)
      .addAction(new Notification.Action.Builder(null,"ОТКЛЮЧИТЬ",stopIntent).build())
      .build();
  }
  private JSONArray catalog() throws Exception{
    JSONArray a=new JSONArray();
    for(Doc d:docs.values())a.put(new JSONObject().put("id",d.id).put("name",d.name).put("size",d.size).put("mime",d.mime));
    return a;
  }
  // All network operations run on one worker, never on the UI thread.
  private void poll(){
    if(!active)return;
    try{
      if(session.isEmpty()){
        status="Связь восстанавливается (попытка "+(reconnectAttempts+1)+")...";
        String newSession=post("/device/start",new JSONObject().put("files",catalog())).getString("session_id");
        if(!active){
          // Stop was pressed during a slow Render cold-start; revoke the just-created session.
          try{post("/device/stop",new JSONObject().put("session_id",newSession));}catch(Exception ignored){}
          return;
        }
        session=newSession;
        reconnectAttempts=0;
        lastServerContact=SystemClock.elapsedRealtime();
        status="Доступ включён. Для отключения открой уведомление.";
      }
      String currentSession=session;
      JSONObject response=post("/device/poll",new JSONObject().put("session_id",currentSession).put("files",catalog()));
      if(!active)return;
      reconnectAttempts=0;
      lastServerContact=SystemClock.elapsedRealtime();
      status="Доступ включён. Соединение с сервером активно.";
      JSONObject cmd=response.optJSONObject("command");
      if(cmd==null||!"read_text".equals(cmd.optString("type")))return;
      String fileId=cmd.optString("file_id"),requestId=cmd.optString("request_id");
      Doc target=docs.get(fileId);
      JSONObject reply=new JSONObject().put("session_id",currentSession).put("request_id",requestId).put("file_id",fileId);
      if(target==null)reply.put("error","Файл не выбран");
      else{
        try{
          if(target.size>LIMIT)throw new IOException("Файл больше 256 КБ");
          byte[] data=read(target.uri);
          for(byte b:data)if(b==0)throw new IOException("Двоичный файл");
          reply.put("base64_data",Base64.encodeToString(data,Base64.NO_WRAP));
        }catch(SecurityException e){
          reply.put("error","Нет разрешения Android. Отключи доступ и выбери файл снова.");
        }catch(Exception e){
          reply.put("error",String.valueOf(e.getMessage()));
        }
      }
      if(active){
        try{
          post("/device/reply",reply);
          status="Файл обработан; ожидание следующего запроса.";
          lastServerContact=SystemClock.elapsedRealtime();
        }catch(IOException e){
          // A request may already have expired; don't lose the whole active session
          // when only the individual read request was cancelled.
          if(!"HTTP 409".equals(e.getMessage()))throw e;
          status="Ответ на файл опоздал; можно повторить чтение.";
        }
      }
    }catch(IOException e){
      if(!active)return;
      String msg=String.valueOf(e.getMessage());
      if("HTTP 401".equals(msg)||"HTTP 403".equals(msg)){
        status="Сервер отклонил ключ. Отключи доступ и проверь секрет устройства.";
        // Never keep trying with invalid credentials.
        session="";
        active=false;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        return;
      }
      if("HTTP 409".equals(msg)||"HTTP 410".equals(msg)){
        session="";
        status="Сеанс на сервере истёк; автоматически переподключаюсь.";
      }else{
        status="Нет связи с сервером: "+msg;
      }
      reconnectAttempts=Math.min(reconnectAttempts+1,1000);
    }catch(Exception e){
      if(active){
        status="Сбой обмена: "+String.valueOf(e.getMessage());
        reconnectAttempts=Math.min(reconnectAttempts+1,1000);
      }
    }
  }
  private byte[] read(Uri uri)throws Exception{
    try(InputStream in=getContentResolver().openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()){
      if(in==null)throw new IOException("Файл недоступен");
      byte[] buffer=new byte[8192];int n;
      while((n=in.read(buffer))!=-1){if(out.size()+n>LIMIT)throw new IOException("Превышен лимит");out.write(buffer,0,n);}
      return out.toByteArray();
    }
  }
  private JSONObject post(String path,JSONObject data)throws Exception{
    HttpURLConnection c=(HttpURLConnection)new URL(endpoint+path).openConnection();
    try{
      c.setInstanceFollowRedirects(false);c.setRequestMethod("POST");c.setDoOutput(true);
      c.setConnectTimeout(15000);c.setReadTimeout(path.equals("/device/start") ? 90000 : 12000);
      c.setRequestProperty("Content-Type","application/json");
      c.setRequestProperty("Authorization","Bearer "+token);
      try(OutputStream out=c.getOutputStream()){out.write(data.toString().getBytes(StandardCharsets.UTF_8));}
      int statusCode=c.getResponseCode();if(statusCode<200||statusCode>=300)throw new IOException("HTTP "+statusCode);
      try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
        byte[] b=new byte[4096];int n;
        while((n=in.read(b))!=-1){if(out.size()+n>1048576)throw new IOException("Response too large");out.write(b,0,n);}
        return new JSONObject(out.toString("UTF-8"));
      }
    }finally{c.disconnect();}
  }
  private void shutdown(){
    active=false;
    String old=session,host=endpoint,key=token;
    session="";
    if(polling!=null){polling.cancel(false);polling=null;}
    if(executor!=null)executor.shutdownNow();
    if(!old.isEmpty()&&!host.isEmpty()&&!key.isEmpty()){
      new Thread(()->{
        try{
          endpoint=host;token=key;
          post("/device/stop",new JSONObject().put("session_id",old));
        }catch(Exception ignored){}
      },"bridge-stop").start();
    }
    synchronized(BridgeService.class){stagedKey=null;stagedUrl=null;stagedFiles=null;}
    status="Доступ отключён.";
    stopForeground(STOP_FOREGROUND_REMOVE);
    stopSelf();
  }
  @Override public void onDestroy(){
    active=false;
    if(executor!=null)executor.shutdownNow();
    super.onDestroy();
  }
  @Override public IBinder onBind(Intent intent){return null;}
}
