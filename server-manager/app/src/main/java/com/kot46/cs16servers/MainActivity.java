package com.kot46.cs16servers;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.*;
import androidx.documentfile.provider.DocumentFile;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class MainActivity extends Activity {
    static final int PICK = 46;
    static final String P="kot46_servers", K="server_list", T="xash_tree";
    static final String DEF =
        "46.174.54.149:27015 | АДСКАЯ СТРАНА\n"+
        "62.122.214.56:27015 | РАЙСКАЯ СТРАНА [18+]\n"+
        "62.122.215.73:27015 | [ZM] ГНИЛАЯ ПЛОТЬ [BiOHAZARD]\n"+
        "45.95.31.209:27015 | ПИВНОЙ |18+|\n"+
        "212.76.137.58:27026 | [CSDM] СМЕРТЕЛЬНАЯ АРЕНА\n"+
        "45.136.205.159:27015 | [HARD-CS] AutoMix";
    EditText edit; TextView status; SharedPreferences prefs;

    static class S { String a,n; S(String a,String n){this.a=a;this.n=n;} }

    public void onCreate(Bundle b){
        super.onCreate(b); prefs=getSharedPreferences(P,MODE_PRIVATE);
        ScrollView sv=new ScrollView(this);
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(d(18),d(18),d(18),d(24)); sv.addView(root);
        TextView title=t("KoT46 — Серверы CS 1.6",24); title.setGravity(Gravity.CENTER); root.addView(title,w());
        TextView hint=t("Строка: IP:PORT | название. При первом сохранении выбери папку xash. Перед изменением полностью закрой CS 1.6.",15);
        hint.setPadding(0,d(12),0,d(12)); root.addView(hint,w());
        edit=new EditText(this); edit.setMinLines(9); edit.setGravity(Gravity.TOP); edit.setText(prefs.getString(K,DEF)); root.addView(edit,w());
        Button save=b("СОХРАНИТЬ В ИЗБРАННОЕ"); save.setOnClickListener(v->save()); root.addView(save,bp());
        Button pick=b("ВЫБРАТЬ ПАПКУ XASH"); pick.setOnClickListener(v->pick()); root.addView(pick,bp());
        Button reset=b("ВЕРНУТЬ 6 СЕРВЕРОВ"); reset.setOnClickListener(v->edit.setText(DEF)); root.addView(reset,bp());
        status=t(prefs.contains(T)?"Папка xash выбрана. Можно сохранять.":"Сначала выбери /storage/emulated/0/xash.",14);
        status.setPadding(0,d(12),0,0); root.addView(status,w()); setContentView(sv);
    }
    TextView t(String s,int z){TextView v=new TextView(this);v.setText(s);v.setTextSize(z);return v;}
    Button b(String s){Button v=new Button(this);v.setText(s);return v;}
    LinearLayout.LayoutParams w(){return new LinearLayout.LayoutParams(-1,-2);}
    LinearLayout.LayoutParams bp(){LinearLayout.LayoutParams p=w();p.topMargin=d(8);return p;}
    int d(int x){return Math.round(x*getResources().getDisplayMetrics().density);}

    void pick(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|
                   Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(i,PICK);
    }
    protected void onActivityResult(int r,int c,Intent data){
        super.onActivityResult(r,c,data); if(r!=PICK||c!=RESULT_OK||data==null||data.getData()==null)return;
        Uri u=data.getData();
        try{getContentResolver().takePersistableUriPermission(u,Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            prefs.edit().putString(T,u.toString()).apply();status.setText("Папка выбрана. Теперь нажми «Сохранить в избранное».");
        }catch(Exception e){status.setText("Нет доступа: "+e.getMessage());}
    }

    void save(){
        String tree=prefs.getString(T,null); if(tree==null){status.setText("Сначала выбери папку xash.");pick();return;}
        try{
            List<S> list=parse(edit.getText().toString()); if(list.isEmpty())throw new Exception("Список пуст");
            DocumentFile root=DocumentFile.fromTreeUri(this,Uri.parse(tree)); if(root==null||!root.canWrite())throw new Exception("Нет доступа к папке");
            DocumentFile pc=dir(dir(root,"platform"),"config"), c=dir(root,"config");
            String n=normal(list), rev=rev(list);
            put(pc,"ServerBrowser.vdf",n); put(pc,"rev_ServerBrowser.vdf",rev);
            put(c,"ServerBrowser.vdf",n); put(c,"rev_ServerBrowser.vdf",rev);
            prefs.edit().putString(K,edit.getText().toString().trim()).apply();
            status.setText("ГОТОВО: "+list.size()+" серверов. Перезапусти CS 1.6 и открой «Избранное».");
            Toast.makeText(this,"Избранное обновлено",Toast.LENGTH_SHORT).show();
        }catch(Exception e){status.setText("Ошибка: "+e.getMessage());}
    }
    DocumentFile dir(DocumentFile p,String n)throws Exception{
        DocumentFile f=p.findFile(n); if(f!=null&&f.isDirectory())return f;
        f=p.createDirectory(n); if(f==null)throw new Exception("Не создать "+n); return f;
    }
    void put(DocumentFile d,String n,String text)throws Exception{
        DocumentFile f=d.findFile(n); if(f==null)f=d.createFile("application/octet-stream",n);
        if(f==null)throw new Exception("Не создать "+n);
        try(OutputStream o=getContentResolver().openOutputStream(f.getUri(),"wt")){
            if(o==null)throw new Exception("Не открыть "+n); o.write(text.getBytes(StandardCharsets.UTF_8));o.write(0);
        }
    }
    List<S> parse(String x){
        List<S> out=new ArrayList<>();String[] ls=x.replace("\r","").split("\n");
        for(int i=0;i<ls.length;i++){String q=ls[i].trim();if(q.isEmpty()||q.startsWith("#"))continue;
            int k=q.indexOf('|');String a=(k<0?q:q.substring(0,k)).trim(),n=(k<0?a:q.substring(k+1)).trim();
            if(!a.matches("^[A-Za-z0-9.-]+:[0-9]{1,5}$"))throw new IllegalArgumentException("Строка "+(i+1)+": нужен IP:PORT | название");
            int port=Integer.parseInt(a.substring(a.lastIndexOf(':')+1));if(port<1||port>65535)throw new IllegalArgumentException("Неверный порт");
            out.add(new S(a,n.isEmpty()?a:n));
        }return out;
    }
    String esc(String s){return s.replace("\\","\\\\").replace("\"","\\\"");}
    String entries(List<S> ss,String in){
        StringBuilder b=new StringBuilder();for(int i=0;i<ss.size();i++){S s=ss.get(i);
            b.append(in).append("\"").append(i).append("\"\n").append(in).append("{\n");
            b.append(in).append("\t\"name\"\t\t\"").append(esc(s.n)).append("\"\n");
            b.append(in).append("\t\"address\"\t\t\"").append(esc(s.a)).append("\"\n");
            b.append(in).append("\t\"lastplayed\"\t\t\"0\"\n").append(in).append("\t\"appID\"\t\t\"10\"\n");
            b.append(in).append("\t\"gamedir\"\t\t\"cstrike\"\n").append(in).append("}\n");
        }return b.toString();
    }
    String rev(List<S> s){return "\"filters\"\n{\n\t\"favorites\"\n\t{\n"+entries(s,"\t\t")+"\t}\n\t\"history\"\n\t{\n\t}\n}\n";}
    String normal(List<S> s){
        StringBuilder b=new StringBuilder("\"Filters\"\n{\n\t\"gamelist\"\t\t\"favorites\"\n\t\"favorites\"\n\t{\n");
        b.append(entries(s,"\t\t")).append("\t}\n\t\"history\"\n\t{\n\t}\n\t\"Filters\"\n\t{\n");
        String[] g={"InternetGames","SpectateGames","FavoriteGames","LanGames","FriendsGames","HistoryGames"};
        for(String x:g)b.append("\t\t\"").append(x).append("\"\n\t\t{\n\t\t\t\"ping\"\t\t\"0\"\n\t\t\t\"NoFull\"\t\t\"0\"\n\t\t\t\"NoEmpty\"\t\t\"0\"\n\t\t\t\"NoPassword\"\t\t\"0\"\n\t\t\t\"ValidSteamAccount\"\t\t\"0\"\n\t\t\t\"secure\"\t\t\"0\"\n\t\t}\n");
        return b.append("\t}\n}\n").toString();
    }
}
