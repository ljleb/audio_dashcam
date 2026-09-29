package com.example.audiodashcam;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.List;
import java.util.Locale;

final class TimelineView extends View {
    interface Listener { void onTimelineChanged(); }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private RecorderService service;
    private Listener listener;

    private long viewStart;
    private long viewEnd;
    private long playhead;
    private long selectionStart = -1;
    private long selectionEnd = -1; // -1 = LIVE
    private boolean followLive = true;
    private float dragStartX;
    private long dragViewStart;
    private long dragViewEnd;

    TimelineView(Context context) {
        super(context);
        setBackgroundColor(0xff101010);
        paint.setTypeface(android.graphics.Typeface.create("sans", android.graphics.Typeface.NORMAL));

        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                if (service == null) return false;
                long earliest = service.earliestFrame();
                long latest = service.latestFrameExclusive();
                long span = Math.max(1, viewEnd - viewStart);
                long minSpan = AudioProfile.SAMPLE_RATE; // one second
                long maxSpan = Math.max(minSpan, latest - earliest);
                long newSpan = (long) Math.max(minSpan, Math.min(maxSpan, span / detector.getScaleFactor()));
                float focusFraction = getWidth() <= 0 ? 0.5f : detector.getFocusX() / getWidth();
                long focusFrame = viewStart + (long) (span * focusFraction);
                long ns = focusFrame - (long) (newSpan * focusFraction);
                setWindow(ns, ns + newSpan, earliest, latest);
                followLive = viewEnd >= latest - AudioProfile.SAMPLE_RATE / 4;
                invalidate();
                notifyChanged();
                return true;
            }
        });

        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapUp(MotionEvent e) {
                if (e.getY() < overviewBottom()) return true;
                playhead = frameAtX(e.getX());
                invalidate();
                notifyChanged();
                return true;
            }

            @Override public boolean onDoubleTap(MotionEvent e) {
                jumpLive();
                return true;
            }
        });
    }

    void setService(RecorderService service) {
        this.service = service;
        syncFromService(true);
    }

    void setListener(Listener listener) { this.listener = listener; }

    void syncFromService(boolean initialize) {
        if (service == null) return;
        long earliest = service.earliestFrame();
        long latest = service.latestFrameExclusive();
        if (latest <= earliest) {
            viewStart = earliest;
            viewEnd = earliest + AudioProfile.SAMPLE_RATE * 60L;
            playhead = earliest;
            invalidate();
            return;
        }
        if (initialize || viewEnd <= viewStart) {
            long defaultSpan = Math.min(latest - earliest, AudioProfile.SAMPLE_RATE * 5L * 60L);
            viewEnd = latest;
            viewStart = Math.max(earliest, latest - defaultSpan);
            playhead = latest;
            if (selectionStart < earliest) selectionStart = -1;
        } else if (followLive) {
            long span = Math.max(AudioProfile.SAMPLE_RATE, viewEnd - viewStart);
            viewEnd = latest;
            viewStart = Math.max(earliest, latest - span);
        } else {
            setWindow(viewStart, viewEnd, earliest, latest);
        }
        invalidate();
    }

    void jumpLive() {
        if (service == null) return;
        long latest = service.latestFrameExclusive();
        long earliest = service.earliestFrame();
        long span = Math.max(AudioProfile.SAMPLE_RATE, viewEnd - viewStart);
        viewEnd = latest;
        viewStart = Math.max(earliest, latest - span);
        playhead = latest;
        followLive = true;
        invalidate();
        notifyChanged();
    }

    void setSelectionStartAtPlayhead() {
        selectionStart = playhead;
        if (selectionEnd >= 0 && selectionEnd < selectionStart) selectionEnd = selectionStart;
        invalidate();
        notifyChanged();
    }

    void setSelectionEndAtPlayhead() {
        selectionEnd = playhead;
        if (selectionStart < 0) selectionStart = playhead;
        if (selectionEnd < selectionStart) {
            long t = selectionStart;
            selectionStart = selectionEnd;
            selectionEnd = t;
        }
        invalidate();
        notifyChanged();
    }

    void setSelectionEndLive() {
        selectionEnd = -1;
        invalidate();
        notifyChanged();
    }

    void setPlayhead(long frame) {
        playhead = frame;
        invalidate();
    }

    long playhead() { return playhead; }
    long selectionStart() { return selectionStart; }
    long selectionEndResolved() {
        if (service == null) return selectionEnd;
        return selectionEnd < 0 ? service.latestFrameExclusive() : selectionEnd;
    }
    boolean selectionEndsLive() { return selectionEnd < 0; }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (service == null) return;
        long earliest = service.earliestFrame();
        long latest = service.latestFrameExclusive();
        if (latest <= earliest) {
            drawText(canvas, "Waiting for audio…", 18, getHeight() / 2f, 18, 0xffcccccc);
            return;
        }

        float overviewBottom = overviewBottom();
        paint.setColor(0xff181818);
        canvas.drawRect(0, 0, getWidth(), overviewBottom, paint);
        paint.setColor(0xff252525);
        canvas.drawLine(0, overviewBottom, getWidth(), overviewBottom, paint);

        drawWaveform(canvas, earliest, latest, 0, 4, getWidth(), overviewBottom - 8, 0xff9aa0a6);
        drawCuts(canvas, earliest, latest, 0, overviewBottom, true);

        float ovStart = xForFrame(viewStart, earliest, latest);
        float ovEnd = xForFrame(viewEnd, earliest, latest);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f);
        paint.setColor(0xffeeeeee);
        canvas.drawRect(ovStart, 2, Math.max(ovStart + 2, ovEnd), overviewBottom - 2, paint);
        paint.setStyle(Paint.Style.FILL);

        drawWaveform(canvas, viewStart, viewEnd, 0, overviewBottom + 5, getWidth(), getHeight() - 22, 0xffdddddd);
        drawCuts(canvas, viewStart, viewEnd, overviewBottom, getHeight(), false);
        drawSelection(canvas, overviewBottom);

        drawText(canvas, formatAudioPosition(viewStart - earliest), 5, getHeight() - 5, 12, 0xffaaaaaa);
        String right = followLive ? "LIVE" : formatAudioPosition(viewEnd - earliest);
        float w = measure(right, 12);
        drawText(canvas, right, getWidth() - w - 5, getHeight() - 5, 12, followLive ? 0xffffffff : 0xffaaaaaa);
    }

    private void drawWaveform(Canvas canvas, long start, long end, float left, float top, float right, float bottom, int color) {
        int columns = Math.max(1, (int) Math.min(1400, right - left));
        float[] env = service.envelope(start, end, columns);
        float center = (top + bottom) / 2f;
        float amp = Math.max(2f, (bottom - top) * 0.45f);
        paint.setColor(color);
        paint.setStrokeWidth(1f);
        for (int i = 0; i < columns; i++) {
            float x = left + (right - left) * i / Math.max(1f, columns - 1f);
            float lo = Math.max(-1f, Math.min(1f, env[i * 2]));
            float hi = Math.max(-1f, Math.min(1f, env[i * 2 + 1]));
            canvas.drawLine(x, center - hi * amp, x, center - lo * amp, paint);
        }
    }

    private void drawCuts(Canvas canvas, long start, long end, float top, float bottom, boolean overview) {
        List<PcmRingBuffer.SegmentSnapshot> segments = service.segments();
        PcmRingBuffer.SegmentSnapshot prev = null;
        for (PcmRingBuffer.SegmentSnapshot s : segments) {
            if (s.startFrame > start && s.startFrame < end) {
                float x = xForFrame(s.startFrame, start, end);
                paint.setColor(0xfff0b35a);
                paint.setStrokeWidth(overview ? 1f : 2f);
                canvas.drawLine(x, top + 2, x, bottom - 2, paint);
                if (!overview && prev != null) {
                    long elapsed = s.wallStartMs - prev.wallEndMs;
                    if (elapsed > 1000) {
                        drawText(canvas, "+" + formatElapsed(elapsed), Math.min(x + 4, getWidth() - 60), top + 15, 11, 0xfff0b35a);
                    }
                }
            }
            prev = s;
        }
    }

    private void drawSelection(Canvas canvas, float top) {
        long latest = service.latestFrameExclusive();
        if (selectionStart >= 0) {
            float x = xForFrame(selectionStart, viewStart, viewEnd);
            paint.setColor(0xff65b5ff);
            paint.setStrokeWidth(2f);
            canvas.drawLine(x, top, x, getHeight() - 20, paint);
            drawText(canvas, "START", x + 3, top + 14, 10, 0xff65b5ff);
        }
        long end = selectionEnd < 0 ? latest : selectionEnd;
        if (selectionStart >= 0 && end >= viewStart && end <= viewEnd) {
            float x = xForFrame(end, viewStart, viewEnd);
            paint.setColor(0xff78d88b);
            paint.setStrokeWidth(2f);
            canvas.drawLine(x, top, x, getHeight() - 20, paint);
            drawText(canvas, selectionEnd < 0 ? "LIVE" : "END", Math.max(3, x - 34), top + 14, 10, 0xff78d88b);
        }
        if (playhead >= viewStart && playhead <= viewEnd) {
            float x = xForFrame(playhead, viewStart, viewEnd);
            paint.setColor(0xffffffff);
            paint.setStrokeWidth(1f);
            canvas.drawLine(x, top + 17, x, getHeight() - 20, paint);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);
        if (scaleDetector.isInProgress()) return true;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragStartX = event.getX();
                dragViewStart = viewStart;
                dragViewEnd = viewEnd;
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getX() - dragStartX;
                if (Math.abs(dx) > 8 && getWidth() > 0 && service != null) {
                    long span = dragViewEnd - dragViewStart;
                    long shift = (long) (-dx / getWidth() * span);
                    setWindow(dragViewStart + shift, dragViewEnd + shift,
                            service.earliestFrame(), service.latestFrameExclusive());
                    followLive = viewEnd >= service.latestFrameExclusive() - AudioProfile.SAMPLE_RATE / 4;
                    invalidate();
                    notifyChanged();
                }
                return true;
            default:
                return true;
        }
    }

    private void setWindow(long start, long end, long earliest, long latest) {
        long span = Math.max(AudioProfile.SAMPLE_RATE, end - start);
        long available = Math.max(AudioProfile.SAMPLE_RATE, latest - earliest);
        span = Math.min(span, available);
        if (start < earliest) { start = earliest; end = start + span; }
        if (end > latest) { end = latest; start = end - span; }
        viewStart = Math.max(earliest, start);
        viewEnd = Math.max(viewStart + 1, Math.min(latest, end));
    }

    private long frameAtX(float x) {
        if (getWidth() <= 0) return viewStart;
        double f = Math.max(0, Math.min(1, x / getWidth()));
        return viewStart + (long) ((viewEnd - viewStart) * f);
    }

    private float xForFrame(long frame, long start, long end) {
        if (end <= start) return 0;
        return (float) ((frame - start) * (double) getWidth() / (end - start));
    }

    private float overviewBottom() { return getHeight() * 0.26f; }

    private void drawText(Canvas c, String s, float x, float y, float size, int color) {
        paint.setTextSize(size * getResources().getDisplayMetrics().scaledDensity);
        paint.setColor(color);
        paint.setStyle(Paint.Style.FILL);
        c.drawText(s, x, y, paint);
    }

    private float measure(String s, float size) {
        paint.setTextSize(size * getResources().getDisplayMetrics().scaledDensity);
        return paint.measureText(s);
    }

    private static String formatElapsed(long ms) {
        long total = Math.max(0, ms / 1000);
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        if (h > 0) return h + "h" + m + "m";
        if (m > 0) return m + "m" + s + "s";
        return s + "s";
    }

    private static String formatAudioPosition(long frames) {
        long totalMs = Math.max(0, Math.round(frames * 1000.0 / AudioProfile.SAMPLE_RATE));
        long h = totalMs / 3_600_000;
        long m = (totalMs / 60_000) % 60;
        long s = (totalMs / 1000) % 60;
        long ms = totalMs % 1000;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        return String.format(Locale.US, "%02d:%02d.%03d", m, s, ms);
    }

    private void notifyChanged() {
        if (listener != null) listener.onTimelineChanged();
    }
}
