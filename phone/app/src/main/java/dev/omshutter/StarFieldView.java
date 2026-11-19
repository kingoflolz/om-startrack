package dev.omshutter;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import dev.omshutter.protocol.StarCaptureProtocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A non-interactive, sensor-coordinate plot of the latest STAR result. */
public final class StarFieldView extends View {
    private static final float SENSOR_WIDTH = 1296f;
    private static final float SENSOR_HEIGHT = 972f;

    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint patternPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint validationPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint placeholderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path patternPath = new Path();
    private final RectF frameBounds = new RectF();
    private final RectF plotBounds = new RectF();

    private List<StarCaptureProtocol.Peak> peaks = Collections.emptyList();
    private final StarFieldOverlayState overlayState = new StarFieldOverlayState();

    public StarFieldView(Context context) {
        this(context, null);
    }

    public StarFieldView(Context context, AttributeSet attributes) {
        this(context, attributes, 0);
    }

    public StarFieldView(Context context, AttributeSet attributes, int defaultStyle) {
        super(context, attributes, defaultStyle);
        backgroundPaint.setColor(0xff080d12);

        gridPaint.setColor(0xff263039);
        gridPaint.setStrokeWidth(dp(1f));

        borderPaint.setColor(context.getColor(R.color.panel_stroke));
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp(1f));

        pointPaint.setStyle(Paint.Style.FILL);

        patternPaint.setColor(context.getColor(R.color.star_pattern_match));
        patternPaint.setStyle(Paint.Style.STROKE);
        patternPaint.setStrokeJoin(Paint.Join.ROUND);
        patternPaint.setStrokeWidth(dp(2.25f));

        validationPaint.setColor(context.getColor(R.color.star_validation_match));
        validationPaint.setStyle(Paint.Style.STROKE);
        validationPaint.setStrokeWidth(dp(1.75f));

