package uz.tinglaymiz.app;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.core.content.FileProvider;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Yagona ekran: qidiruv, natijalar ro'yxati va ijro boshqaruvi. Ekran o'quvchi uchun moslangan. */
public class MainActivity extends Activity {

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final AtomicInteger token = new AtomicInteger();

    private ListenableFuture<MediaController> future;
    private MediaController player;
    private final List<Runnable> whenReady = new ArrayList<>();

    private EditText input;
    private TextView status;
    private TextView nowTitle;
    private LinearLayout panel;
    private Button btnPlay;
    private Button btnSpeed;
    private ListView list;
    private ArrayAdapter<String> adapter;
    private final List<YtClient.Item> items = new ArrayList<>();
    private final List<String> labels = new ArrayList<>();

    private YtClient.Resolved cur;
    private String curUrl;
    private int attempt;
    private boolean reResolved;
    private boolean announceStart;
    private long resumeMs;
    private final float[] speeds = {1f, 1.25f, 1.5f, 1.75f, 2f, 0.75f};

    private final Runnable slow = new Runnable() {
        @Override
        public void run() {
            say("Internet sekin yoki uzilgan. Kutilmoqda...");
        }
    };

    private final Player.Listener listener = new Player.Listener() {
        @Override
        public void onIsPlayingChanged(boolean playing) {
            updatePlayButton();
            if (playing) {
                ui.removeCallbacks(slow);
                if (announceStart) {
                    announceStart = false;
                    ErrorLog.i("Ijro boshlandi (urinish " + (attempt + 1) + ")");
                    say("Ijro etilmoqda: " + nowTitle.getText());
                }
            }
        }

        @Override
        public void onPlaybackStateChanged(int state) {
            updatePlayButton();
            ui.removeCallbacks(slow);
            if (state == Player.STATE_BUFFERING) {
                ui.postDelayed(slow, 12000);
            } else if (state == Player.STATE_ENDED) {
                say("Video tugadi.");
            }
        }

        @Override
        public void onPlayerError(PlaybackException error) {
            onPlayerFailure(error);
        }
    };

    // ---------------------------------------------------------------- hayot sikli

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        buildUi();
        askNotificationPermissionOnce();

