package dev.omshutter;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import dev.omshutter.protocol.StarTelemetry;

import java.util.Locale;

/** Compact three-axis IBIS position view. Visible values are always angular degrees. */
public final class IbisCorrectionView extends View {
    private static final int ROW_COUNT = 3;
    private static final String[] ROW_LABELS = {
        "Yaw", "Pitch", "Roll"
    };
    private static final String UNAVAILABLE_DEGREES = "—°";
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int[] q10Values = new int[3];
    private final String[] displayedDegrees = {
        UNAVAILABLE_DEGREES,
        UNAVAILABLE_DEGREES,
        UNAVAILABLE_DEGREES
    };
    private boolean hasQ10Sample;

    public IbisCorrectionView(Context context, AttributeSet attributes) {
        super(context, attributes);
        setMinimumHeight(dp(112));
        setContentDescription("IBIS position in degrees unavailable");
    }

    public void setTelemetry(
            StarTelemetry.IbisSample sample,
            double focalLengthMm) {
        hasQ10Sample = sample != null;
        q10Values[0] = sample == null ? 0 : sample.getRawX();
        q10Values[1] = sample == null ? 0 : sample.getRawY();
        q10Values[2] = sample == null ? 0 : sample.getRawRoll();
        displayedDegrees[0] = formatDegrees(sample == null
                ? Double.NaN : planarQ10Degrees(q10Values[0], focalLengthMm));
        displayedDegrees[1] = formatDegrees(sample == null
                ? Double.NaN : planarQ10Degrees(q10Values[1], focalLengthMm));
        displayedDegrees[2] = formatDegrees(sample == null
                ? Double.NaN : rollQ10Degrees(q10Values[2]));
        setContentDescription(contentDescription(displayedDegrees));
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = resolveSize(getSuggestedMinimumWidth(), widthMeasureSpec);
        int height = resolveSize(getSuggestedMinimumHeight(), heightMeasureSpec);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int textColor = getResources().getColor(R.color.text_secondary, getContext().getTheme());
        int trackColor = getResources().getColor(R.color.panel_stroke, getContext().getTheme());
        int valueColor = getResources().getColor(R.color.ibis_meter, getContext().getTheme());
        float verticalInset = Math.min(dp(4), getHeight() / 8f);
        float contentTop = getPaddingTop() + verticalInset;
        float contentBottom = getHeight() - getPaddingBottom() - verticalInset;
        if (contentBottom <= contentTop) return;
        float rowHeight = (contentBottom - contentTop) / ROW_COUNT;
        float labelBaselineOffset = Math.min(dp(4), rowHeight * 0.24f);
        float tickHalfHeight = Math.min(dp(6), rowHeight * 0.28f);
        float valueStrokeWidth = Math.min(dp(6), rowHeight * 0.28f);
        paint.setTextSize(Math.min(dp(12), rowHeight * 0.55f));
        paint.setStrokeWidth(dp(2));
        paint.setStrokeCap(Paint.Cap.ROUND);
        float left = dp(60);
        float valueRight = getWidth() - dp(6);
        float right = Math.max(left + dp(8), valueRight - dp(58));
        float center = (left + right) / 2f;
        float half = Math.max(1f, (right - left) / 2f);
        for (int i = 0; i < ROW_LABELS.length; i++) {
            float y = rowCenter(contentTop, contentBottom, i);
            paint.setColor(textColor);
            paint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText(ROW_LABELS[i], dp(6), y + labelBaselineOffset, paint);
            paint.setColor(trackColor);
            canvas.drawLine(left, y, right, y, paint);
            canvas.drawLine(center, y - tickHalfHeight, center, y + tickHalfHeight, paint);
            if (hasQ10Sample) {
                float endpoint = center + half * q10Values[i]
                        / StarTelemetry.IBIS_Q10_FULL_SCALE;
                paint.setColor(valueColor);
                paint.setStrokeWidth(valueStrokeWidth);
                canvas.drawLine(center, y, endpoint, y, paint);
                paint.setStrokeWidth(dp(2));
            }
            paint.setTextAlign(Paint.Align.RIGHT);
            paint.setColor(textColor);
            canvas.drawText(
                    displayedDegrees[i],
                    valueRight,
                    y + labelBaselineOffset,
                    paint);
        }
    }

    static double planarQ10Degrees(int q10, double focalLengthMm) {
        if (q10 < -StarTelemetry.IBIS_Q10_FULL_SCALE
                || q10 > StarTelemetry.IBIS_Q10_FULL_SCALE
                || !Double.isFinite(focalLengthMm)
                || focalLengthMm <= 0.0) {
            return Double.NaN;
        }
        double sensorTravelMm = q10 * 1800.0
                / (StarTelemetry.IBIS_Q10_FULL_SCALE * 3000.0);
        return Math.toDegrees(Math.atan(sensorTravelMm / focalLengthMm));
    }

    static double rollQ10Degrees(int q10) {
        if (q10 < -StarTelemetry.IBIS_Q10_FULL_SCALE
                || q10 > StarTelemetry.IBIS_Q10_FULL_SCALE) {
            return Double.NaN;
        }
        return q10 * 5325.0
                / (StarTelemetry.IBIS_Q10_FULL_SCALE * 8192.0);
    }

    static String formatDegrees(double value) {
        return Double.isFinite(value)
                ? String.format(Locale.US, "%+.3f°", value)
                : UNAVAILABLE_DEGREES;
    }

    private static String contentDescription(String[] values) {
        return "IBIS position in degrees: Yaw " + values[0]
                + ", Pitch " + values[1]
                + ", Roll " + values[2];
    }

    static float rowCenter(float contentTop, float contentBottom, int rowIndex) {
        if (rowIndex < 0 || rowIndex >= ROW_COUNT) {
            throw new IllegalArgumentException("IBIS row index out of range");
        }
        return contentTop
                + (contentBottom - contentTop) * (rowIndex + 0.5f) / ROW_COUNT;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
