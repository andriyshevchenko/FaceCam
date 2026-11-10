package ua.hahacam;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Режим «Екран + я в кружечку».
 * Екран (MediaProjection → VirtualDisplay) і камера (Camera2) потрапляють у дві OES-текстури,
 * OpenGL складає їх в один кадр (камера — кружечком у кутку) і віддає в H.264-кодер.
 * Звук: мікрофон і/або звук інших програм, змішані в AAC. Усе пишеться в MP4.
 * На самому екрані під час запису нічого не показується.
 */
final class ScreenSession {
    private static final String TAG = "HahacamScreen";
    private static final int FPS = 30;
    private static final int SAMPLE_RATE = 48_000;

    interface Listener {
        void onFatal(String message);
        void onProjectionStopped();
    }

    static final class Options {
        int outW, outH, dpi;
        boolean frontCamera = true;
        boolean mic = true;
        boolean appAudio = true;
        int corner;        // 0 — верх-ліво, 1 — верх-право, 2 — низ-ліво, 3 — низ-право
        float sizeFrac;    // діаметр кружечка як частка ширини
    }

    private final Context ctx;
    private final MediaProjection projection;
    private final Options opt;
    private final Listener listener;
    private final File outFile;

    private HandlerThread glThread, camThread;
    private Handler gl, cam;

    // Кодування
    private MediaCodec venc, aenc;
    private Surface encSurface;
    private MuxerSync mux;
    private Thread vDrain, audioThread;
    private volatile boolean audioRunning;
    private AudioRecord micRec, appRec;

    // EGL / GL
    private EGLDisplay eglDpy = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglCtx = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurf = EGL14.EGL_NO_SURFACE;
    private int progScreen, progBubble;
    private int texScreen, texCam;
    private SurfaceTexture stScreen, stCam;
    private Surface screenSurface, camSurface;
    private final float[] mScreen = new float[16], mCam = new float[16];
    private volatile boolean screenNew, camNew;
    private boolean haveScreen, haveCam;
    private float cropX = 1f, cropY = 1f;
    private FloatBuffer quadFull, quadBubble;
    private volatile boolean rendering;
    private long nextFrameAt;

    // Захоплення
    private VirtualDisplay vdisplay;
    private CameraDevice camera;
    private CameraCaptureSession camSession;

    volatile long firstSampleElapsed;
    /** Ширина/висота кадру камери «стоячи» (для прев'ю в плаваючому вікні). */
    volatile float uprightAspect = 0.5625f;

    private SurfaceTexture previewTexture;   // з плаваючого вікна, може бути null
    private Surface previewSurface;
    private volatile boolean active;         // false — «готовий»: прев'ю є, у файл ще не пишемо
    private boolean wantAudio;

    ScreenSession(Context ctx, MediaProjection projection, Options opt, File outFile, Listener listener) {
        this.ctx = ctx;
        this.projection = projection;
        this.opt = opt;
        this.outFile = outFile;
        this.listener = listener;
    }

    // ================================================================ старт

    /**
     * @param preview SurfaceTexture плаваючого прев'ю або null
     * @param armed   true — підготувати все, але писати лише після go()
     */
    void start(SurfaceTexture preview, boolean armed) throws Exception {
        previewTexture = preview;
        active = !armed;
        glThread = new HandlerThread("hc-gl");
        glThread.start();
        gl = new Handler(glThread.getLooper());
        camThread = new HandlerThread("hc-cam");
        camThread.start();
        cam = new Handler(camThread.getLooper());

        wantAudio = (opt.mic || opt.appAudio) && setupAudioRecorders();
        setupEncoders(wantAudio);
        mux = new MuxerSync(new MediaMuxer(outFile.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4),
                wantAudio ? aacTrackFormat() : null, () -> {
                    Bundle b = new Bundle();
                    b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                    try { venc.setParameters(b); } catch (Exception ignored) { }
                });
        mux.onFirstSample = () -> firstSampleElapsed = SystemClock.elapsedRealtime();

        // GL-контекст і текстури створюємо на GL-потоці й чекаємо.
        final Exception[] err = {null};
        CountDownLatch ready = new CountDownLatch(1);
        gl.post(() -> {
            try { setupGl(); } catch (Exception e) { err[0] = e; }
            ready.countDown();
        });
        if (!ready.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("GL init timeout");
        if (err[0] != null) throw err[0];

        // Екран
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { listener.onProjectionStopped(); }
        }, gl);
        vdisplay = projection.createVirtualDisplay("Hahacam", opt.outW, opt.outH, opt.dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, screenSurface, null, gl);

