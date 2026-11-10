package ua.facecam;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.app.RecoverableSecurityException;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQ_PERMS = 10;
    private static final int REQ_DELETE = 11;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView statusTitle, statusTime, statusSub, emptyList;
    private Button btnRec;
    private LinearLayout list;
    private View[] settingViews;
    private int lastState = -1;
    private boolean resumed;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            refreshStatus();
            if (resumed) ui.postDelayed(this, 250);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        RecorderService.createChannels(this);

        View root = findViewById(R.id.root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });

        statusTitle = findViewById(R.id.statusTitle);
        statusTime = findViewById(R.id.statusTime);
        statusSub = findViewById(R.id.statusSub);
        emptyList = findViewById(R.id.emptyList);
        btnRec = findViewById(R.id.btnRec);
        list = findViewById(R.id.list);

        SharedPreferences sp = Prefs.sp(this);
        Prefs p = Prefs.load(this);

        RadioButton rbFront = findViewById(R.id.rbFront), rbBack = findViewById(R.id.rbBack);
        (p.front ? rbFront : rbBack).setChecked(true);
        rbFront.setOnCheckedChangeListener((b, c) -> sp.edit().putBoolean("front", c).apply());

        RadioButton rb1080 = findViewById(R.id.rb1080), rb720 = findViewById(R.id.rb720);
        (p.hd1080 ? rb1080 : rb720).setChecked(true);
        rb1080.setOnCheckedChangeListener((b, c) -> sp.edit().putBoolean("hd1080", c).apply());

        Switch swAudio = findViewById(R.id.swAudio);
        swAudio.setChecked(p.audio);
        swAudio.setOnCheckedChangeListener((b, c) -> sp.edit().putBoolean("audio", c).apply());

        Spinner spDelay = findViewById(R.id.spDelay);
        setupSpinner(spDelay, Prefs.DELAY_LABELS, p.delayIdx, i -> sp.edit().putInt("delay", i).apply());
        Spinner spAuto = findViewById(R.id.spAutoStop);
        setupSpinner(spAuto, Prefs.AUTOSTOP_LABELS, p.autoStopIdx, i -> sp.edit().putInt("autostop", i).apply());

        Switch swHidden = findViewById(R.id.swHidden);
        View hiddenHint = findViewById(R.id.hiddenHint);
        Button btnChannel = findViewById(R.id.btnChannel);
        swHidden.setChecked(p.hiddenNotif);
        CompoundButton.OnCheckedChangeListener hl = (b, c) -> {
            sp.edit().putBoolean("hidden", c).apply();
            hiddenHint.setVisibility(c ? View.VISIBLE : View.GONE);
            btnChannel.setVisibility(c ? View.VISIBLE : View.GONE);
        };
        swHidden.setOnCheckedChangeListener(hl);
        hl.onCheckedChanged(swHidden, p.hiddenNotif);
        btnChannel.setOnClickListener(v -> openHiddenChannelSettings());

        settingViews = new View[]{rbFront, rbBack, rb1080, rb720, swAudio, spDelay, spAuto, swHidden};

        btnRec.setOnClickListener(v -> {
            if (RecorderService.state == RecorderService.IDLE) startWithPermissions();
            else stopRecording();
        });
    }

    interface IntCb { void on(int i); }

    private void setupSpinner(Spinner s, String[] labels, int sel, IntCb cb) {
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(a);
        s.setSelection(sel);
        s.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) { cb.on(pos); }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        lastState = -1;
        ui.post(ticker);
        loadRecordings();
    }

    @Override
    protected void onPause() {
        resumed = false;
        ui.removeCallbacks(ticker);
        super.onPause();
    }

    // ---------------------------------------------------------------- запис

    private void startWithPermissions() {
        Prefs p = Prefs.load(this);
        List<String> need = new ArrayList<>();
        if (!granted(Manifest.permission.CAMERA)) need.add(Manifest.permission.CAMERA);
        if (p.audio && !granted(Manifest.permission.RECORD_AUDIO)) need.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33 && !p.hiddenNotif && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (need.isEmpty()) { startRecording(); return; }
        requestPermissions(need.toArray(new String[0]), REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_PERMS) return;
        if (!granted(Manifest.permission.CAMERA)) {
            Toast.makeText(this, "Без дозволу на камеру запис неможливий", Toast.LENGTH_LONG).show();
            return;
        }
        if (Prefs.load(this).audio && !granted(Manifest.permission.RECORD_AUDIO)) {
            Toast.makeText(this, "Мікрофон не дозволено — запис буде без звуку", Toast.LENGTH_LONG).show();
        }
        startRecording();
    }

    private boolean granted(String perm) {
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    private void startRecording() {
        Intent i = new Intent(this, RecorderService.class).setAction(RecorderService.ACTION_START);
        try {
            startForegroundService(i);
        } catch (Exception e) {
            Toast.makeText(this, "Не вдалося запустити: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        Prefs p = Prefs.load(this);
        String msg = p.delaySec() > 0
                ? "Запис почнеться через " + p.delaySec() + " с — можна переходити в іншу програму"
                : "Запис пішов — можна переходити в іншу програму";
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private void stopRecording() {
        Intent i = new Intent(this, RecorderService.class).setAction(RecorderService.ACTION_STOP);
        startService(i);
    }

    private void refreshStatus() {
        int st = RecorderService.state;
        long now = System.currentTimeMillis();

        if (st == RecorderService.RECORDING) {
            long el = now - RecorderService.startedAtWall;
            statusTitle.setText("● Йде запис");
            statusTitle.setTextColor(getColor(R.color.accent));
            statusTime.setText(fmtDuration(el));
            long auto = RecorderService.autoStopAtWall;
            statusSub.setText(auto > 0 ? "Автостоп через " + fmtDuration(Math.max(0, auto - now)) : "Без автостопу");
        } else if (st == RecorderService.COUNTDOWN) {
            long left = RecorderService.countdownUntilWall - now;
            statusTitle.setText(left > 0 ? "Старт через" : "Запуск камери…");
            statusTitle.setTextColor(statusSub.getCurrentTextColor());
            statusTime.setText(left > 0 ? String.valueOf((left + 999) / 1000) : "…");
            statusSub.setText("Переходьте в Instagram чи сценарій");
        } else if (st == RecorderService.FINISHING) {
            statusTitle.setText("Зберігаю відео…");
            statusSub.setText("");
        }

        if (st == lastState) return;
        int prev = lastState;
        lastState = st;

        boolean idle = st == RecorderService.IDLE;
        btnRec.setText(idle ? "●  Почати запис" : "■  Зупинити і зберегти");
        btnRec.setBackgroundResource(idle ? R.drawable.btn_rec : R.drawable.btn_stop);
        btnRec.setEnabled(st != RecorderService.FINISHING);
        for (View v : settingViews) v.setEnabled(idle);

        if (idle) {
            statusTitle.setText("Готово до запису");
            statusTitle.setTextColor(statusSub.getCurrentTextColor());
            statusTime.setText("00:00");
            Prefs p = Prefs.load(this);
            statusSub.setText((p.front ? "Фронтальна" : "Основна") + " · " + (p.hd1080 ? "1080p" : "720p")
                    + (p.audio ? " · зі звуком" : " · без звуку"));
            if (prev != -1 && prev != RecorderService.IDLE) {
                loadRecordings();
                String err = RecorderService.lastError;
                if (err != null) Toast.makeText(this, err, Toast.LENGTH_LONG).show();
                else if (RecorderService.lastSavedUri != null)
                    Toast.makeText(this, "Відео збережено в галерею", Toast.LENGTH_SHORT).show();
            }
        }
    }

    static String fmtDuration(long ms) {
        long s = ms / 1000;
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
                     : String.format(Locale.US, "%02d:%02d", m, sec);
    }

    private void openHiddenChannelSettings() {
        Intent i = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())
                .putExtra(Settings.EXTRA_CHANNEL_ID, RecorderService.CH_HIDDEN);
        try {
            startActivity(i);
            Toast.makeText(this, "Вимкніть перемикач «Запис (приховане)». Запис усе одно працюватиме",
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
        }
    }

    // ---------------------------------------------------------------- список записів

    private static final class Rec {
        Uri uri; String name; long durationMs, size, dateSec;
    }

    private void loadRecordings() {
        List<Rec> recs = new ArrayList<>();
        Uri coll = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        String[] proj = {MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION, MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DATE_ADDED};
        String sel = MediaStore.Video.Media.RELATIVE_PATH + " LIKE ?";
        String[] args = {"Movies/FaceCam%"};
        try (Cursor c = getContentResolver().query(coll, proj, sel, args,
                MediaStore.Video.Media.DATE_ADDED + " DESC")) {
            if (c != null) {
                while (c.moveToNext() && recs.size() < 100) {
                    Rec r = new Rec();
                    r.uri = ContentUris.withAppendedId(coll, c.getLong(0));
                    r.name = c.getString(1);
                    r.durationMs = c.getLong(2);
                    r.size = c.getLong(3);
                    r.dateSec = c.getLong(4);
                    recs.add(r);
                }
            }
        } catch (Exception ignored) { }

        list.removeAllViews();
        emptyList.setVisibility(recs.isEmpty() ? View.VISIBLE : View.GONE);
        LayoutInflater inf = LayoutInflater.from(this);
        SimpleDateFormat df = new SimpleDateFormat("d MMM, HH:mm", new Locale("uk"));
        for (Rec r : recs) {
            View row = inf.inflate(R.layout.item_recording, list, false);
            ((TextView) row.findViewById(R.id.itemTitle)).setText(df.format(new Date(r.dateSec * 1000)));
            String dur = r.durationMs > 0 ? fmtDuration(r.durationMs) : "--:--";
            ((TextView) row.findViewById(R.id.itemSub)).setText(dur + " · " + Formatter.formatShortFileSize(this, r.size));
            row.findViewById(R.id.itemBody).setOnClickListener(v -> play(r.uri));
            row.findViewById(R.id.itemShare).setOnClickListener(v -> share(r.uri));
            row.findViewById(R.id.itemDelete).setOnClickListener(v -> confirmDelete(r));
            list.addView(row);
        }
    }

    private void play(Uri uri) {
        Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(i); } catch (Exception e) {
            Toast.makeText(this, "Немає програми для перегляду відео", Toast.LENGTH_SHORT).show();
        }
    }

    private void share(Uri uri) {
        Intent send = new Intent(Intent.ACTION_SEND).setType("video/mp4")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(ClipData.newRawUri("video", uri));
        startActivity(Intent.createChooser(send, "Поділитися відео"));
    }

    private Uri pendingDelete;

    private void confirmDelete(Rec r) {
        new AlertDialog.Builder(this)
                .setTitle("Видалити запис?")
                .setMessage(r.name)
                .setNegativeButton("Скасувати", null)
                .setPositiveButton("Видалити", (d, w) -> delete(r.uri))
                .show();
    }

    private void delete(Uri uri) {
        ContentResolver cr = getContentResolver();
        try {
            cr.delete(uri, null, null);
            loadRecordings();
        } catch (SecurityException se) {
            try {
                pendingDelete = uri;
                if (Build.VERSION.SDK_INT >= 30) {
                    startIntentSenderForResult(MediaStore.createDeleteRequest(cr, java.util.Collections.singletonList(uri))
                            .getIntentSender(), REQ_DELETE, null, 0, 0, 0);
                } else if (se instanceof RecoverableSecurityException) {
                    startIntentSenderForResult(((RecoverableSecurityException) se).getUserAction()
                            .getActionIntent().getIntentSender(), REQ_DELETE, null, 0, 0, 0);
                }
            } catch (Exception e) {
                Toast.makeText(this, "Не вдалося видалити", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_DELETE && resultCode == RESULT_OK) {
            if (Build.VERSION.SDK_INT < 30 && pendingDelete != null) {
                try { getContentResolver().delete(pendingDelete, null, null); } catch (Exception ignored) { }
            }
            pendingDelete = null;
            loadRecordings();
        }
    }
}
