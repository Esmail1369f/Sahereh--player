package ir.fatahi.saherehplayer;

import android.app.*;
import android.content.*;
import android.media.*;
import android.media.session.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.content.pm.ServiceInfo;
import org.json.*;
import java.util.*;

public class NativeAudioService extends Service {
    public static final String ACTION="ir.fatahi.saherehplayer.ACTION";
    public static final String EXTRA_CMD="cmd";
    public static final String CHANNEL="sahereh_playback";
    private static NativeAudioService instance;
    private MediaPlayer player;
    private MediaSession mediaSession;
    private Equalizer equalizer;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private final ArrayList<Track> queue=new ArrayList<>();
    private int index=-1;
    private float volume=0.8f;
    private boolean resumeOnFocus=true;
    private boolean wasPlayingBeforeFocus=false;
    private boolean repeat=false, shuffle=false;
    private Handler handler=new Handler(Looper.getMainLooper());
    private Runnable sleepRunnable;
    private static class Track { String uri,title,artist; long duration; Track(String u,String t,String a,long d){uri=u;title=t;artist=a;duration=d;} JSONObject json(){JSONObject o=new JSONObject();try{o.put("uri",uri);o.put("title",title);o.put("artist",artist);o.put("duration",duration);}catch(Exception ignored){}return o;} }

