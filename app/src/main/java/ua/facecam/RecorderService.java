package ua.facecam;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.hardware.display.DisplayManager;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.util.Range;
import android.util.Size;
import android.view.Display;
import android.view.Surface;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Executor;

/**
 * Записує відео з камери у фоні без жодного прев'ю на екрані.
 * Працює як foreground-сервіс (так вимагає Android для доступу до камери у фоні).
 */
public class RecorderService extends Service {

    static final String ACTION_START = "ua.facecam.START";
    static final String ACTION_STOP = "ua.facecam.STOP";

    static final String CH_NORMAL = "rec_normal";
    static final String CH_HIDDEN = "rec_hidden";
    static final String CH_DONE = "rec_done";
    private static final int NOTIF_ID = 1;
    private static final int DONE_ID = 2;

    // Стан, який читає головний екран.
    static final int IDLE = 0, COUNTDOWN = 1, RECORDING = 2, FINISHING = 3;
    static volatile int state = IDLE;
    static volatile long countdownUntilWall = 0;   // System.currentTimeMillis()
    static volatile long startedAtWall = 0;
    static volatile long autoStopAtWall = 0;        // 0 = без автостопу
    static volatile String lastError = null;
    static volatile Uri lastSavedUri = null;

    private HandlerThread thread;
    private Handler bg;
    private final Handler main = new Handler(Looper.getMainLooper());

    private Prefs prefs;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private MediaRecorder recorder;
    private Surface recorderSurface;
    private ParcelFileDescriptor pfd;
    private Uri outUri;
    private PowerManager.WakeLock wakeLock;
    private boolean recorderStarted;
    private boolean stopping;
    private boolean cleaned;