        // Камера
        openCamera();

        // Потоки кодування
        vDrain = new Thread(this::drainVideo, "hc-vdrain");
        vDrain.start();
        if (active) startAudio();

        rendering = true;
        nextFrameAt = SystemClock.uptimeMillis();
        gl.post(this::renderTick);
    }

    /** Почати писати у файл (після натискання «Почати»). */
    void go() {
        if (active) return;
        active = true;
        startAudio();
    }

    private void startAudio() {
        if (!wantAudio || audioThread != null) return;
        audioRunning = true;
        audioThread = new Thread(this::audioLoop, "hc-audio");
        audioThread.start();
    }

    private void setupEncoders(boolean audio) throws Exception {
        venc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        MediaCodecInfo.VideoCapabilities caps = venc.getCodecInfo()
                .getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).getVideoCapabilities();
        if (!caps.isSizeSupported(opt.outW, opt.outH)) {
            // Зменшуємо, поки кодер не погодиться (зберігаючи пропорції).
            float k = opt.outW / (float) opt.outH;
            int w = opt.outW;
            while (w > 360 && !caps.isSizeSupported(w, align16(Math.round(w / k)))) w -= 72;
            opt.outW = w & ~1;
            opt.outH = align16(Math.round(w / k));
        }
        int bitrate = opt.outW * opt.outH >= 2_000_000 ? 10_000_000 : 6_000_000;
        MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, opt.outW, opt.outH);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        f.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        venc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encSurface = venc.createInputSurface();
        venc.start();

        if (audio) {
            aenc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            MediaFormat af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1);
            af.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            af.setInteger(MediaFormat.KEY_BIT_RATE, 128_000);
            af.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384);
            aenc.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            aenc.start();
        }
    }

    /** Формат AAC-доріжки з готовим csd-0, щоб муксер міг стартувати, не чекаючи звуку. */
    private static MediaFormat aacTrackFormat() {
        MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1);
        f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        f.setInteger(MediaFormat.KEY_BIT_RATE, 128_000);
        // AudioSpecificConfig: AAC-LC (2), 48 кГц (індекс 3), моно (1)
        f.setByteBuffer("csd-0", ByteBuffer.wrap(new byte[]{0x11, (byte) 0x88}));
        return f;
    }

    static int align16(int v) { return Math.max(16, (v + 8) / 16 * 16); }

    @SuppressLint("MissingPermission")
    private boolean setupAudioRecorders() {
        AudioFormat fmt = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build();
        int min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufSize = Math.max(min * 4, 16_384);
        if (opt.mic) {
            try {
                micRec = new AudioRecord.Builder()
                        .setAudioSource(MediaRecorder.AudioSource.MIC)
                        .setAudioFormat(fmt).setBufferSizeInBytes(bufSize).build();
                if (micRec.getState() != AudioRecord.STATE_INITIALIZED) { micRec.release(); micRec = null; }
            } catch (Exception e) { Log.w(TAG, "mic", e); micRec = null; }
        }
        if (opt.appAudio) {
            try {
                AudioPlaybackCaptureConfiguration cfg = new AudioPlaybackCaptureConfiguration.Builder(projection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build();
                appRec = new AudioRecord.Builder()
                        .setAudioFormat(fmt).setBufferSizeInBytes(bufSize)
                        .setAudioPlaybackCaptureConfig(cfg).build();
                if (appRec.getState() != AudioRecord.STATE_INITIALIZED) { appRec.release(); appRec = null; }
            } catch (Exception e) { Log.w(TAG, "app audio", e); appRec = null; }
        }
        return micRec != null || appRec != null;
    }

    // ================================================================ камера

    @SuppressLint("MissingPermission")
    private void openCamera() throws Exception {
        CameraManager cm = ctx.getSystemService(CameraManager.class);
        int want = opt.frontCamera ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK;
        String id = null;
        for (String c : cm.getCameraIdList()) {
            Integer f = cm.getCameraCharacteristics(c).get(CameraCharacteristics.LENS_FACING);
            if (f != null && f == want) { id = c; break; }
        }
        if (id == null) id = cm.getCameraIdList()[0];
        CameraCharacteristics ch = cm.getCameraCharacteristics(id);
        StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size size = pickCamSize(map.getOutputSizes(SurfaceTexture.class));
        Integer so = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
        boolean swap = so != null && so % 180 != 0;
        // Розмір кадру камери «стоячи» (після повороту, який робить сама система).
        float uprightW = swap ? size.getHeight() : size.getWidth();
        float uprightH = swap ? size.getWidth() : size.getHeight();
        float aspect = uprightW / uprightH;
        uprightAspect = aspect;
        if (previewTexture != null) {
            previewTexture.setDefaultBufferSize(size.getWidth(), size.getHeight());
            previewSurface = new Surface(previewTexture);
        }
        if (aspect < 1f) { cropX = 1f; cropY = aspect; } else { cropX = 1f / aspect; cropY = 1f; }

        final Range<Integer> fps = pickFps(ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES));
        stCam.setDefaultBufferSize(size.getWidth(), size.getHeight());

        cm.openCamera(id, new CameraDevice.StateCallback() {
            @Override public void onOpened(CameraDevice c) {
                camera = c;
                try {
                    java.util.List<OutputConfiguration> outs = new java.util.ArrayList<>();
                    outs.add(new OutputConfiguration(camSurface));
                    if (previewSurface != null) outs.add(new OutputConfiguration(previewSurface));
                    SessionConfiguration cfg = new SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                            outs, cam::post,
                            new CameraCaptureSession.StateCallback() {
                                @Override public void onConfigured(CameraCaptureSession s) {
                                    camSession = s;
                                    try {
                                        CaptureRequest.Builder b = c.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                                        b.addTarget(camSurface);
                                        if (previewSurface != null) b.addTarget(previewSurface);
                                        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                                        if (fps != null) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps);
                                        s.setRepeatingRequest(b.build(), null, cam);
                                    } catch (Exception e) { Log.w(TAG, "cam request", e); }
                                }
                                @Override public void onConfigureFailed(CameraCaptureSession s) {
                                    Log.w(TAG, "cam configure failed");
                                }
                            });
                    c.createCaptureSession(cfg);
                } catch (Exception e) { Log.w(TAG, "cam session", e); }
            }
            // Якщо камеру забере інша програма, запис екрана триває, просто без кружечка.
            @Override public void onDisconnected(CameraDevice c) { c.close(); camera = null; }
            @Override public void onError(CameraDevice c, int error) { c.close(); camera = null; }
        }, cam);
    }

    private static Size pickCamSize(Size[] sizes) {
        Size best = null;
        for (Size s : sizes) {
            int lo = Math.min(s.getWidth(), s.getHeight());
            if (lo >= 480 && lo <= 1080 && (best == null || Math.abs(lo - 720) < Math.abs(Math.min(best.getWidth(), best.getHeight()) - 720))) best = s;
        }
        return best != null ? best : sizes[0];
    }

    private static Range<Integer> pickFps(Range<Integer>[] ranges) {
        if (ranges == null) return null;
        Range<Integer> best = null;
        for (Range<Integer> r : ranges) {
            if (r.getUpper() == 30 && (best == null || r.getLower() > best.getLower())) best = r;
        }
        return best;
    }

    // ================================================================ OpenGL

    private static final String VS =
            "attribute vec4 aPos;\n" +
            "attribute vec2 aUv;\n" +
            "varying vec2 vUv;\n" +
            "void main() { gl_Position = aPos; vUv = aUv; }\n";

    private static final String FS_SCREEN =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vUv;\n" +
            "uniform samplerExternalOES uTex;\n" +
            "uniform mat4 uTexM;\n" +
            "void main() { gl_FragColor = texture2D(uTex, (uTexM * vec4(vUv, 0.0, 1.0)).xy); }\n";

    private static final String FS_BUBBLE =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vUv;\n" +
            "uniform samplerExternalOES uTex;\n" +
            "uniform mat4 uTexM;\n" +
            "uniform vec2 uCrop;\n" +
            "uniform float uPx;\n" +
            "void main() {\n" +
            "  vec2 c = vUv - 0.5;\n" +
            "  float r = length(c);\n" +
            "  float alpha = 1.0 - smoothstep(0.5 - uPx, 0.5, r);\n" +
            "  if (alpha <= 0.0) discard;\n" +
            "  vec2 u = 0.5 + c * uCrop;\n" +
            "  vec4 col = texture2D(uTex, (uTexM * vec4(u, 0.0, 1.0)).xy);\n" +
            "  float ring = smoothstep(0.465 - uPx, 0.465, r);\n" +
            "  col = mix(col, vec4(1.0), ring);\n" +
            "  gl_FragColor = vec4(col.rgb, alpha);\n" +
            "}\n";

    private void setupGl() {
        eglDpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] ver = new int[2];
        if (!EGL14.eglInitialize(eglDpy, ver, 0, ver, 1)) throw new IllegalStateException("eglInitialize");
        int[] attrs = {
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGLExt.EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE};
        EGLConfig[] cfgs = new EGLConfig[1];
        int[] num = new int[1];
        if (!EGL14.eglChooseConfig(eglDpy, attrs, 0, cfgs, 0, 1, num, 0) || num[0] == 0) {
            throw new IllegalStateException("eglChooseConfig");
        }
        eglCtx = EGL14.eglCreateContext(eglDpy, cfgs[0], EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        eglSurf = EGL14.eglCreateWindowSurface(eglDpy, cfgs[0], encSurface, new int[]{EGL14.EGL_NONE}, 0);
        if (!EGL14.eglMakeCurrent(eglDpy, eglSurf, eglSurf, eglCtx)) throw new IllegalStateException("eglMakeCurrent");

        progScreen = buildProgram(VS, FS_SCREEN);
        progBubble = buildProgram(VS, FS_BUBBLE);

        int[] tex = new int[2];
        GLES20.glGenTextures(2, tex, 0);
        texScreen = tex[0];
        texCam = tex[1];
        for (int t : tex) {
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, t);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        }

        stScreen = new SurfaceTexture(texScreen);
        stScreen.setDefaultBufferSize(opt.outW, opt.outH);
        stScreen.setOnFrameAvailableListener(s -> screenNew = true, gl);
        screenSurface = new Surface(stScreen);

        stCam = new SurfaceTexture(texCam);
        stCam.setOnFrameAvailableListener(s -> camNew = true, gl);
        camSurface = new Surface(stCam);

        android.opengl.Matrix.setIdentityM(mScreen, 0);
        android.opengl.Matrix.setIdentityM(mCam, 0);

        quadFull = quad(-1f, -1f, 1f, 1f);
        // Геометрія кружечка в пікселях кадру
        float d = opt.outW * opt.sizeFrac;
        float mx = opt.outW * 0.045f;
        float topY = opt.outH * 0.10f, botY = opt.outH * 0.17f;
        boolean right = opt.corner == 1 || opt.corner == 3;
        boolean bottom = opt.corner >= 2;
        float x0 = right ? opt.outW - mx - d : mx;
        float y0 = bottom ? opt.outH - botY - d : topY;      // від верху
        float l = x0 / opt.outW * 2f - 1f, r = (x0 + d) / opt.outW * 2f - 1f;
        float t = 1f - y0 / opt.outH * 2f, b = 1f - (y0 + d) / opt.outH * 2f;
        quadBubble = quad(l, b, r, t);
    }

    private static FloatBuffer quad(float l, float b, float r, float t) {
        float[] v = {   // x, y, u, v  (triangle strip)
                l, b, 0f, 0f,
                r, b, 1f, 0f,
                l, t, 0f, 1f,
                r, t, 1f, 1f};
        FloatBuffer fb = ByteBuffer.allocateDirect(v.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(v).position(0);
        return fb;
    }

    private static int buildProgram(String vs, String fs) {
        int v = compile(GLES20.GL_VERTEX_SHADER, vs), f = compile(GLES20.GL_FRAGMENT_SHADER, fs);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new IllegalStateException("link: " + GLES20.glGetProgramInfoLog(p));
        return p;
    }

    private static int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new IllegalStateException("shader: " + GLES20.glGetShaderInfoLog(s));
        return s;
    }

    private void drawQuad(int prog, int tex, float[] texM, FloatBuffer q) {
        GLES20.glUseProgram(prog);
        int aPos = GLES20.glGetAttribLocation(prog, "aPos");
        int aUv = GLES20.glGetAttribLocation(prog, "aUv");
        q.position(0);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, q);
        GLES20.glEnableVertexAttribArray(aPos);
        q.position(2);
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 16, q);
        GLES20.glEnableVertexAttribArray(aUv);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uTex"), 0);
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uTexM"), 1, false, texM, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    /** Рендеримо 30 разів на секунду, навіть якщо екран не змінюється (обличчя рухається). */
    private void renderTick() {
        if (!rendering) return;
        try {
            if (screenNew) { screenNew = false; stScreen.updateTexImage(); stScreen.getTransformMatrix(mScreen); haveScreen = true; }
            if (camNew) { camNew = false; stCam.updateTexImage(); stCam.getTransformMatrix(mCam); haveCam = true; }
            if (!active) {   // «готовий»: кадри лише споживаємо, у кодер не віддаємо
                scheduleNext();
                return;
            }

            GLES20.glViewport(0, 0, opt.outW, opt.outH);
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            if (haveScreen) drawQuad(progScreen, texScreen, mScreen, quadFull);
            if (haveCam && camera != null) {
                GLES20.glEnable(GLES20.GL_BLEND);
                GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
                GLES20.glUseProgram(progBubble);
                GLES20.glUniform2f(GLES20.glGetUniformLocation(progBubble, "uCrop"), cropX, cropY);
                GLES20.glUniform1f(GLES20.glGetUniformLocation(progBubble, "uPx"), 2.0f / (opt.outW * opt.sizeFrac));
                drawQuad(progBubble, texCam, mCam, quadBubble);
                GLES20.glDisable(GLES20.GL_BLEND);
            }
            EGLExt.eglPresentationTimeANDROID(eglDpy, eglSurf, System.nanoTime());
            EGL14.eglSwapBuffers(eglDpy, eglSurf);
        } catch (Exception e) {
            Log.w(TAG, "render", e);
        }
        scheduleNext();
    }

    private void scheduleNext() {
        nextFrameAt += 1000 / FPS;
        long now = SystemClock.uptimeMillis();
        if (nextFrameAt < now) nextFrameAt = now;
        gl.postAtTime(this::renderTick, nextFrameAt);
    }

    // ================================================================ кодування

    private void drainVideo() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int idx;
            try { idx = venc.dequeueOutputBuffer(info, 10_000); } catch (Exception e) { break; }
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                mux.addTrack(venc.getOutputFormat(), true);
            } else if (idx >= 0) {
                ByteBuffer b = venc.getOutputBuffer(idx);
                boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                if (!config && info.size > 0 && b != null) mux.write(true, b, info);
                boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                venc.releaseOutputBuffer(idx, false);
                if (eos) break;
            }
        }
    }

    private void audioLoop() {
        final int n = 1024;
        short[] a = new short[n], b = new short[n];
        ByteBuffer bytes = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long startNs = -1, samples = 0;
        try {
            if (micRec != null) micRec.startRecording();
            if (appRec != null) appRec.startRecording();
            while (audioRunning) {
                int got;
                if (micRec != null) {
                    got = micRec.read(a, 0, n);
                    if (got <= 0) continue;
                    if (appRec != null) {
                        int m = appRec.read(b, 0, got, AudioRecord.READ_NON_BLOCKING);
                        for (int i = 0; i < m; i++) {
                            int s = a[i] + b[i];
                            a[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, s));
                        }
                    }
                } else {
                    // Лише звук програм: коли нічого не грає, даних немає — доповнюємо тишею в реальному часі.
                    got = appRec.read(a, 0, n, AudioRecord.READ_NON_BLOCKING);
                    if (got <= 0) {
                        Thread.sleep(n * 1000L / SAMPLE_RATE);
                        java.util.Arrays.fill(a, (short) 0);
                        got = n;
                    }
                }
                if (startNs < 0) startNs = System.nanoTime() - got * 1_000_000_000L / SAMPLE_RATE;
                bytes.clear();
                bytes.asShortBuffer().put(a, 0, got);
                long pts = (startNs + samples * 1_000_000_000L / SAMPLE_RATE) / 1000;
                samples += got;
                feedAudio(bytes, got * 2, pts, false);
                drainAudio(info, false);
            }
            feedAudio(bytes, 0, (startNs + samples * 1_000_000_000L / SAMPLE_RATE) / 1000, true);
            drainAudio(info, true);
        } catch (Exception e) {
            Log.w(TAG, "audio", e);
        }
    }

    private void feedAudio(ByteBuffer src, int len, long ptsUs, boolean eos) {
        int idx = aenc.dequeueInputBuffer(20_000);
        if (idx < 0) return;
        ByteBuffer in = aenc.getInputBuffer(idx);
        in.clear();
        if (len > 0) {
            src.position(0).limit(len);
            in.put(src);
        }
        aenc.queueInputBuffer(idx, 0, len, Math.max(0, ptsUs),
                eos ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
    }

    private void drainAudio(MediaCodec.BufferInfo info, boolean untilEos) {
        long deadline = System.currentTimeMillis() + 2000;
        while (true) {
            int idx = aenc.dequeueOutputBuffer(info, untilEos ? 10_000 : 0);
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!untilEos || System.currentTimeMillis() > deadline) return;
            } else if (idx >= 0) {
                ByteBuffer b = aenc.getOutputBuffer(idx);
                boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                if (!config && info.size > 0 && b != null) mux.write(false, b, info);
                boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                aenc.releaseOutputBuffer(idx, false);
                if (eos) return;
            }
        }
    }

    // ================================================================ стоп

    /** Блокуючий: дописує файл і звільняє все. @return true, якщо у файлі є відео. */
    boolean stop() {
        // 1) звук
        audioRunning = false;
        joinQuietly(audioThread, 3000);

        // 2) останній кадр і кінець відеопотоку
        CountDownLatch glDone = new CountDownLatch(1);
        if (gl != null) {
            gl.post(() -> {
                rendering = false;
                gl.removeCallbacksAndMessages(null);
                try { venc.signalEndOfInputStream(); } catch (Exception ignored) { }
                glDone.countDown();
            });
            try { glDone.await(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        }
        joinQuietly(vDrain, 4000);

        // 3) файл
        boolean ok = mux != null && mux.stop();

        // 4) звільняємо захоплення
        try { if (camSession != null) camSession.close(); } catch (Exception ignored) { }
        try { if (camera != null) camera.close(); } catch (Exception ignored) { }
        camera = null;
        try { if (previewSurface != null) previewSurface.release(); } catch (Exception ignored) { }
        previewSurface = null;
        try { if (vdisplay != null) vdisplay.release(); } catch (Exception ignored) { }
        try { projection.stop(); } catch (Exception ignored) { }
        releaseAudio();
        try { if (venc != null) { venc.stop(); venc.release(); } } catch (Exception ignored) { }
        try { if (aenc != null) { aenc.stop(); aenc.release(); } } catch (Exception ignored) { }

        // 5) GL
        CountDownLatch rel = new CountDownLatch(1);
        if (gl != null) {
            gl.post(() -> { releaseGl(); rel.countDown(); });
            try { rel.await(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        }
        if (glThread != null) glThread.quitSafely();
        if (camThread != null) camThread.quitSafely();
        return ok;
    }

    /** Аварійне звільнення без збереження. */
    void abort() {
        audioRunning = false;
        rendering = false;
        stop();
    }

    private void releaseAudio() {
        for (AudioRecord r : new AudioRecord[]{micRec, appRec}) {
            if (r == null) continue;
            try { r.stop(); } catch (Exception ignored) { }
            r.release();
        }
        micRec = appRec = null;
    }

    private void releaseGl() {
        try { if (screenSurface != null) screenSurface.release(); } catch (Exception ignored) { }
        try { if (camSurface != null) camSurface.release(); } catch (Exception ignored) { }
        try { if (stScreen != null) stScreen.release(); } catch (Exception ignored) { }
        try { if (stCam != null) stCam.release(); } catch (Exception ignored) { }
        if (eglDpy != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (eglSurf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDpy, eglSurf);
            if (eglCtx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDpy, eglCtx);
            EGL14.eglTerminate(eglDpy);
        }
        eglDpy = EGL14.EGL_NO_DISPLAY;
        try { if (encSurface != null) encSurface.release(); } catch (Exception ignored) { }
    }

    private static void joinQuietly(Thread t, long ms) {
        if (t == null) return;
        try { t.join(ms); } catch (InterruptedException ignored) { }
    }

    // ================================================================ мультиплексор

    /** Потокобезпечна обгортка MediaMuxer: стартує, коли додані всі доріжки; відео — з ключового кадру. */
    static final class MuxerSync {
        private final MediaMuxer m;
        private final Runnable onStarted;
        Runnable onFirstSample;
        private int vTrack = -1, aTrack = -1;
        private boolean started, videoKey, any, stopped;

        MuxerSync(MediaMuxer m, MediaFormat audioFormat, Runnable onStarted) {
            this.m = m;
            this.onStarted = onStarted;
            if (audioFormat != null) aTrack = m.addTrack(audioFormat);
        }

        /** Стартуємо, щойно відомий формат відео (звукова доріжка вже додана наперед). */
        synchronized void addTrack(MediaFormat f, boolean video) {
            if (started || !video) return;
            vTrack = m.addTrack(f);
            m.start();
            started = true;
            if (onStarted != null) onStarted.run();
        }

        synchronized void write(boolean video, ByteBuffer b, MediaCodec.BufferInfo i) {
            if (!started || stopped) return;
            if (video && !videoKey) {
                if ((i.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) == 0) return;
                videoKey = true;
            }
            if (!videoKey) return;   // звук — лише після першого відеокадру
            if (!video && aTrack < 0) return;
            b.position(i.offset).limit(i.offset + i.size);
            m.writeSampleData(video ? vTrack : aTrack, b, i);
            if (!any) {
                any = true;
                if (onFirstSample != null) onFirstSample.run();
            }
        }

        synchronized boolean stop() {
            stopped = true;
            boolean ok = false;
            if (started) {
                try { m.stop(); ok = any; } catch (Exception e) { Log.w(TAG, "muxer stop", e); }
            }
            try { m.release(); } catch (Exception ignored) { }
            return ok;
        }
    }
}
