package news.blackfalcon.jan;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

public class RadioService extends Service implements AudioManager.OnAudioFocusChangeListener {

    public static final String ACTION_PLAY = "news.blackfalcon.jan.PLAY";
    public static final String ACTION_STOP = "news.blackfalcon.jan.STOP";
    public static final String ACTION_STATE = "news.blackfalcon.jan.STATE";
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_NAME = "name";
    public static final String EXTRA_INDEX = "index";
    public static final String EXTRA_STATE = "state";

    public static final int STOPPED = 0;
    public static final int BUFFERING = 1;
    public static final int PLAYING = 2;
    public static final int ERROR = 3;

    public static volatile int state = STOPPED;
    public static volatile String currentName = "";
    public static volatile int currentIndex = 0;

    private static final String CHANNEL_ID = "radio_playback";
    private static final int NOTIF_ID = 1;

    private MediaPlayer player;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private boolean pausedByFocus = false;

    @Override
    public void onCreate() {
        super.onCreate();
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Radio playback", NotificationManager.IMPORTANCE_LOW);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(channel);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_PLAY.equals(action)) {
            String url = intent.getStringExtra(EXTRA_URL);
            String name = intent.getStringExtra(EXTRA_NAME);
            currentName = name == null ? "" : name;
            currentIndex = intent.getIntExtra(EXTRA_INDEX, 0);
            enterForeground();
            startPlayback(url);
        } else if (ACTION_STOP.equals(action)) {
            stopEverything();
        }
        return START_NOT_STICKY;
    }

    private void enterForeground() {
        Notification n = buildNotification(getString(R.string.status_connecting));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);

        Intent stop = new Intent(this, RadioService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE);

        Notification.Action stopAction = new Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_stop), getString(R.string.stop), stopPi).build();

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(openPi)
                .addAction(stopAction)
                .setOngoing(true)
                .build();
    }

    private AudioAttributes attrs() {
        return new AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build();
    }

    private void startPlayback(String url) {
        releasePlayer();
        pausedByFocus = false;
        setState(BUFFERING);
        requestFocus();
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(attrs());
            player.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            player.setDataSource(url);
            player.setOnPreparedListener(mp -> {
                mp.start();
                setState(PLAYING);
            });
            player.setOnErrorListener((mp, what, extra) -> {
                fail();
                return true;
            });
            player.prepareAsync();
        } catch (Exception e) {
            fail();
        }
    }

    private void setState(int newState) {
        state = newState;
        if (newState == BUFFERING || newState == PLAYING) {
            String text = newState == BUFFERING
                    ? getString(R.string.status_connecting)
                    : getString(R.string.status_playing, currentName);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(NOTIF_ID, buildNotification(text));
        }
        Intent i = new Intent(ACTION_STATE);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_STATE, newState);
        sendBroadcast(i);
    }

    private void fail() {
        releasePlayer();
        abandonFocus();
        setState(ERROR);
        stopForeground(Service.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void stopEverything() {
        releasePlayer();
        abandonFocus();
        setState(STOPPED);
        stopForeground(Service.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void releasePlayer() {
        if (player != null) {
            try {
                player.release();
            } catch (Exception ignored) {
            }
            player = null;
        }
    }

    private void requestFocus() {
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs())
                .setOnAudioFocusChangeListener(this)
                .build();
        audioManager.requestAudioFocus(focusRequest);
    }

    private void abandonFocus() {
        if (focusRequest != null) {
            audioManager.abandonAudioFocusRequest(focusRequest);
            focusRequest = null;
        }
    }

    @Override
    public void onAudioFocusChange(int change) {
        try {
            switch (change) {
                case AudioManager.AUDIOFOCUS_LOSS:
                    stopEverything();
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                    if (player != null && player.isPlaying()) {
                        player.pause();
                        pausedByFocus = true;
                    }
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                    if (player != null) player.setVolume(0.3f, 0.3f);
                    break;
                case AudioManager.AUDIOFOCUS_GAIN:
                    if (player != null) {
                        player.setVolume(1f, 1f);
                        if (pausedByFocus) {
                            player.start();
                            pausedByFocus = false;
                        }
                    }
                    break;
                default:
                    break;
            }
        } catch (IllegalStateException ignored) {
        }
    }

    @Override
    public void onDestroy() {
        releasePlayer();
        abandonFocus();
        if (state != ERROR) state = STOPPED;
        super.onDestroy();
    }
}
