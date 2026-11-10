package ua.hahacam;

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
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
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

    static final String ACTION_START = "ua.hahacam.START";
    static final String ACTION_STOP = "ua.hahacam.STOP";
    static final String ACTION_GO = "ua.hahacam.GO";
    static final String EXTRA_SRC = "src";          // "app" | "notif" | "cancel"

    /** Поточний екземпляр (для передачі дозволу на запис екрана з активності). */
    static volatile RecorderService instance;

    static final String CH_NORMAL = "rec_normal";
    static final String CH_HIDDEN = "rec_hidden";
    static final String CH_DONE = "rec_done";
    private static final int NOTIF_ID = 1;
    private static final int DONE_ID = 2;

    // Стан, який читає головний екран.
    static final int IDLE = 0, COUNTDOWN = 1, RECORDING = 2, FINISHING = 3, READY = 4;
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
    private File tmpFile;
    private ScreenSession screen;
    private int fgsTypes;

    // Плаваюче вікно (прев'ю + кнопка «Почати»)
    private boolean useOverlay;
    private OverlayController overlay;
    private volatile android.graphics.SurfaceTexture previewTexture;
    private Surface previewSurface;
    private CameraCharacteristics camChars;
    private Object[] pendingProjection;      // {MediaProjection, w, h, dpi}
    private boolean goRequested;
    private PowerManager.WakeLock wakeLock;
    private boolean recorderStarted;
    private boolean stopping;
    private boolean cleaned;

    @Override
    public void onCreate() {
        super.onCreate();
        thread = new HandlerThread("HahacamRec");
        thread.start();
        bg = new Handler(thread.getLooper());
        createChannels(this);
        instance = this;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            String src = intent.getStringExtra(EXTRA_SRC);
            Session.StopSource ss = "app".equals(src) ? Session.StopSource.APP
                    : "notif".equals(src) ? Session.StopSource.NOTIFICATION : Session.StopSource.AUTO;
            boolean discard = "cancel".equals(src);
            if (state == IDLE) { stopSelf(); } else { bg.post(() -> stopInternal(null, ss, discard)); }
            return START_NOT_STICKY;
        }
        if (ACTION_GO.equals(action)) {
            bg.post(this::onGoRequested);
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

        useOverlay = prefs.overlay && OverlayController.allowed(this);
        goRequested = false;
        pendingProjection = null;
        previewTexture = null;
        int delay = useOverlay ? 0 : prefs.delaySec();   // з плаваючою кнопкою відлік — після «Почати»
        long now = System.currentTimeMillis();
        countdownUntilWall = delay > 0 ? now + delay * 1000L : 0;
        state = COUNTDOWN; // "готуємось" (з затримкою або без)
        cleaned = false;

        // Android вимагає показати сповіщення одразу після старту сервісу.
        // Камеру й мікрофон заявляємо зараз, поки програма на екрані (так вимагає Android 14+).
        Notification n = buildRecordingNotification();
        int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
        if ((prefs.audio || (prefs.screen() && prefs.appAudio)) && hasPerm(Manifest.permission.RECORD_AUDIO)) {
            type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        }
        fgsTypes = type;
        try {
            startFg(n, type);
        } catch (RuntimeException e) {
            lastError = getString(R.string.err_fgs, e.getMessage());
            state = IDLE;
            stopSelf();
            return;
        }

        PowerManager pm = getSystemService(PowerManager.class);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Hahacam:rec");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire(4 * 60 * 60 * 1000L);

        tmpFile = newTempFile();
        if (useOverlay) main.post(this::showOverlay);
        if (prefs.screen()) {
            // Чекаємо, поки користувач дозволить запис екрана (onProjectionGranted).
            bg.postDelayed(() -> { if (state == COUNTDOWN && screen == null) stopInternal(null, Session.StopSource.AUTO, true); }, 120_000);
            return;
        }
        if (!useOverlay) bg.postDelayed(this::openCamera, delay * 1000L);
        // з плаваючим вікном камера відкриється, щойно буде готове прев'ю (maybeArm)
    }

    // ---------------------------------------------------------------- плаваюче вікно

    private void showOverlay() {
        if (cleaned) return;
        overlay = new OverlayController(this, new OverlayController.Listener() {
            @Override public void onPreviewTexture(android.graphics.SurfaceTexture st) {
                previewTexture = st;
                bg.post(RecorderService.this::maybeArm);
            }
            @Override public void onStartTap() { bg.post(RecorderService.this::onGoRequested); }
            @Override public void onStopTap() { bg.post(() -> stopInternal(null, Session.StopSource.NOTIFICATION, false)); }
            @Override public void onCancelTap() { bg.post(() -> stopInternal(null, Session.StopSource.AUTO, true)); }
        });
        if (!overlay.show()) {
            // Не вдалося показати вікно — працюємо як без нього.
            overlay = null;
            bg.post(() -> {
                useOverlay = false;
                if (cleaned) return;
                if (prefs.screen()) {
                    if (pendingProjection != null) {
                        Object[] p = pendingProjection;
                        pendingProjection = null;
                        startScreen((MediaProjection) p[0], (int) p[1], (int) p[2], (int) p[3]);
                    }
                } else {
                    openCamera();
                }
            });
        }
    }

    /** (bg) Коли є і прев'ю, і (для екрана) дозвіл — готуємо все й переходимо в READY. */
    private void maybeArm() {
        if (cleaned || previewTexture == null) return;
        if (prefs.screen()) {
            if (pendingProjection != null && screen == null) {
                Object[] p = pendingProjection;
                pendingProjection = null;
                startScreen((MediaProjection) p[0], (int) p[1], (int) p[2], (int) p[3]);
            }
        } else if (camera == null && recorder == null) {
            openCamera();
        }
    }

    /** (bg) Натиснули «Почати»: відлік (якщо задано), потім запис. */
    private void onGoRequested() {
        if (state != READY || goRequested || cleaned) return;
        goRequested = true;
        int delay = prefs.delaySec();
        countdownUntilWall = delay > 0 ? System.currentTimeMillis() + delay * 1000L : 0;
        notifyUpdate();
        bg.postDelayed(this::go, delay * 1000L);
    }

    private void go() {
        if (cleaned || state != READY) return;
        countdownUntilWall = 0;
        if (screen != null) {
            screen.go();
            markStarted();
        } else if (camChars != null) {
            startRecording(camChars);
        }
    }

    private void markStarted() {
        recorderStarted = true;
        startedAtWall = System.currentTimeMillis();
        Session.markRecordingStart(Session.now());
        int autoMin = prefs.autoStopMin();
        autoStopAtWall = autoMin > 0 ? startedAtWall + autoMin * 60_000L : 0;
        if (autoMin > 0 && screen != null) {
            bg.postDelayed(() -> stopInternal(null, Session.StopSource.AUTO, false), autoMin * 60_000L);
        }
        state = RECORDING;
        notifyUpdate();
    }

    /** На Android 10 типів camera/microphone ще немає — там лише mediaProjection або без типу. */
    private void startFg(Notification n, int types) {
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIF_ID, n, types);
        } else if ((types & ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) != 0) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private File newTempFile() {
        File dir = getExternalCacheDir() != null ? getExternalCacheDir() : getCacheDir();
        return new File(dir, "rec_" + System.currentTimeMillis() + ".mp4");
    }

    // ---------------------------------------------------------------- режим «екран + я»

    /** Викликає MainActivity після того, як користувач дозволив запис екрана. */
    void onProjectionGranted(int resultCode, Intent data, int w, int h, int dpi) {
        if (state != COUNTDOWN || prefs == null || !prefs.screen()) return;
        try {
            // Спершу оголошуємо тип mediaProjection, і лише потім беремо MediaProjection (вимога Android 14+).
            fgsTypes |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
            startFg(buildRecordingNotification(), fgsTypes);
            MediaProjection mp = getSystemService(MediaProjectionManager.class).getMediaProjection(resultCode, data);
            if (mp == null) throw new IllegalStateException("no projection");
            if (useOverlay) {
                bg.post(() -> { pendingProjection = new Object[]{mp, w, h, dpi}; maybeArm(); });
            } else {
                bg.post(() -> startScreen(mp, w, h, dpi));
            }
        } catch (Exception e) {
            bg.post(() -> stopInternal(getString(R.string.err_start, e.getMessage()), Session.StopSource.AUTO, true));
        }
    }

    private void startScreen(MediaProjection mp, int w, int h, int dpi) {
        if (cleaned) { mp.stop(); return; }
        ScreenSession.Options o = new ScreenSession.Options();
        // Ширина кадру до 1080, висота кратна 16, пропорції як у екрана.
        int portraitW = Math.min(w, h), portraitH = Math.max(w, h);
        boolean landscape = w > h;
        int outShort = Math.min(1080, portraitW) & ~1;
        int outLong = ScreenSession.align16(Math.round(portraitH * (outShort / (float) portraitW)));
        o.outW = landscape ? outLong : outShort;
        o.outH = landscape ? outShort : outLong;
        o.dpi = dpi;
        o.frontCamera = prefs.front;
        boolean micOk = hasPerm(Manifest.permission.RECORD_AUDIO);
        o.mic = prefs.audio && micOk;
        o.appAudio = prefs.appAudio && micOk;
        o.corner = prefs.corner;
        o.sizeFrac = prefs.bubbleFrac();
        screen = new ScreenSession(this, mp, o, tmpFile, new ScreenSession.Listener() {
            @Override public void onFatal(String message) {
                bg.post(() -> stopInternal(message, Session.StopSource.AUTO, false));
            }
            @Override public void onProjectionStopped() {
                bg.post(() -> stopInternal(null, Session.StopSource.AUTO, false));
            }
        });
        try {
            screen.start(useOverlay ? previewTexture : null, useOverlay);
        } catch (Exception e) {
            stopInternal(getString(R.string.err_start, e.getMessage()), Session.StopSource.AUTO, true);
            return;
        }
        if (useOverlay) {
            final float a = screen.uprightAspect;
            main.post(() -> { if (overlay != null) overlay.setUprightAspect(a); });
            state = READY;
            notifyUpdate();
        } else {
            markStarted();
        }
    }

    private boolean hasPerm(String p) {
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("MissingPermission")
    private void openCamera() {
        if (stopping) return;
        if (!hasPerm(Manifest.permission.CAMERA)) {
            stopInternal(getString(R.string.err_no_camera_perm));
            return;
        }
        CameraManager cm = getSystemService(CameraManager.class);
        try {
            String id = pickCamera(cm, prefs.front);
            if (id == null) { stopInternal(getString(R.string.err_no_camera)); return; }
            CameraCharacteristics ch = cm.getCameraCharacteristics(id);
            camChars = ch;
            setupRecorder(ch);
            if (useOverlay && previewTexture != null) {
                StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                Size ps = chooseSize(map.getOutputSizes(android.graphics.SurfaceTexture.class), 720);
                previewTexture.setDefaultBufferSize(ps.getWidth(), ps.getHeight());
                previewSurface = new Surface(previewTexture);
                Integer so = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
                boolean swap = so != null && so % 180 != 0;
                final float a = swap ? ps.getHeight() / (float) ps.getWidth() : ps.getWidth() / (float) ps.getHeight();
                main.post(() -> { if (overlay != null) overlay.setUprightAspect(a); });
            }
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
                    stopInternal(getString(recorderStarted ? R.string.err_disconnected_saved
                                                           : R.string.err_camera_busy));
                }
                @Override public void onError(CameraDevice c, int error) {
                    if (cleaned) { c.close(); return; }
                    camera = c;
                    stopInternal(getString(R.string.err_camera_code, error));
                }
            }, bg);
        } catch (Exception e) {
            stopInternal(getString(R.string.err_open, e.getMessage()));
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

        // Пишемо в тимчасовий файл; у галерею він потрапить після автообрізання.
        boolean audio = prefs.audio && hasPerm(Manifest.permission.RECORD_AUDIO);
        recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
        // Для фронталки — основний мікрофон (знизу, ближче до обличчя), для основної — "камкордерний".
        if (audio) recorder.setAudioSource(prefs.front ? MediaRecorder.AudioSource.MIC
                                                       : MediaRecorder.AudioSource.CAMCORDER);
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setOutputFile(tmpFile);
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
                bg.post(() -> stopInternal(null, Session.StopSource.AUTO, false));
            }
        });
        recorder.setOnErrorListener((mr, what, extra) ->
                bg.post(() -> stopInternal(getString(R.string.err_record_code, what), Session.StopSource.AUTO, false)));
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
            java.util.List<OutputConfiguration> outs = new java.util.ArrayList<>();
            outs.add(new OutputConfiguration(recorderSurface));
            if (previewSurface != null) outs.add(new OutputConfiguration(previewSurface));
            SessionConfiguration cfg = new SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                    outs, ex,
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession s) {
                            if (cleaned) { s.close(); return; }
                            session = s;
                            if (stopping) { stopInternal(null); return; }
                            if (useOverlay) startPreviewOnly(ch); else startRecording(ch);
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession s) {
                            stopInternal(getString(R.string.err_config));
                        }
                    });
            camera.createCaptureSession(cfg);
        } catch (Exception e) {
            stopInternal(getString(R.string.err_camera, e.getMessage()));
        }
    }

    /** Прев'ю без запису: стан READY, чекаємо «Почати». */
    private void startPreviewOnly(CameraCharacteristics ch) {
        try {
            CaptureRequest.Builder b = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            b.addTarget(previewSurface);
            b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            Range<Integer> fps = pickFps(ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES));
            if (fps != null) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps);
            session.setRepeatingRequest(b.build(), null, bg);
            state = READY;
            notifyUpdate();
        } catch (Exception e) {
            stopInternal(getString(R.string.err_start, e.getMessage()));
        }
    }

    private void startRecording(CameraCharacteristics ch) {
        try {
            // Спершу запускаємо рекордер, потім шлемо йому кадри.
            if (useOverlay) recorder.start();
            CaptureRequest.Builder b = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            b.addTarget(recorderSurface);
            if (previewSurface != null) b.addTarget(previewSurface);
            b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            Range<Integer> fps = pickFps(ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES));
            if (fps != null) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps);
            session.setRepeatingRequest(b.build(), null, bg);
            if (!useOverlay) recorder.start();
            markStarted();
        } catch (Exception e) {
            stopInternal(getString(R.string.err_start, e.getMessage()));
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

    private void stopInternal(String error) { stopInternal(error, Session.StopSource.AUTO, false); }

    /** Викликати лише з фонового потоку bg. discard = не зберігати (скасовано). */
    private void stopInternal(String error, Session.StopSource src, boolean discard) {
        if (cleaned) return;
        cleaned = true;
        stopping = true;
        long stopElapsed = Session.now();
        bg.removeCallbacksAndMessages(null);
        if (error != null) lastError = error;
        state = FINISHING;
        notifyUpdate();

        long totalMs = startedAtWall > 0 ? System.currentTimeMillis() - startedAtWall : 0;
        boolean saved = false;

        // --- режим камери
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
        if (previewSurface != null) { previewSurface.release(); previewSurface = null; }
        camChars = null;
        if (pendingProjection != null) {
            try { ((MediaProjection) pendingProjection[0]).stop(); } catch (Exception ignored) { }
            pendingProjection = null;
        }

        // --- режим екрана
        long recStart = Session.recStart;
        if (screen != null) {
            saved = screen.stop();
            if (screen.firstSampleElapsed > 0) Session.recStart = screen.firstSampleElapsed;
            screen = null;
        }

        // --- автообрізання і збереження в галерею
        Uri finished = null;
        long durationMs = totalMs;
        if (saved && !discard && tmpFile != null && tmpFile.length() > 0) {
            long[] trim = Session.computeTrim(prefs.trim, prefs.tailSec(), src, stopElapsed);
            long[] dur = {totalMs};
            finished = saveToGallery(tmpFile, trim[0], trim[1], dur);
            durationMs = dur[0];
        }
        Session.recStart = recStart;
        Session.markRecordingEnd();
        if (tmpFile != null) { tmpFile.delete(); tmpFile = null; }
        if (finished != null) lastSavedUri = finished;

        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        recorderStarted = false;
        startedAtWall = 0;
        autoStopAtWall = 0;
        countdownUntilWall = 0;

        final Uri doneUri = finished;
        final long doneMs = durationMs;
        final boolean showDone = prefs != null && !prefs.hiddenNotif;
        main.post(() -> {
            if (overlay != null) { overlay.remove(); overlay = null; }
            stopForeground(STOP_FOREGROUND_REMOVE);
            if (doneUri != null && showDone) postDoneNotification(doneUri, doneMs);
            state = IDLE;
            stopSelf();
        });
    }

    /**
     * Копіює запис у галерею (Movies/Hahacam), за потреби обрізаючи початок і кінець без перекодування.
     * Якщо обрізати не вдалося — зберігає файл як є.
     */
    private Uri saveToGallery(File src, long startMs, long endMs, long[] durationOut) {
        String name = "Hahacam_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4";
        ContentValues v = new ContentValues();
        v.put(MediaStore.Video.Media.DISPLAY_NAME, name);
        v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        v.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Hahacam");
        v.put(MediaStore.Video.Media.IS_PENDING, 1);
        ContentResolver cr = getContentResolver();
        Uri uri = cr.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
        if (uri == null) return null;
        try (ParcelFileDescriptor pfd = cr.openFileDescriptor(uri, "rw")) {
            boolean trimmed = false;
            if (startMs > 0 || endMs >= 0) {
                try {
                    long us = Trimmer.remux(src, pfd.getFileDescriptor(), startMs * 1000L, endMs < 0 ? -1 : endMs * 1000L);
                    durationOut[0] = us / 1000L;
                    trimmed = true;
                } catch (Exception e) {
                    android.util.Log.w("Hahacam", "trim failed, saving full file", e);
                }
            }
            if (!trimmed) {
                try (FileOutputStream out = new FileOutputStream(pfd.getFileDescriptor());
                     InputStream in = new FileInputStream(src)) {
                    out.getChannel().truncate(0);
                    out.getChannel().position(0);
                    copy(in, out);
                }
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Video.Media.IS_PENDING, 0);
            cr.update(uri, done, null, null);
            return uri;
        } catch (Exception e) {
            try { cr.delete(uri, null, null); } catch (Exception ignored) { }
            lastError = getString(R.string.err_save, e.getMessage());
            return null;
        }
    }

    private static void copy(InputStream in, OutputStream out) throws java.io.IOException {
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    @Override
    public void onDestroy() {
        if (state != IDLE && bg != null) {
            // Закриваємо все синхронно, щоб файл не зіпсувався.
            final Object lock = new Object();
            synchronized (lock) {
                bg.post(() -> { stopInternal(null); synchronized (lock) { lock.notifyAll(); } });
                try { lock.wait(8000); } catch (InterruptedException ignored) { }
            }
            state = IDLE;
        }
        if (thread != null) thread.quitSafely();
        if (instance == this) instance = null;
        super.onDestroy();
    }

    // ---------------------------------------------------------------- сповіщення

    static void createChannels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel normal = new NotificationChannel(CH_NORMAL, c.getString(R.string.ch_normal), NotificationManager.IMPORTANCE_LOW);
        normal.setDescription(c.getString(R.string.ch_normal_desc));
        normal.setShowBadge(false);
        NotificationChannel hidden = new NotificationChannel(CH_HIDDEN, c.getString(R.string.ch_hidden), NotificationManager.IMPORTANCE_MIN);
        hidden.setDescription(c.getString(R.string.ch_hidden_desc));
        hidden.setShowBadge(false);
        NotificationChannel done = new NotificationChannel(CH_DONE, c.getString(R.string.ch_done), NotificationManager.IMPORTANCE_DEFAULT);
        done.setDescription(c.getString(R.string.ch_done_desc));
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
        Intent i = new Intent(this, RecorderService.class).setAction(ACTION_STOP).putExtra(EXTRA_SRC, "notif");
        return PendingIntent.getService(this, 1, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent goIntent() {
        Intent i = new Intent(this, RecorderService.class).setAction(ACTION_GO);
        return PendingIntent.getService(this, 4, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent cancelIntent() {
        Intent i = new Intent(this, RecorderService.class).setAction(ACTION_STOP).putExtra(EXTRA_SRC, "cancel");
        return PendingIntent.getService(this, 5, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
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
            b.setContentTitle(getString(R.string.app_name)).setContentText(getString(R.string.n_hidden_text))
             .setOngoing(false).setShowWhen(false)
             .addAction(new Notification.Action.Builder(null, getString(R.string.n_stop), stopIntent()).build());
            return b.build();
        }

        b.setOngoing(true)
         .addAction(new Notification.Action.Builder(null, getString(R.string.n_stop_save), stopIntent()).build());
        if (state == READY) {
            b = new Notification.Builder(this, hidden ? CH_HIDDEN : CH_NORMAL)
                    .setSmallIcon(R.drawable.ic_stat_rec)
                    .setContentIntent(openAppIntent())
                    .setOnlyAlertOnce(true)
                    .setOngoing(true)
                    .setContentTitle(getString(R.string.n_ready_title))
                    .setContentText(getString(R.string.n_ready_text))
                    .addAction(new Notification.Action.Builder(null, getString(R.string.ov_start), goIntent()).build())
                    .addAction(new Notification.Action.Builder(null, getString(R.string.cancel), cancelIntent()).build());
            if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
            return b.build();
        } else if (state == FINISHING) {
            b.setContentTitle(getString(R.string.st_saving));
        } else if (state == RECORDING) {
            String what = prefs.screen() ? getString(R.string.n_screen_lc)
                    : getString(prefs.front ? R.string.cam_front_lc : R.string.cam_back_lc);
            b.setContentTitle(getString(R.string.n_rec_title, what));
            b.setUsesChronometer(true).setShowWhen(true);
            if (autoStopAtWall > 0) {
                b.setWhen(autoStopAtWall).setChronometerCountDown(true)
                 .setContentText(getString(R.string.n_rec_text_autostop));
            } else {
                b.setWhen(startedAtWall).setContentText(getString(R.string.n_rec_text));
            }
        } else if (countdownUntilWall > System.currentTimeMillis()) {
            b.setContentTitle(getString(R.string.n_countdown_title))
             .setUsesChronometer(true).setChronometerCountDown(true).setWhen(countdownUntilWall)
             .setContentText(getString(R.string.n_countdown_text));
        } else if (prefs != null && prefs.screen()) {
            b.setContentTitle(getString(R.string.n_waiting_screen));
        } else {
            b.setContentTitle(getString(R.string.st_starting_camera));
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
        Intent chooser = Intent.createChooser(send, getString(R.string.share_title));
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        PendingIntent sharePi = PendingIntent.getActivity(this, 2, chooser,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent viewPi = PendingIntent.getActivity(this, 3, view,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification n = new Notification.Builder(this, CH_DONE)
                .setSmallIcon(R.drawable.ic_stat_rec)
                .setContentTitle(getString(R.string.n_done_title, MainActivity.fmtDuration(durationMs)))
                .setContentText(getString(R.string.n_done_text))
                .setContentIntent(viewPi)
                .setAutoCancel(true)
                .addAction(new Notification.Action.Builder(null, getString(R.string.share), sharePi).build())
                .addAction(new Notification.Action.Builder(null, getString(R.string.n_my_recordings), openAppIntent()).build())
                .build();
        getSystemService(NotificationManager.class).notify(DONE_ID, n);
    }
}