    @Override
    public void onCreate() {
        super.onCreate();
        thread = new HandlerThread("FaceCamRec");
        thread.start();
        bg = new Handler(thread.getLooper());
        createChannels(this);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            if (state == IDLE) { stopSelf(); } else { bg.post(() -> stopInternal(null)); }
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) {
            if (state != IDLE) {
                // Вже пишемо: Android все одно чекає startForeground на кожен такий запуск.
                try { startForeground(NOTIF_ID, buildRecordingNotification()); } catch (RuntimeException ignored) { }
                return START_NOT_STICKY;
            }
            begin();
            return START_NOT_STICKY;
        }
        if (state == IDLE) stopSelf();
        return START_NOT_STICKY;
    }

    // ---------------------------------------------------------------- старт

    private void begin() {
        prefs = Prefs.load(this);
        lastError = null;
        stopping = false;
        recorderStarted = false;

        int delay = prefs.delaySec();
        long now = System.currentTimeMillis();
        countdownUntilWall = delay > 0 ? now + delay * 1000L : 0;
        state = COUNTDOWN; // "готуємось" (з затримкою або без)
        cleaned = false;

        // Android вимагає показати сповіщення одразу після старту сервісу.
        Notification n = buildRecordingNotification();
        if (Build.VERSION.SDK_INT >= 30) {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
            if (prefs.audio && hasPerm(Manifest.permission.RECORD_AUDIO)) {
                type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            }
            try {
                startForeground(NOTIF_ID, n, type);
            } catch (RuntimeException e) {
                lastError = "Android не дозволив запуск: " + e.getMessage();
                state = IDLE;
                stopSelf();
                return;
            }
        } else {
            startForeground(NOTIF_ID, n);
        }

        PowerManager pm = getSystemService(PowerManager.class);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FaceCam:rec");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire(4 * 60 * 60 * 1000L);

        bg.postDelayed(this::openCamera, delay * 1000L);
    }

    private boolean hasPerm(String p) {
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("MissingPermission")
    private void openCamera() {
        if (stopping) return;
        if (!hasPerm(Manifest.permission.CAMERA)) {
            stopInternal("Немає дозволу на камеру");
            return;
        }
        CameraManager cm = getSystemService(CameraManager.class);
        try {
            String id = pickCamera(cm, prefs.front);
            if (id == null) { stopInternal("Камеру не знайдено"); return; }
            CameraCharacteristics ch = cm.getCameraCharacteristics(id);
            setupRecorder(ch);
            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice c) {
                    if (cleaned) { c.close(); return; }
                    camera = c;
                    if (stopping) { stopInternal(null); return; }
                    createSession(ch);
                }
                @Override public void onDisconnected(CameraDevice c) {
                    if (cleaned) { c.close(); return; }
                    camera = c;
                    stopInternal(recorderStarted ? "Камеру забрала інша програма — запис збережено"
                                                 : "Камера зайнята іншою програмою");
                }
                @Override public void onError(CameraDevice c, int error) {
                    if (cleaned) { c.close(); return; }
                    camera = c;
                    stopInternal("Помилка камери (" + error + ")");
                }
            }, bg);
        } catch (Exception e) {
            stopInternal("Не вдалося відкрити камеру: " + e.getMessage());
        }
    }

    private static String pickCamera(CameraManager cm, boolean front) throws CameraAccessException {
        int want = front ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK;
        String[] ids = cm.getCameraIdList();
        for (String id : ids) {
            Integer f = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (f != null && f == want) return id;
        }
        return ids.length > 0 ? ids[0] : null;
    }

    private void setupRecorder(CameraCharacteristics ch) throws Exception {
        StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size size = chooseSize(map.getOutputSizes(MediaRecorder.class), prefs.hd1080 ? 1080 : 720);

        // Файл одразу в галерею: Movies/FaceCam
        String name = "FaceCam_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4";
        ContentValues v = new ContentValues();
        v.put(MediaStore.Video.Media.DISPLAY_NAME, name);
        v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        v.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/FaceCam");
        v.put(MediaStore.Video.Media.IS_PENDING, 1);
        ContentResolver cr = getContentResolver();
        outUri = cr.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
        if (outUri == null) throw new IllegalStateException("не вдалося створити файл");
        pfd = cr.openFileDescriptor(outUri, "rw");

        boolean audio = prefs.audio && hasPerm(Manifest.permission.RECORD_AUDIO);
        recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
        // Для фронталки — основний мікрофон (знизу, ближче до обличчя), для основної — "камкордерний".
        if (audio) recorder.setAudioSource(prefs.front ? MediaRecorder.AudioSource.MIC
                                                       : MediaRecorder.AudioSource.CAMCORDER);
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setOutputFile(pfd.getFileDescriptor());
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        recorder.setVideoSize(size.getWidth(), size.getHeight());
        recorder.setVideoFrameRate(30);
        recorder.setVideoEncodingBitRate(size.getHeight() >= 1080 ? 8_000_000 : 4_000_000);
        if (audio) {
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioEncodingBitRate(128_000);
            recorder.setAudioSamplingRate(44_100);
            recorder.setAudioChannels(1);
        }
        recorder.setOrientationHint(orientationHint(ch));
        int autoMin = prefs.autoStopMin();
        if (autoMin > 0) recorder.setMaxDuration(autoMin * 60_000);
        recorder.setMaxFileSize(3_900_000_000L);
        recorder.setOnInfoListener((mr, what, extra) -> {
            if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED
                    || what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
                bg.post(() -> stopInternal(null));
            }
        });
        recorder.setOnErrorListener((mr, what, extra) ->
                bg.post(() -> stopInternal("Помилка запису (" + what + ")")));
        recorder.prepare();
        recorderSurface = recorder.getSurface();
    }

    private static Size chooseSize(Size[] sizes, int targetH) {
        Size best = null;
        for (Size s : sizes) {   // точне 16:9 потрібної висоти
            if (s.getHeight() == targetH && s.getWidth() * 9 == s.getHeight() * 16) return s;
        }
        for (Size s : sizes) {   // найбільше 16:9 не вище за ціль
            if (s.getWidth() * 9 == s.getHeight() * 16 && s.getHeight() <= targetH
                    && (best == null || s.getHeight() > best.getHeight())) best = s;
        }
        if (best != null) return best;
        for (Size s : sizes) {
            if (s.getHeight() <= targetH && (best == null || s.getWidth() * s.getHeight() > best.getWidth() * best.getHeight())) best = s;
        }
        return best != null ? best : sizes[0];
    }

    private int orientationHint(CameraCharacteristics ch) {
        Integer so = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
        int sensor = so == null ? 0 : so;
        int degrees = 0;
        try {
            Display d = getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
            degrees = d.getRotation() * 90;
        } catch (Exception ignored) { }
        Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
        boolean front = facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT;
        return front ? (sensor + degrees) % 360 : (sensor - degrees + 360) % 360;
    }

    private void createSession(CameraCharacteristics ch) {
        try {
            Executor ex = r -> bg.post(r);
            SessionConfiguration cfg = new SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                    Collections.singletonList(new OutputConfiguration(recorderSurface)), ex,
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession s) {
                            if (cleaned) { s.close(); return; }
                            session = s;
                            if (stopping) { stopInternal(null); return; }
                            startRecording(ch);
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession s) {
                            stopInternal("Камера не підтримує такий режим запису");
                        }
                    });
            camera.createCaptureSession(cfg);
        } catch (Exception e) {
            stopInternal("Помилка камери: " + e.getMessage());
        }
    }

    private void startRecording(CameraCharacteristics ch) {
        try {
            CaptureRequest.Builder b = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            b.addTarget(recorderSurface);
            b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            Range<Integer> fps = pickFps(ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES));
            if (fps != null) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps);
            session.setRepeatingRequest(b.build(), null, bg);
            recorder.start();
            recorderStarted = true;
            startedAtWall = System.currentTimeMillis();
            int autoMin = prefs.autoStopMin();
            autoStopAtWall = autoMin > 0 ? startedAtWall + autoMin * 60_000L : 0;
            state = RECORDING;
            notifyUpdate();
        } catch (Exception e) {
            stopInternal("Не вдалося почати запис: " + e.getMessage());
        }
    }

    private static Range<Integer> pickFps(Range<Integer>[] ranges) {
        if (ranges == null) return null;
        Range<Integer> best = null;
        for (Range<Integer> r : ranges) {
            if (r.getUpper() == 30 && (best == null || r.getLower() > best.getLower())) best = r;
        }
        return best;
    }

    // ---------------------------------------------------------------- стоп

    /** Викликати лише з фонового потоку bg. */
    private void stopInternal(String error) {
        if (cleaned) return;
        cleaned = true;
        stopping = true;
        bg.removeCallbacksAndMessages(null);
        if (error != null) lastError = error;
        state = FINISHING;

        long durationMs = startedAtWall > 0 ? System.currentTimeMillis() - startedAtWall : 0;
        boolean saved = false;

        if (session != null) {
            try { session.stopRepeating(); } catch (Exception ignored) { }
        }
        if (recorder != null) {
            if (recorderStarted) {
                try { recorder.stop(); saved = true; } catch (RuntimeException ignored) { }
            }
            try { recorder.reset(); } catch (Exception ignored) { }
            recorder.release();
            recorder = null;
        }
        if (session != null) { try { session.close(); } catch (Exception ignored) { } session = null; }
        if (camera != null) { try { camera.close(); } catch (Exception ignored) { } camera = null; }
        if (recorderSurface != null) { recorderSurface.release(); recorderSurface = null; }
        if (pfd != null) { try { pfd.close(); } catch (Exception ignored) { } pfd = null; }

        Uri finished = null;
        if (outUri != null) {
            ContentResolver cr = getContentResolver();
            try {
                if (saved) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Video.Media.IS_PENDING, 0);
                    cr.update(outUri, v, null, null);
                    finished = outUri;
                } else {
                    cr.delete(outUri, null, null);
                }
            } catch (Exception ignored) { }
            outUri = null;
        }
        if (finished != null) lastSavedUri = finished;

        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        recorderStarted = false;
        startedAtWall = 0;
        autoStopAtWall = 0;
        countdownUntilWall = 0;

        final Uri doneUri = finished;
        final boolean showDone = prefs != null && !prefs.hiddenNotif;
        main.post(() -> {
            stopForeground(STOP_FOREGROUND_REMOVE);
            if (doneUri != null && showDone) postDoneNotification(doneUri, durationMs);
            state = IDLE;
            stopSelf();
        });
    }

    @Override
    public void onDestroy() {
        if (state != IDLE && bg != null) {
            // Закриваємо все синхронно, щоб файл не зіпсувався.
            final Object lock = new Object();
            synchronized (lock) {
                bg.post(() -> { stopInternal(null); synchronized (lock) { lock.notifyAll(); } });
                try { lock.wait(3000); } catch (InterruptedException ignored) { }
            }
            state = IDLE;
        }
        if (thread != null) thread.quitSafely();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- сповіщення

    static void createChannels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel normal = new NotificationChannel(CH_NORMAL, "Запис (звичайне)", NotificationManager.IMPORTANCE_LOW);
        normal.setDescription("Сповіщення під час запису з кнопкою «Стоп»");
        normal.setShowBadge(false);
        NotificationChannel hidden = new NotificationChannel(CH_HIDDEN, "Запис (приховане)", NotificationManager.IMPORTANCE_MIN);
        hidden.setDescription("Мінімальне сповіщення без значка в рядку стану. Можна вимкнути повністю.");
        hidden.setShowBadge(false);
        NotificationChannel done = new NotificationChannel(CH_DONE, "Готові записи", NotificationManager.IMPORTANCE_DEFAULT);
        done.setDescription("Повідомлення, що відео збережено, з кнопкою «Поділитися»");
        done.setSound(null, null);
        nm.createNotificationChannel(normal);
        nm.createNotificationChannel(hidden);
        nm.createNotificationChannel(done);
    }

    private PendingIntent openAppIntent() {
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent stopIntent() {
        Intent i = new Intent(this, RecorderService.class).setAction(ACTION_STOP);
        return PendingIntent.getService(this, 1, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification buildRecordingNotification() {
        boolean hidden = prefs != null && prefs.hiddenNotif;
        Notification.Builder b = new Notification.Builder(this, hidden ? CH_HIDDEN : CH_NORMAL)
                .setSmallIcon(R.drawable.ic_stat_rec)
                .setContentIntent(openAppIntent())
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_SECRET);
        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(hidden ? Notification.FOREGROUND_SERVICE_DEFERRED
                                                  : Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }

        if (hidden) {
            b.setContentTitle("FaceCam").setContentText("Відкрийте програму, щоб зупинити")
             .setOngoing(false).setShowWhen(false)
             .addAction(new Notification.Action.Builder(null, "Стоп", stopIntent()).build());
            return b.build();
        }

        b.setOngoing(true)
         .addAction(new Notification.Action.Builder(null, "■  Стоп і зберегти", stopIntent()).build());
        if (state == RECORDING) {
            String cam = prefs.front ? "фронтальна" : "основна";
            b.setContentTitle("● Йде запис · " + cam);
            b.setUsesChronometer(true).setShowWhen(true);
            if (autoStopAtWall > 0) {
                b.setWhen(autoStopAtWall).setChronometerCountDown(true)
                 .setContentText("Автостоп через таймер. Натисніть «Стоп», щоб зберегти раніше");
            } else {
                b.setWhen(startedAtWall).setContentText("Натисніть «Стоп», щоб зберегти відео");
            }
        } else if (countdownUntilWall > System.currentTimeMillis()) {
            b.setContentTitle("Запис почнеться за кілька секунд")
             .setUsesChronometer(true).setChronometerCountDown(true).setWhen(countdownUntilWall)
             .setContentText("Можна перемикатися в іншу програму");
        } else {
            b.setContentTitle("Запуск камери…");
        }
        return b.build();
    }

    private void notifyUpdate() {
        main.post(() -> {
            if (state == IDLE) return;
            getSystemService(NotificationManager.class).notify(NOTIF_ID, buildRecordingNotification());
        });
    }

    private void postDoneNotification(Uri uri, long durationMs) {
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("video/mp4")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(ClipData.newRawUri("video", uri));
        Intent chooser = Intent.createChooser(send, "Поділитися відео");
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        PendingIntent sharePi = PendingIntent.getActivity(this, 2, chooser,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent viewPi = PendingIntent.getActivity(this, 3, view,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification n = new Notification.Builder(this, CH_DONE)
                .setSmallIcon(R.drawable.ic_stat_rec)
                .setContentTitle("Відео збережено (" + MainActivity.fmtDuration(durationMs) + ")")
                .setContentText("Галерея → Movies/FaceCam. Натисніть, щоб переглянути")
                .setContentIntent(viewPi)
                .setAutoCancel(true)
                .addAction(new Notification.Action.Builder(null, "Поділитися", sharePi).build())
                .addAction(new Notification.Action.Builder(null, "Мої записи", openAppIntent()).build())
                .build();
        getSystemService(NotificationManager.class).notify(DONE_ID, n);
    }
}
