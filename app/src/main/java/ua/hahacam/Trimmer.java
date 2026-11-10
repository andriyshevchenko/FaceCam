package ua.hahacam;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;

import java.io.File;
import java.io.FileDescriptor;
import java.nio.ByteBuffer;

/**
 * Обрізає MP4 без перекодування: копіює семпли між startUs і endUs.
 * Відео починається з найближчого ключового кадру після startUs.
 */
final class Trimmer {
    private Trimmer() { }

    /** @return тривалість результату в мікросекундах */
    static long remux(File src, FileDescriptor dst, long startUs, long endUs) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(src.getPath());
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try { r.setDataSource(src.getPath()); } catch (Exception ignored) { }
        return remux(ex, r, dst, startUs, endUs);
    }

    static long remux(android.content.Context ctx, android.net.Uri src, FileDescriptor dst, long startUs, long endUs) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(ctx, src, null);
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try { r.setDataSource(ctx, src); } catch (Exception ignored) { }
        return remux(ex, r, dst, startUs, endUs);
    }

    /** Час першого ключового кадру відео, не раніше за us (відносно початку файлу). */
    static long nextSyncUs(android.content.Context ctx, android.net.Uri src, long us) {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(ctx, src, null);
            for (int i = 0; i < ex.getTrackCount(); i++) {
                String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) { ex.selectTrack(i); break; }
            }
            long base = ex.getSampleTime();
            ex.seekTo(base + us, MediaExtractor.SEEK_TO_NEXT_SYNC);
            long t = ex.getSampleTime();
            return t < 0 ? us : t - base;
        } catch (Exception e) {
            return us;
        } finally {
            ex.release();
        }
    }

    private static long remux(MediaExtractor ex, MediaMetadataRetriever r, FileDescriptor dst,
                              long startUs, long endUs) throws Exception {
        MediaMuxer mux = null;
        try {
            int n = ex.getTrackCount();
            mux = new MediaMuxer(dst, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            int[] map = new int[n];
            boolean[] isVideo = new boolean[n];
            int maxSize = 1 << 20;
            boolean hasVideo = false;
            for (int i = 0; i < n; i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                map[i] = -1;
                if (mime == null || !(mime.startsWith("video/") || mime.startsWith("audio/"))) continue;
                isVideo[i] = mime.startsWith("video/");
                hasVideo |= isVideo[i];
                map[i] = mux.addTrack(f);
                ex.selectTrack(i);
                if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    maxSize = Math.max(maxSize, f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
                }
            }
            if (!hasVideo) throw new IllegalStateException("no video track");

            try {
                String rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
                if (rot != null) mux.setOrientationHint(Integer.parseInt(rot));
            } catch (Exception ignored) {
            } finally {
                try { r.release(); } catch (Exception ignored) { }
            }

            mux.start();
            ByteBuffer buf = ByteBuffer.allocate(Math.max(maxSize, 4 << 20));
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            long base = ex.getSampleTime();
            long videoStart = -1;
            long lastPts = 0;
            boolean written = false;

            while (true) {
                int t = ex.getSampleTrackIndex();
                if (t < 0) break;
                long time = ex.getSampleTime();
                int flags = ex.getSampleFlags();
                int size = ex.readSampleData(buf, 0);
                if (size < 0) break;
                long rel = time - base;

                if (endUs >= 0 && rel > endUs) {
                    if (rel > endUs + 2_000_000) break;   // усі доріжки вже пройшли кінець
                    ex.advance();
                    continue;
                }
                if (map[t] >= 0) {
                    if (isVideo[t] && videoStart < 0) {
                        if (rel >= startUs && (flags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0) videoStart = time;
                    }
                    if (videoStart >= 0 && time >= videoStart) {
                        info.offset = 0;
                        info.size = size;
                        info.presentationTimeUs = time - videoStart;
                        info.flags = (flags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                        mux.writeSampleData(map[t], buf, info);
                        lastPts = Math.max(lastPts, info.presentationTimeUs);
                        written = true;
                    }
                }
                ex.advance();
            }
            if (!written) throw new IllegalStateException("nothing written");
            mux.stop();
            return lastPts;
        } finally {
            try { if (mux != null) mux.release(); } catch (Exception ignored) { }
            ex.release();
        }
    }
}
