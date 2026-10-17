package ir.fatahi.saherehplayer;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
public class MusicActionReceiver extends BroadcastReceiver {
 @Override public void onReceive(Context c,Intent i){Intent s=new Intent(c,MusicService.class).setAction(i.getAction());if(Build.VERSION.SDK_INT>=26)c.startForegroundService(s);else c.startService(s);}
}