        SessionToken t = new SessionToken(this, new ComponentName(this, PlaybackService.class));
        future = new MediaController.Builder(this, t).buildAsync();
        future.addListener(new Runnable() {
            @Override
            public void run() {
                try {
                    player = future.get();
                    player.addListener(listener);
                    syncFromPlayer();
                    List<Runnable> pending = new ArrayList<>(whenReady);
                    whenReady.clear();
                    for (Runnable r : pending) {
                        r.run();
                    }
                } catch (Throwable e) {
                    ErrorLog.e("Pleyer xizmatiga ulanib bo'lmadi", e);
                    say("Pleyer xizmatiga ulanib bo'lmadi. Ilovani qayta oching va xato jurnalini yuboring.");
                }
            }
        }, new java.util.concurrent.Executor() {
            @Override
            public void execute(Runnable r) {
                ui.post(r);
            }
        });

        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        io.shutdownNow();
        if (player != null) {
            player.removeListener(listener);
        }
        if (future != null) {
            MediaController.releaseFuture(future);
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- interfeys

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private Button btn(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout row(View... views) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (View v : views) {
            r.addView(v, new LinearLayout.LayoutParams(0, WRAP, 1f));
        }
        return r;
    }

    private void buildUi() {
        int p = dp(12);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(p, p, p, p);

        input = new EditText(this);
        input.setHint("YouTube'da qidirish yoki havolani qo'ying");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent ev) {
                if (actionId == EditorInfo.IME_ACTION_SEARCH
                        || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                    doSearch();
                    return true;
                }
                return false;
            }
        });
        root.addView(input, new LinearLayout.LayoutParams(MATCH, WRAP));

        root.addView(btn("Qidirish", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doSearch();
            }
        }), new LinearLayout.LayoutParams(MATCH, WRAP));

        status = new TextView(this);
        status.setTextSize(16);
        status.setPadding(0, dp(8), 0, dp(8));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setText("Qidiruv so'zini yozing yoki YouTube havolasini qo'ying.");
        root.addView(status, new LinearLayout.LayoutParams(MATCH, WRAP));

        // Ijro paneli: video tanlangandan keyin ko'rinadi
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setVisibility(View.GONE);

        nowTitle = new TextView(this);
        nowTitle.setTextSize(18);
        nowTitle.setTypeface(null, Typeface.BOLD);
        panel.addView(nowTitle, new LinearLayout.LayoutParams(MATCH, WRAP));

        btnPlay = btn("Pauza", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePlay();
            }
        });
        btnSpeed = btn("Tezlik 1 barobar", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cycleSpeed();
            }
        });
        panel.addView(row(
                btn("10 soniya orqaga", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (player != null) {
                            player.seekBack();
                        }
                    }
                }),
                btnPlay,
                btn("10 soniya oldinga", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (player != null) {
                            player.seekForward();
                        }
                    }
                })));
        panel.addView(row(
                btnSpeed,
                btn("Joriy vaqt", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        sayTime();
                    }
                }),
                btn("To'xtatib yopish", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        stopAll();
                    }
                })));
        root.addView(panel, new LinearLayout.LayoutParams(MATCH, WRAP));

        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels);
        list = new ListView(this);
        list.setAdapter(adapter);
        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int pos, long id) {
                if (pos >= 0 && pos < items.size()) {
                    YtClient.Item it = items.get(pos);
                    startResolve(it.url, it.title);
                }
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        root.addView(row(
                btn("Fon rejimi sozlamasi", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        batteryOpt();
                    }
                }),
                btn("Xato jurnalini yuborish", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        sendLog();
                    }
                })));

        setContentView(root);
    }

    private void say(String s) {
        status.setText(s);
    }

    private void hideKeyboard() {
        InputMethodManager m = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (m != null) {
            m.hideSoftInputFromWindow(input.getWindowToken(), 0);
        }
    }

    // ---------------------------------------------------------------- qidirish

    private void doSearch() {
        final String q = input.getText().toString().trim();
        hideKeyboard();
        if (q.isEmpty()) {
            say("Avval qidiruv so'zini yozing yoki YouTube havolasini qo'ying.");
            return;
        }
        String link = YtClient.extractYoutubeUrl(q);
        if (link != null) {
            startResolve(link, null);
            return;
        }
        final int my = token.incrementAndGet();
        say("Qidirilmoqda: " + q);
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<YtClient.Item> r = YtClient.search(q);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (my == token.get()) {
                                showResults(r);
                            }
                        }
                    });
                } catch (final Throwable e) {
                    ErrorLog.e("Qidirishda xato: " + q, e);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (my == token.get()) {
                                say("Qidirib bo'lmadi. " + YtClient.explain(e)
                                        + " (" + e.getClass().getSimpleName() + ")");
                            }
                        }
                    });
                }
            }
        });
    }

    private void showResults(List<YtClient.Item> r) {
        items.clear();
        items.addAll(r);
        labels.clear();
        for (YtClient.Item it : r) {
            labels.add(it.title + ". Kanal: " + it.channel + ". "
                    + (it.live ? "Jonli efir" : YtClient.spoken(it.seconds)));
        }
        adapter.notifyDataSetChanged();
        list.setSelection(0);
        if (r.isEmpty()) {
            say("Hech narsa topilmadi. Boshqa so'z bilan urinib ko'ring.");
        } else {
            say(r.size() + " ta natija topildi. Tanlash uchun ro'yxatdagi elementni ikki marta bosing.");
        }
    }

    // ---------------------------------------------------------------- ijroni boshlash

    private void withPlayer(Runnable r) {
        if (player != null) {
            r.run();
        } else {
            whenReady.add(r);
        }
    }

    private void startResolve(final String url, String hintTitle) {
        final int my = token.incrementAndGet();
        curUrl = url;
        attempt = 0;
        reResolved = false;
        resumeMs = 0;
        announceStart = true;
        say(hintTitle == null ? "Yuklanmoqda..." : "Yuklanmoqda: " + hintTitle);
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final YtClient.Resolved r = YtClient.resolve(url);
                    ErrorLog.i("Topildi: " + r.title + " | nomzod oqimlar: " + r.cands.size());
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (my == token.get()) {
                                beginPlayback(r);
                            }
                        }
                    });
                } catch (final Throwable e) {
                    ErrorLog.e("Videoni ochishda xato: " + url, e);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (my == token.get()) {
                                say("Videoni ochib bo'lmadi. " + YtClient.explain(e)
                                        + " (" + e.getClass().getSimpleName() + ")");
                            }
                        }
                    });
                }
            }
        });
    }

    private void beginPlayback(YtClient.Resolved r) {
        cur = r;
        attempt = 0;
        nowTitle.setText(r.title);
        panel.setVisibility(View.VISIBLE);
        startAttempt();
    }

    /** Urinishlar tartibi: har bir oqim uchun avval oddiy rejim, keyin zaxira rejim. */
    private void startAttempt() {
        final YtClient.Resolved r = cur;
        if (r == null || r.cands.isEmpty()) {
            return;
        }
        int idx = Math.min(attempt / 2, r.cands.size() - 1);
        YtSource.nativeLike = (attempt % 2 == 1);
        final YtClient.Cand c = r.cands.get(idx);
        ErrorLog.i("Urinish " + (attempt + 1) + ": " + c.label
                + (YtSource.nativeLike ? " [zaxira rejim]" : "") + " | " + YtClient.redact(c.url));

        MediaItem.Builder mb = new MediaItem.Builder()
                .setMediaId(curUrl == null ? c.url : curUrl)
                .setUri(c.url)
                .setMediaMetadata(new MediaMetadata.Builder()
                        .setTitle(r.title)
                        .setArtist(r.channel)
                        .build());
        if (c.hls) {
            mb.setMimeType(MimeTypes.APPLICATION_M3U8);
        }
        final MediaItem item = mb.build();
        final long start = resumeMs;
        withPlayer(new Runnable() {
            @Override
            public void run() {
                player.setMediaItem(item, start);
                player.prepare();
                player.play();
                updatePlayButton();
            }
        });
    }

    private void onPlayerFailure(PlaybackException e) {
        ErrorLog.e("Ijro xatosi " + e.getErrorCodeName() + " (urinish " + (attempt + 1) + ")", e);
        if (cur == null) {
            say("Ijro xatosi: " + e.getErrorCodeName() + ". Xato jurnalini yuborib bering.");
            return;
        }
        if (player != null) {
            resumeMs = Math.max(resumeMs, player.getCurrentPosition());
        }
        attempt++;
        if (attempt < cur.cands.size() * 2) {
            startAttempt();
            return;
        }
        if (!reResolved && curUrl != null) {
            reResolved = true;
            say("Havola eskirgan bo'lishi mumkin. Qayta olinmoqda...");
            reResolve();
            return;
        }
        say("Ijro etib bo'lmadi: " + e.getErrorCodeName()
                + ". Pastdagi Xato jurnalini yuborish tugmasi orqali jurnalni yuboring.");
    }

    private void reResolve() {
        final int my = token.incrementAndGet();
        final String url = curUrl;
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final YtClient.Resolved r = YtClient.resolve(url);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (my == token.get()) {
                                cur = r;
                                attempt = 0;
                                startAttempt();
                            }
                        }
                    });
                } catch (final Throwable ex) {
                    ErrorLog.e("Havolani qayta olishda xato", ex);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (my == token.get()) {
                                say("Qayta olib bo'lmadi. " + YtClient.explain(ex));
                            }
                        }
                    });
                }
            }
        });
    }

    // ---------------------------------------------------------------- boshqaruv tugmalari

    private void syncFromPlayer() {
        if (player == null || player.getMediaItemCount() == 0) {
            return;
        }
        CharSequence t = player.getMediaMetadata().title;
        nowTitle.setText(t == null ? "" : t);
        panel.setVisibility(View.VISIBLE);
        updatePlayButton();
        btnSpeed.setText(speedLabel(player.getPlaybackParameters().speed));
    }

    private void updatePlayButton() {
        if (player == null) {
            return;
        }
        int s = player.getPlaybackState();
        boolean active = player.getPlayWhenReady() && s != Player.STATE_ENDED && s != Player.STATE_IDLE;
        btnPlay.setText(active ? "Pauza" : "Davom ettirish");
    }

    private void togglePlay() {
        if (player == null) {
            return;
        }
        int s = player.getPlaybackState();
        if (player.getPlayWhenReady() && s != Player.STATE_ENDED && s != Player.STATE_IDLE) {
            player.pause();
            return;
        }
        if (s == Player.STATE_ENDED) {
            player.seekTo(0);
        }
        if (s == Player.STATE_IDLE) {
            if (cur != null) {
                resumeMs = player.getCurrentPosition();
                attempt = 0;
                reResolved = false;
                startAttempt();
                return;
            }
            player.prepare();
        }
        player.play();
    }

    private void cycleSpeed() {
        if (player == null) {
            return;
        }
        float now = player.getPlaybackParameters().speed;
        int i = 0;
        for (int k = 0; k < speeds.length; k++) {
            if (Math.abs(speeds[k] - now) < 0.01f) {
                i = k;
            }
        }
        float next = speeds[(i + 1) % speeds.length];
        player.setPlaybackParameters(new PlaybackParameters(next));
        btnSpeed.setText(speedLabel(next));
    }

    private String speedLabel(float f) {
        String s = Float.toString(f);
        if (s.endsWith(".0")) {
            s = s.substring(0, s.length() - 2);
        }
        return "Tezlik " + s + " barobar";
    }

    private void sayTime() {
        if (player == null || player.getMediaItemCount() == 0) {
            say("Hozir hech narsa ijro etilmayapti.");
            return;
        }
        long pos = player.getCurrentPosition() / 1000;
        long dur = player.getDuration();
        String s = YtClient.spoken(pos);
        if (dur != C.TIME_UNSET && dur > 0) {
            s += ". Jami: " + YtClient.spoken(dur / 1000);
        }
        say(s);
    }

    private void stopAll() {
        token.incrementAndGet();
        cur = null;
        if (player != null) {
            player.stop();
            player.clearMediaItems();
        }
        panel.setVisibility(View.GONE);
        finish();
    }

    // ---------------------------------------------------------------- tizim sozlamalari va jurnal

    private void batteryOpt() {
        try {
            if (Build.VERSION.SDK_INT < 23) {
                say("Bu Android versiyasida batareya cheklovini o'chirish shart emas.");
                return;
            }
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
                say("Batareya cheklovi allaqachon o'chirilgan. Ilova ekran o'chiq paytda ham ishlaydi.");
                return;
            }
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Throwable e) {
            ErrorLog.e("Batareya sozlamasini ochib bo'lmadi", e);
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Throwable e2) {
                say("Sozlamani ochib bo'lmadi. Telefon sozlamalaridan ilovaning batareya cheklovini o'zingiz o'chiring.");
            }
        }
    }

    private void sendLog() {
        try {
            File f = ErrorLog.file();
            if (f == null || !f.exists()) {
                say("Jurnal hali bo'sh.");
                return;
            }
            Uri u = FileProvider.getUriForFile(this, getPackageName() + ".files", f);
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_STREAM, u);
            i.putExtra(Intent.EXTRA_SUBJECT, "YouTubeni endi tinglaymiz - xato jurnali");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Xato jurnalini yuborish"));
        } catch (Throwable e) {
            ErrorLog.e("Jurnalni yuborib bo'lmadi", e);
            say("Jurnalni yuborib bo'lmadi.");
        }
    }

    private void askNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < 33) {
            return;
        }
        try {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                return;
            }
            SharedPreferences sp = getSharedPreferences("ilova", MODE_PRIVATE);
            if (sp.getBoolean("notif_asked", false)) {
                return;
            }
            sp.edit().putBoolean("notif_asked", true).apply();
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 1);
        } catch (Throwable e) {
            ErrorLog.e("Bildirishnoma ruxsatini so'rab bo'lmadi", e);
        }
    }

    // ---------------------------------------------------------------- tashqaridan kelgan havola

    private void handleIntent(Intent in) {
        if (in == null) {
            return;
        }
        String action = in.getAction();
        String text = null;
        if (Intent.ACTION_SEND.equals(action)) {
            text = in.getStringExtra(Intent.EXTRA_TEXT);
        } else if (Intent.ACTION_VIEW.equals(action)) {
            text = in.getDataString();
        }
        if (text == null) {
            return;
        }
        setIntent(new Intent(Intent.ACTION_MAIN));
        String link = YtClient.extractYoutubeUrl(text);
        if (link == null) {
            say("Bu havola YouTube havolasiga o'xshamaydi.");
            return;
        }
        input.setText(link);
        startResolve(link, null);
    }
}
