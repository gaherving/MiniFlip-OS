package com.irving.galaxyrootchecker;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.util.*;

public class MainActivity extends Activity {
  private TextView result;
  private Button rootBtn;

  static class Profile {
    String model, buildToken, kernelContains, status, note;
    Profile(String m,String b,String k,String s,String n){
      model=m;buildToken=b;kernelContains=k;status=s;note=n;
    }
  }

  private final List<Profile> profiles = Arrays.asList(
    new Profile("SM-S938B","S938BXXSBCZG3","6.6.98-android15-8-pd6ff1cd","VERIFIED",
      "Perfil conocido para SM-S938B. Se requiere payload exacto antes de ejecutar root."),
    new Profile("SM-S938B","S938BXXSCCZH1","6.6.98-android15-8-pd6ff1cd","EXPERIMENTAL",
      "Tu build coincide en modelo y base de kernel, pero no hay payload exacto verificado integrado todavía.")
  );

  @Override public void onCreate(Bundle b){
    super.onCreate(b);
    ScrollView sv=new ScrollView(this);
    LinearLayout root=new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(dp(20),dp(24),dp(20),dp(28));
    sv.addView(root);
    setContentView(sv);

    TextView title=txt("Galaxy Root Checker",28,Typeface.BOLD,Color.rgb(28,28,28));
    root.addView(title);
    TextView sub=txt("Diagnóstico local de compatibilidad. No ejecuta root ni modifica el sistema.",15,Typeface.NORMAL,Color.DKGRAY);
    sub.setPadding(0,dp(8),0,dp(22));
    root.addView(sub);

    Button scanBtn=new Button(this);
    scanBtn.setText("ANALIZAR DISPOSITIVO");
    scanBtn.setAllCaps(false);
    scanBtn.setTextSize(17);
    scanBtn.setMinHeight(dp(52));
    root.addView(scanBtn,new LinearLayout.LayoutParams(-1,-2));

    result=txt("Pulsa Analizar dispositivo.",15,Typeface.NORMAL,Color.rgb(45,45,45));
    result.setPadding(0,dp(20),0,dp(20));
    root.addView(result);

    rootBtn=new Button(this);
    rootBtn.setText("ROOT TEMPORAL — BLOQUEADO");
    rootBtn.setEnabled(false);
    rootBtn.setAllCaps(false);
    root.addView(rootBtn,new LinearLayout.LayoutParams(-1,-2));

    TextView note=txt("La app solo habilitará root cuando exista una coincidencia exacta y un payload verificado para esa build.",13,Typeface.NORMAL,Color.GRAY);
    note.setPadding(0,dp(14),0,0);
    root.addView(note);

    scanBtn.setOnClickListener(v->scan());
  }

  private void scan(){
    String model=Build.MODEL;
    String device=Build.DEVICE;
    String display=Build.DISPLAY;
    String fingerprint=Build.FINGERPRINT;
    String android=Build.VERSION.RELEASE+" (SDK "+Build.VERSION.SDK_INT+")";
    String kernel=readFirst("/proc/version");
    String release=unameRelease();

    Profile exact=null, partial=null;
    for(Profile p:profiles){
      boolean modelOk=model.equalsIgnoreCase(p.model);
      boolean buildOk=display.contains(p.buildToken) || fingerprint.contains(p.buildToken);
      boolean kernelOk=(kernel.contains(p.kernelContains) || release.contains(p.kernelContains));
      if(modelOk && buildOk && kernelOk){ exact=p; break; }
      if(modelOk && kernelOk) partial=p;
    }

    StringBuilder sb=new StringBuilder();
    sb.append("Modelo: ").append(model).append("\n");
    sb.append("Device: ").append(device).append("\n");
    sb.append("Android: ").append(android).append("\n");
    sb.append("Build: ").append(display).append("\n");
    sb.append("Kernel release: ").append(release).append("\n\n");

    if(exact!=null){
      sb.append("Perfil: ").append(exact.buildToken).append("\n");
      sb.append("Estado: ").append(exact.status).append("\n");
      sb.append(exact.note);
      rootBtn.setText("VERIFIED".equals(exact.status) ? "ROOT TEMPORAL — REQUIERE PAYLOAD" : "ROOT TEMPORAL — NO VERIFICADO");
      rootBtn.setEnabled(false);
    } else if(partial!=null){
      sb.append("Coincidencia parcial encontrada.\nEstado: NO VERIFICADO\n");
      sb.append("La app no ejecutará ningún payload hasta tener coincidencia exacta.");
      rootBtn.setText("ROOT TEMPORAL — BLOQUEADO");
      rootBtn.setEnabled(false);
    } else {
      sb.append("Estado: SIN PERFIL COMPATIBLE\n");
      sb.append("No se encontró una coincidencia segura en la base local.");
      rootBtn.setText("ROOT TEMPORAL — BLOQUEADO");
      rootBtn.setEnabled(false);
    }
    result.setText(sb.toString());
  }

  private String unameRelease(){
    try{
      Process p=new ProcessBuilder("uname","-r").redirectErrorStream(true).start();
      BufferedReader br=new BufferedReader(new InputStreamReader(p.getInputStream()));
      String s=br.readLine();
      return s==null?"desconocido":s;
    }catch(Exception e){return "desconocido";}
  }

  private String readFirst(String path){
    try(BufferedReader br=new BufferedReader(new FileReader(path))){
      String s=br.readLine(); return s==null?"desconocido":s;
    }catch(Exception e){return "desconocido";}
  }

  private TextView txt(String s,int sp,int style,int color){
    TextView t=new TextView(this);
    t.setText(s); t.setTextSize(sp); t.setTypeface(Typeface.DEFAULT,style); t.setTextColor(color);
    return t;
  }

  private int dp(int v){return (int)(v*getResources().getDisplayMetrics().density+0.5f);}
}
