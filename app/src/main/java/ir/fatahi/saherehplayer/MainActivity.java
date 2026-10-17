package ir.fatahi.saherehplayer;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.webkit.*;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private static final int FILE_CHOOSER_REQUEST = 711;
    private static final int PERMISSION_REQUEST = 712;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xff090909);
        getWindow().setNavigationBarColor(0xff090909);
        webView = new WebView(this);
        webView.setBackgroundColor(0xff090909);
        WebSettings s=webView.getSettings(); s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true); s.setMediaPlaybackRequiresUserGesture(false);
        webView.addJavascriptInterface(new PlayerBridge(), "Sahereh");
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient(){
            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p){
                if(filePathCallback!=null)filePathCallback.onReceiveValue(null);
                filePathCallback=cb;
                try { Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("audio/*"); i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true); startActivityForResult(i,FILE_CHOOSER_REQUEST); return true; }
                catch(Exception e){filePathCallback=null;return false;}
            }
        });
        setContentView(webView);
        if(Build.VERSION.SDK_INT>=33) requestPermissions(new String[]{Manifest.permission.READ_MEDIA_AUDIO,Manifest.permission.POST_NOTIFICATIONS},PERMISSION_REQUEST);
        else requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},PERMISSION_REQUEST);
        webView.loadUrl("file:///android_asset/index.html");
    }
    private class PlayerBridge {
        @JavascriptInterface public void scanMusic(){
            runOnUiThread(()->{
                if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO)!=PackageManager.PERMISSION_GRANTED){ requestPermissions(new String[]{Manifest.permission.READ_MEDIA_AUDIO},PERMISSION_REQUEST); return; }
                if(Build.VERSION.SDK_INT<33 && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},PERMISSION_REQUEST);return;}
                JSONArray arr=new JSONArray(); Uri collection=MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                String[] cols={MediaStore.Audio.Media._ID,MediaStore.Audio.Media.TITLE,MediaStore.Audio.Media.ARTIST,MediaStore.Audio.Media.DURATION,MediaStore.Audio.Media.SIZE};
                try(Cursor c=getContentResolver().query(collection,cols,MediaStore.Audio.Media.IS_MUSIC+" != 0",null,MediaStore.Audio.Media.TITLE+" COLLATE NOCASE ASC")){
                    if(c!=null)while(c.moveToNext()){JSONObject o=new JSONObject();long id=c.getLong(0);o.put("id",id);o.put("title",c.getString(1));o.put("artist",c.getString(2));o.put("duration",c.getLong(3));o.put("size",c.getLong(4));o.put("uri",Uri.withAppendedPath(collection,String.valueOf(id)).toString());arr.put(o);}
                }catch(Exception ignored){}
                String js="window.onNativeLibrary && window.onNativeLibrary("+arr.toString()+")"; webView.evaluateJavascript(js,null);
            });
        }
        @JavascriptInterface public void play(String uri,String title,String artist){
            Intent i=new Intent(MainActivity.this,MusicService.class).setAction(MusicService.ACTION_PLAY).putExtra("uri",uri).putExtra("title",title).putExtra("artist",artist);
            if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);
        }
        @JavascriptInterface public void setQueue(String json,int index){Intent i=new Intent(MainActivity.this,MusicService.class).setAction("sp.QUEUE").putExtra("queue",json).putExtra("index",index);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);}
        @JavascriptInterface public int getPosition(){return MusicService.instance==null?0:MusicService.instance.currentPosition();}
        @JavascriptInterface public int getDuration(){return MusicService.instance==null?0:MusicService.instance.currentDuration();}
        @JavascriptInterface public boolean isPlaying(){return MusicService.instance!=null&&MusicService.instance.isPlaying();}
        @JavascriptInterface public void control(String action){Intent i=new Intent(MainActivity.this,MusicService.class).setAction(action);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);}
        @JavascriptInterface public void seekTo(int seconds){Intent i=new Intent(MainActivity.this,MusicService.class).setAction(MusicService.ACTION_SEEK).putExtra("position",seconds*1000);startService(i);}
        @JavascriptInterface public void setVolume(float v){Intent i=new Intent(MainActivity.this,MusicService.class).setAction(MusicService.ACTION_VOLUME).putExtra("volume",v);startService(i);}
        @JavascriptInterface public void sleepTimer(int minutes){Intent i=new Intent(MainActivity.this,MusicService.class).setAction(MusicService.ACTION_TIMER).putExtra("minutes",minutes);startService(i);}
        @JavascriptInterface public void openFolder(){runOnUiThread(()->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);startActivityForResult(i,713);});}
    }
    @Override protected void onActivityResult(int req,int res,Intent data){super.onActivityResult(req,res,data);if(req==FILE_CHOOSER_REQUEST&&filePathCallback!=null){Uri[] r=null;if(res==RESULT_OK&&data!=null){if(data.getClipData()!=null){int n=data.getClipData().getItemCount();r=new Uri[n];for(int i=0;i<n;i++)r[i]=data.getClipData().getItemAt(i).getUri();}else if(data.getData()!=null)r=new Uri[]{data.getData()};}filePathCallback.onReceiveValue(r);filePathCallback=null;}}
    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){super.onRequestPermissionsResult(r,p,g);if(r==PERMISSION_REQUEST&&webView!=null&&g.length>0&&g[0]==PackageManager.PERMISSION_GRANTED)webView.evaluateJavascript("window.requestNativeLibrary && window.requestNativeLibrary()",null);}
    @Override public void onBackPressed(){if(webView!=null&&webView.canGoBack())webView.goBack();else super.onBackPressed();}
}