    public static NativeAudioService get(){return instance;}
    @Override public void onCreate(){super.onCreate();instance=this;audioManager=(AudioManager)getSystemService(AUDIO_SERVICE);createChannel();mediaSession=new MediaSession(this,"SaherehPlayer");mediaSession.setCallback(new MediaSession.Callback(){
        @Override public void onPlay(){playCurrent();}
        @Override public void onPause(){pausePlayer();}
        @Override public void onSkipToNext(){next();}
        @Override public void onSkipToPrevious(){previous();}
        @Override public void onSeekTo(long pos){seek((int)pos);}
    });mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS|MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);mediaSession.setActive(true);
        registerReceiver(noisyReceiver,new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));}
    private final BroadcastReceiver noisyReceiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){if(AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(i.getAction()))pausePlayer();}};
    @Override public int onStartCommand(Intent intent,int flags,int startId){if(intent==null)return START_NOT_STICKY;String cmd=intent.getStringExtra(EXTRA_CMD);if(cmd==null)return START_NOT_STICKY;
        switch(cmd){
            case "queue": setQueue(intent.getStringExtra("json"));break;
            case "playIndex": int target=intent.getIntExtra("index",0);if(target>=0&&target<queue.size()){index=target;playCurrent();}break;
            case "toggle": if(isPlaying())pausePlayer();else playCurrent();break;
            case "play": playCurrent();break;
            case "pause": pausePlayer();break;
            case "next": next();break;
            case "previous": previous();break;
            case "seek": seek(intent.getIntExtra("position",0));break;
            case "volume": volume=Math.max(0f,Math.min(1f,intent.getFloatExtra("volume",0.8f)));if(player!=null)player.setVolume(volume,volume);break;
            case "repeat": repeat=intent.getBooleanExtra("value",false);break;
            case "shuffle": shuffle=intent.getBooleanExtra("value",false);break;
            case "timer": scheduleTimer(intent.getIntExtra("minutes",0));break;
            case "eq": setBand(intent.getIntExtra("band",-1),intent.getIntExtra("level",0));break;
            case "stop": pausePlayer();stopForeground(true);stopSelf();break;
        }return START_NOT_STICKY;}
    private void setQueue(String raw){try{JSONArray a=new JSONArray(raw==null?"[]":raw);queue.clear();for(int i=0;i<a.length();i++){JSONObject o=a.getJSONObject(i);queue.add(new Track(o.optString("uri"),o.optString("title","آهنگ"),o.optString("artist","ناشناخته"),o.optLong("duration",0)));}if(index>=queue.size())index=-1;}catch(Exception ignored){}}
    private boolean requestFocus(){try{if(Build.VERSION.SDK_INT>=26){AudioAttributes attrs=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();focusRequest=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attrs).setOnAudioFocusChangeListener(focusListener).setAcceptsDelayedFocusGain(true).setWillPauseWhenDucked(false).build();return audioManager.requestAudioFocus(focusRequest)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED;}return audioManager.requestAudioFocus(focusListener,AudioManager.STREAM_MUSIC,AudioManager.AUDIOFOCUS_GAIN)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED;}catch(Exception e){return true;}}
    private final AudioManager.OnAudioFocusChangeListener focusListener=change->{if(change==AudioManager.AUDIOFOCUS_LOSS){wasPlayingBeforeFocus=isPlaying();pausePlayer();}else if(change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT){wasPlayingBeforeFocus=isPlaying();pausePlayer();}else if(change==AudioManager.AUDIOFOCUS_GAIN&&wasPlayingBeforeFocus){playCurrent();wasPlayingBeforeFocus=false;}else if(change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK&&player!=null&&isPlaying())player.setVolume(volume*.25f,volume*.25f);};
    private void playCurrent(){if(index<0||index>=queue.size())return;Track t=queue.get(index);if(player!=null&&player.isPlaying()&&playerIndex==index){updateState();return;}releasePlayer();if(!requestFocus())return;try{playerIndex=index;player=new MediaPlayer();player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());player.setDataSource(this,Uri.parse(t.uri));player.setVolume(volume,volume);player.setOnPreparedListener(mp->{try{if(Build.VERSION.SDK_INT>=26){equalizer=new Equalizer(0,mp.getAudioSessionId());equalizer.setEnabled(true);}mp.start();startForegroundCompat();updateState();updateNotification();}catch(Exception e){updateState();}});player.setOnCompletionListener(mp->{if(repeat){seek(0);playCurrent();}else if(!queue.isEmpty()){if(shuffle&&queue.size()>1){int n=index;while(n==index)n=new Random().nextInt(queue.size());index=n;playCurrent();}else if(index<queue.size()-1){index++;playCurrent();}else{updateState();stopForeground(false);}}});player.setOnErrorListener((mp,what,extra)->{updateState();return true;});player.prepareAsync();startForegroundCompat();}catch(Exception e){releasePlayer();updateState();}}
    private int playerIndex=-1;
    private void pausePlayer(){if(player!=null)try{if(player.isPlaying())player.pause();}catch(Exception ignored){}updateState();updateNotification();}
    private void next(){if(queue.isEmpty())return;if(shuffle&&queue.size()>1){int n=index;while(n==index)n=new Random().nextInt(queue.size());index=n;}else index=(index+1)%queue.size();playCurrent();}
    private void previous(){if(queue.isEmpty())return;if(player!=null&&player.getCurrentPosition()>3000){seek(0);return;}index=index<=0?queue.size()-1:index-1;playCurrent();}
    private void seek(int p){if(player!=null)try{player.seekTo(Math.max(0,Math.min(p,player.getDuration())));}catch(Exception ignored){}updateState();}
    private void releasePlayer(){if(equalizer!=null){try{equalizer.release();}catch(Exception ignored){}equalizer=null;}if(player!=null){try{player.setOnPreparedListener(null);player.setOnCompletionListener(null);player.release();}catch(Exception ignored){}player=null;}playerIndex=-1;}
    private boolean isPlaying(){try{return player!=null&&player.isPlaying();}catch(Exception e){return false;}}
    private void updateState(){if(mediaSession==null)return;long pos=0,dur=0;try{if(player!=null){pos=player.getCurrentPosition();dur=player.getDuration();}}catch(Exception ignored){}long actions=PlaybackState.ACTION_PLAY|PlaybackState.ACTION_PAUSE|PlaybackState.ACTION_PLAY_PAUSE|PlaybackState.ACTION_SKIP_TO_NEXT|PlaybackState.ACTION_SKIP_TO_PREVIOUS|PlaybackState.ACTION_SEEK_TO;mediaSession.setPlaybackState(new PlaybackState.Builder().setActions(actions).setState(isPlaying()?PlaybackState.STATE_PLAYING:PlaybackState.STATE_PAUSED,pos,1f).build());if(index>=0&&index<queue.size()){Track t=queue.get(index);mediaSession.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,t.title).putString(MediaMetadata.METADATA_KEY_ARTIST,t.artist).putLong(MediaMetadata.METADATA_KEY_DURATION,dur>0?dur:t.duration).build());}updateNotification();}
    private void createChannel(){if(Build.VERSION.SDK_INT>=26){NotificationChannel ch=new NotificationChannel(CHANNEL,"پخش موسیقی",NotificationManager.IMPORTANCE_LOW);ch.setDescription("کنترل پخش ساهره پلیر");((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);}}
    private PendingIntent actionIntent(String action,int request){Intent i=new Intent(this,NativeAudioService.class);i.putExtra(EXTRA_CMD,action);return PendingIntent.getService(this,request,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}
    private Notification buildNotification(){String title=index>=0&&index<queue.size()?queue.get(index).title:"ساهره پلیر";String artist=index>=0&&index<queue.size()?queue.get(index).artist:"پخش موسیقی";Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);b.setContentTitle(title).setContentText(artist).setSmallIcon(android.R.drawable.ic_media_play).setVisibility(Notification.VISIBILITY_PUBLIC).setOngoing(isPlaying()).addAction(new Notification.Action.Builder(android.R.drawable.ic_media_previous,"قبلی",actionIntent("previous",1)).build()).addAction(new Notification.Action.Builder(isPlaying()?android.R.drawable.ic_media_pause:android.R.drawable.ic_media_play,isPlaying()?"مکث":"پخش",actionIntent("toggle",2)).build()).addAction(new Notification.Action.Builder(android.R.drawable.ic_media_next,"بعدی",actionIntent("next",3)).build()).setStyle(new Notification.MediaStyle().setMediaSession(mediaSession.getSessionToken()).setShowActionsInCompactView(0,1,2)).setDeleteIntent(actionIntent("pause",4));return b.build();}
    private void startForegroundCompat(){try{Notification n=buildNotification();if(Build.VERSION.SDK_INT>=29)startForeground(41,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);else startForeground(41,n);}catch(Exception ignored){}}
    private void updateNotification(){try{((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(41,buildNotification());}catch(Exception ignored){}}
    private void scheduleTimer(int minutes){if(sleepRunnable!=null)handler.removeCallbacks(sleepRunnable);if(minutes>0){sleepRunnable=()->{pausePlayer();stopForeground(false);};handler.postDelayed(sleepRunnable,minutes*60000L);}}
    private void setBand(int band,int level){try{if(equalizer!=null&&band>=0&&band<equalizer.getNumberOfBands()){short[] range=equalizer.getBandLevelRange();short v=(short)Math.max(range[0],Math.min(range[1],level));equalizer.setBandLevel((short)band,v);}}catch(Exception ignored){}}
    public String getStateJson(){JSONObject o=new JSONObject();try{o.put("index",index);o.put("playing",isPlaying());o.put("position",player==null?0:player.getCurrentPosition());o.put("duration",player==null?0:player.getDuration());o.put("volume",volume);o.put("count",queue.size());JSONArray bands=new JSONArray();if(equalizer!=null){for(short i=0;i<equalizer.getNumberOfBands();i++){JSONObject b=new JSONObject();b.put("index",i);b.put("level",equalizer.getBandLevel(i));bands.put(b);}}o.put("bands",bands);}catch(Exception ignored){}return o.toString();}
    public String getBandInfo(){JSONObject o=new JSONObject();try{if(equalizer!=null){short[] r=equalizer.getBandLevelRange();o.put("min",r[0]);o.put("max",r[1]);o.put("count",equalizer.getNumberOfBands());JSONArray f=new JSONArray();for(short i=0;i<equalizer.getNumberOfBands();i++)f.put(equalizer.getCenterFreq(i)/1000);o.put("frequencies",f);}}catch(Exception ignored){}return o.toString();}
    @Override public void onDestroy(){try{unregisterReceiver(noisyReceiver);}catch(Exception ignored){}releasePlayer();if(mediaSession!=null){mediaSession.setActive(false);mediaSession.release();}if(audioManager!=null){try{if(Build.VERSION.SDK_INT>=26&&focusRequest!=null)audioManager.abandonAudioFocusRequest(focusRequest);else audioManager.abandonAudioFocus(focusListener);}catch(Exception ignored){}}instance=null;super.onDestroy();}
    @Override public android.os.IBinder onBind(Intent intent){return null;}
}