        placeholderPaint.setColor(context.getColor(R.color.text_secondary));
        placeholderPaint.setTextAlign(Paint.Align.CENTER);
        placeholderPaint.setTextSize(sp(14f));

        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setFocusable(false);
        setContentDescription(context.getString(R.string.star_visualization_empty_description));
    }

    /** Replaces the plotted result with a defensive copy. Call on the UI thread. */
    public void setPeaks(List<StarCaptureProtocol.Peak> nextPeaks) {
        List<StarCaptureProtocol.Peak> replacement = nextPeaks == null
                || nextPeaks.isEmpty()
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(nextPeaks));
        peaks = replacement;
        overlayState.replacePeakData(snapshotPeakData(replacement));
        updateContentDescription();
        invalidate();
    }

    /** Highlights solver associations by their stable order in the capture stream. */
    public void setMatchSourceIndices(int[] patternSourceIndices, int[] validationSourceIndices) {
        overlayState.setMatches(patternSourceIndices, validationSourceIndices);
        updateContentDescription();
        invalidate();
    }

    /** Clears solver classification while preserving the detector's flux markers. */
    public void clearMatchSourceIndices() {
        if (!overlayState.hasMatches()) return;
        overlayState.clearMatches();
        updateContentDescription();
        invalidate();
    }

    public boolean hasMatchOverlay() {
        return overlayState.hasMatches();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desiredWidth = Math.round(dp(320f));
        int measuredWidth = resolveSize(desiredWidth, widthMeasureSpec);
        int desiredHeight = Math.round(measuredWidth * SENSOR_HEIGHT / SENSOR_WIDTH);

        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int heightLimit = MeasureSpec.getSize(heightMeasureSpec);
        int measuredHeight;
        if (heightMode == MeasureSpec.EXACTLY) {
            measuredHeight = heightLimit;
        } else if (heightMode == MeasureSpec.AT_MOST) {
            measuredHeight = Math.min(desiredHeight, heightLimit);
        } else {
            measuredHeight = desiredHeight;
        }
        setMeasuredDimension(measuredWidth, measuredHeight);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float frameInset = dp(1f);
        frameBounds.set(
                frameInset, frameInset, getWidth() - frameInset, getHeight() - frameInset);
        float plotInset = dp(6f);
        plotBounds.set(
                frameBounds.left + plotInset,
                frameBounds.top + plotInset,
                frameBounds.right - plotInset,
                frameBounds.bottom - plotInset);
        float corner = dp(12f);
        canvas.drawRoundRect(frameBounds, corner, corner, backgroundPaint);

        float thirdWidth = plotBounds.width() / 3f;
        float thirdHeight = plotBounds.height() / 3f;
        for (int index = 1; index < 3; index++) {
            float x = plotBounds.left + thirdWidth * index;
            float y = plotBounds.top + thirdHeight * index;
            canvas.drawLine(x, plotBounds.top, x, plotBounds.bottom, gridPaint);
            canvas.drawLine(plotBounds.left, y, plotBounds.right, y, gridPaint);
        }

        if (peaks.isEmpty()) {
            Paint.FontMetrics metrics = placeholderPaint.getFontMetrics();
            float baseline = plotBounds.centerY() - (metrics.ascent + metrics.descent) / 2f;
            canvas.drawText(
                    getContext().getString(R.string.star_visualization_placeholder),
                    plotBounds.centerX(),
                    baseline,
                    placeholderPaint);
        } else {
            drawPeaks(canvas);
        }
        canvas.drawRoundRect(frameBounds, corner, corner, borderPaint);
    }

    private void drawPeaks(Canvas canvas) {
        long maximumFlux = 0;
        for (StarCaptureProtocol.Peak peak : peaks) {
            maximumFlux = Math.max(maximumFlux, peak.getFlux());
        }
        double maximumLogFlux = Math.log1p(maximumFlux);
        int regularColor = getContext().getColor(R.color.text_primary);
        int brightestColor = getContext().getColor(R.color.accent);

        // Results are normally brightest-first. Draw backwards so a bright point
        // remains visible if two detector coordinates overlap.
        for (int index = peaks.size() - 1; index >= 0; index--) {
            StarCaptureProtocol.Peak peak = peaks.get(index);
            float normalized = maximumLogFlux == 0d
                    ? 1f
                    : (float) (Math.log1p(Math.max(0L, peak.getFlux())) / maximumLogFlux);
            normalized = Math.max(0f, Math.min(1f, normalized));
            pointPaint.setColor(peak.getFlux() == maximumFlux ? brightestColor : regularColor);
            pointPaint.setAlpha(Math.round(140f + normalized * 115f));

            float x = plotBounds.left
                    + plotBounds.width() * peak.getX() / (SENSOR_WIDTH - 1f);
            float y = plotBounds.top
                    + plotBounds.height() * peak.getY() / (SENSOR_HEIGHT - 1f);
            float radius = dp(1.75f + 2.75f * normalized);
            canvas.drawCircle(x, y, radius, pointPaint);
        }
        pointPaint.setAlpha(255);

        // Flux markers remain the base layer. Validation circles are drawn next,
        // then pattern diamonds, so a defensive overlap gives the pattern precedence.
        drawMatchOverlays(canvas, maximumLogFlux, StarMatchOverlay.Kind.VALIDATION);
        drawMatchOverlays(canvas, maximumLogFlux, StarMatchOverlay.Kind.PATTERN);
    }

    private void drawMatchOverlays(
            Canvas canvas, double maximumLogFlux, StarMatchOverlay.Kind requestedKind) {
        for (int index = peaks.size() - 1; index >= 0; index--) {
            StarCaptureProtocol.Peak peak = peaks.get(index);
            if (overlayState.kindForSourceIndex(index) != requestedKind) continue;

            float normalized = maximumLogFlux == 0d
                    ? 1f
                    : (float) (Math.log1p(Math.max(0L, peak.getFlux())) / maximumLogFlux);
            normalized = Math.max(0f, Math.min(1f, normalized));
            float x = plotBounds.left
                    + plotBounds.width() * peak.getX() / (SENSOR_WIDTH - 1f);
            float y = plotBounds.top
                    + plotBounds.height() * peak.getY() / (SENSOR_HEIGHT - 1f);
            float radius = dp(1.75f + 2.75f * normalized + 3f);

            if (requestedKind == StarMatchOverlay.Kind.PATTERN) {
                patternPath.reset();
                patternPath.moveTo(x, y - radius);
                patternPath.lineTo(x + radius, y);
                patternPath.lineTo(x, y + radius);
                patternPath.lineTo(x - radius, y);
                patternPath.close();
                canvas.drawPath(patternPath, patternPaint);
            } else {
                canvas.drawCircle(x, y, radius, validationPaint);
            }
        }
    }

    private void updateContentDescription() {
        if (peaks.isEmpty()) {
            setContentDescription(getContext().getString(
                    R.string.star_visualization_empty_description));
        } else if (!overlayState.hasMatches()) {
            int count = peaks.size();
            setContentDescription(getResources().getQuantityString(
                    R.plurals.star_visualization_count_description, count, count));
        } else {
            setContentDescription(getContext().getString(
                    R.string.star_visualization_match_description,
                    peaks.size(),
                    overlayState.getPatternCount(),
                    overlayState.getValidationCount()));
        }
    }

    private static long[] snapshotPeakData(List<StarCaptureProtocol.Peak> peaks) {
        long[] snapshot = new long[peaks.size() * 2];
        for (int index = 0; index < peaks.size(); index++) {
            StarCaptureProtocol.Peak peak = peaks.get(index);
            snapshot[index * 2] = ((long) peak.getX() << 16) | peak.getY();
            snapshot[index * 2 + 1] = peak.getFlux();
        }
        return snapshot;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private float sp(float value) {
        return value * getResources().getDisplayMetrics().scaledDensity;
    }
}
