package news.blackfalcon.jan;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private static final String NEWS_URL = "https://www.replaynews.net/";
    private static final long REFRESH_MS = 5 * 60 * 1000L;

    private static final class Channel {
        final String name;
        final String flag;
        final String url;

        Channel(String name, String countryCode, String url) {
            this.name = name;
            this.flag = flag(countryCode);
            this.url = url;
        }

        @Override
        public String toString() {
            return flag + "  " + name;
        }
    }

    private Spinner spinner;
    private Button playBtn;
    private Button stopBtn;
    private TextView statusView;
    private TextView tickerView;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private List<Channel> channels;
    private int playingIndex = -1;

    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            loadNews();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            render(intent.getIntExtra(RadioService.EXTRA_STATE, RadioService.STOPPED));
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        spinner = findViewById(R.id.languageSpinner);
        playBtn = findViewById(R.id.playButton);
        stopBtn = findViewById(R.id.stopButton);
        statusView = findViewById(R.id.statusText);
        tickerView = findViewById(R.id.tickerText);

        channels = buildChannels();
        ArrayAdapter<Channel> adapter = new ArrayAdapter<>(this, R.layout.spinner_item, channels);
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        spinner.setAdapter(adapter);

        int s = RadioService.state;
        if (s == RadioService.BUFFERING || s == RadioService.PLAYING) {
            playingIndex = RadioService.currentIndex;
            spinner.setSelection(playingIndex, false);
        }

        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                int st = RadioService.state;
                boolean active = st == RadioService.BUFFERING || st == RadioService.PLAYING;
                if (active && position != playingIndex) {
                    play();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        playBtn.setOnClickListener(v -> play());
        stopBtn.setOnClickListener(v -> {
            Intent i = new Intent(this, RadioService.class);
            i.setAction(RadioService.ACTION_STOP);
            startService(i);
        });

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        tickerView.setSelected(true);
        render(RadioService.state);
        handler.post(refreshTask);
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(RadioService.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateReceiver, filter);
        }
        render(RadioService.state);
        tickerView.setSelected(true);
    }

    @Override
    protected void onPause() {
        unregisterReceiver(stateReceiver);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(refreshTask);
        super.onDestroy();
    }

    private void play() {
        int pos = spinner.getSelectedItemPosition();
        Channel c = channels.get(pos);
        playingIndex = pos;
        Intent i = new Intent(this, RadioService.class);
        i.setAction(RadioService.ACTION_PLAY);
        i.putExtra(RadioService.EXTRA_URL, c.url);
        i.putExtra(RadioService.EXTRA_NAME, c.name);
        i.putExtra(RadioService.EXTRA_INDEX, pos);
        startForegroundService(i);
        render(RadioService.BUFFERING);
    }

    private void render(int state) {
        switch (state) {
            case RadioService.BUFFERING:
                statusView.setText(R.string.status_connecting);
                playBtn.setEnabled(false);
                stopBtn.setEnabled(true);
                break;
            case RadioService.PLAYING:
                statusView.setText(getString(R.string.status_playing, RadioService.currentName));
                playBtn.setEnabled(false);
                stopBtn.setEnabled(true);
                break;
            case RadioService.ERROR:
                statusView.setText(R.string.status_error);
                playBtn.setEnabled(true);
                stopBtn.setEnabled(false);
                break;
            default:
                statusView.setText(R.string.status_stopped);
                playBtn.setEnabled(true);
                stopBtn.setEnabled(false);
                break;
        }
    }

    // ---------- Channels ----------

    private static String flag(String cc) {
        StringBuilder sb = new StringBuilder();
        for (char c : cc.toCharArray()) {
            sb.appendCodePoint(0x1F1E6 + (c - 'A'));
        }
        return sb.toString();
    }

    private static String std(String host) {
        return "https://" + host + ".ice.infomaniak.ch/" + host + "-128.mp3";
    }

    private static List<Channel> buildChannels() {
        List<Channel> l = new ArrayList<>();
        l.add(new Channel("العربية", "SA", std("replaynewsar")));
        l.add(new Channel("Deutsch", "DE", std("replaynewsde")));
        l.add(new Channel("English", "GB", std("replaynewsen")));
        l.add(new Channel("Español", "ES", std("replaynewses")));
        l.add(new Channel("Suomi", "FI", std("replaynewsfi")));
        l.add(new Channel("Ελληνικά", "GR", std("replaynewsgr")));
        l.add(new Channel("Hrvatski", "HR", std("replaynewshr")));
        l.add(new Channel("हिन्दी", "IN", std("replaynewshi")));
        l.add(new Channel("Bahasa Indonesia", "ID", std("replaynewsin")));
        l.add(new Channel("Italiano", "IT", std("replaynewsit")));
        l.add(new Channel("Nederlands", "NL", std("replaynewsnl")));
        l.add(new Channel("日本語", "JP", std("replaynewsja")));
        l.add(new Channel("한국어", "KR", std("replaynewskr")));
        l.add(new Channel("Polski", "PL", std("replaynewspl")));
        l.add(new Channel("Português", "PT", std("replaynewspt")));
        l.add(new Channel("Română", "RO", std("replaynewsro")));
        l.add(new Channel("Русский", "RU", std("replaynewsru")));
        l.add(new Channel("Svenska", "SE", std("replaynewsse")));
        l.add(new Channel("Français", "FR", "https://replaynews.ice.infomaniak.ch/replaynews-192.mp3"));
        l.add(new Channel("Kiswahili", "KE", std("replaynewssw")));
        l.add(new Channel("Türkçe", "TR", std("replaynewstr")));
        l.add(new Channel("Українська", "UA", std("replaynewsuk")));
        l.add(new Channel("Tiếng Việt", "VN", "https://replaynewsvi.ice.infomaniak.ch/replaynewsve-128.mp3"));
        l.add(new Channel("中文", "CN", std("replaynewszh")));
        l.add(new Channel("Eco (Français)", "FR", std("replaynewseco")));
        l.add(new Channel("Story (Français)", "FR", std("replaynewsstory")));
        l.add(new Channel("Sport (Français)", "FR", std("replaynewssport")));
        return l;
    }

    // ---------- News ticker ----------

    private void loadNews() {
        new Thread(() -> {
            try {
                String html = download(NEWS_URL);
                List<String> items = parseHeadlines(html);
                if (items.isEmpty()) return;
                StringBuilder sb = new StringBuilder();
                for (String it : items) {
                    if (sb.length() > 0) sb.append("     \u25CF     ");
                    sb.append(it);
                }
                final String text = sb.toString();
                runOnUiThread(() -> {
                    tickerView.setText(text);
                    tickerView.setSelected(true);
                });
            } catch (Exception ignored) {
            }
        }).start();
    }

    private static String download(String address) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(address).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) NewsEvery5Minutes/1.0");
        try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) != -1 && sb.length() < 2_000_000) {
                sb.append(buf, 0, n);
            }
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    /** Headlines on the site look like "09:19 France: ..." so we look for HH:MM prefixed lines. */
    private static List<String> parseHeadlines(String html) {
        String text = html
                .replaceAll("(?is)<script.*?</script>", " ")
                .replaceAll("(?is)<style.*?</style>", " ")
                .replaceAll("(?s)<[^>]+>", "\n");

        String[] rawLines = text.split("\n");
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < rawLines.length; i++) {
            String line = rawLines[i].trim();
            if (line.isEmpty()) continue;
            if (line.matches("^\\d{2}:\\d{2}$")) {
                // time and headline in separate elements: merge with the next non-empty line
                int j = i + 1;
                while (j < rawLines.length && rawLines[j].trim().isEmpty()) j++;
                if (j < rawLines.length) {
                    line = line + " " + rawLines[j].trim();
                    i = j;
                }
            }
            lines.add(line);
        }

        Pattern splitter = Pattern.compile("(?=\\d{2}:\\d{2}\\s)");
        Pattern item = Pattern.compile("^(\\d{2}:\\d{2})\\s+(.{15,})$");
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String line : lines) {
            for (String piece : splitter.split(line)) {
                piece = piece.trim();
                Matcher m = item.matcher(piece);
                if (m.matches()) {
                    String clean = Html.fromHtml(piece, Html.FROM_HTML_MODE_LEGACY).toString().trim();
                    set.add(clean);
                    if (set.size() >= 40) return new ArrayList<>(set);
                }
            }
        }
        return new ArrayList<>(set);
    }
}
