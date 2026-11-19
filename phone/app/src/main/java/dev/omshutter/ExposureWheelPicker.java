package dev.omshutter;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewParent;
import android.widget.NumberPicker;

import dev.omshutter.protocol.ExposureCatalogs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Native NumberPicker backed by exact camera exposure options. */
public final class ExposureWheelPicker extends NumberPicker {
    private static final long STEP_SETTLE_DELAY_MS = 350L;
    private static final long SCROLL_SETTLE_DELAY_MS = 850L;

    public interface OnOptionSettledListener {
        void onOptionSettled(ExposureCatalogs.Option option);
    }

    private final List<ExposureCatalogs.Option> options = new ArrayList<>();
    private final Runnable settleSelection = this::dispatchSettledSelection;
    private OnOptionSettledListener optionSettledListener;
    private int pendingSelection = -1;
    private boolean replacingOptions;
    private boolean touchActive;
    private boolean scrolling;
    private boolean settlingAfterScroll;

    public ExposureWheelPicker(Context context) {
        super(context);
        initialize();
    }

    public ExposureWheelPicker(Context context, AttributeSet attributes) {
        super(context, attributes);
        initialize();
    }

    public ExposureWheelPicker(
            Context context, AttributeSet attributes, int defaultStyleAttribute) {
        super(context, attributes, defaultStyleAttribute);
        initialize();
    }

    private void initialize() {
        setWrapSelectorWheel(false);
        super.setOnValueChangedListener((picker, oldValue, newValue) -> {
            if (replacingOptions || !isEnabled() || newValue >= options.size()) return;
            pendingSelection = newValue;
            if (!touchActive && !scrolling && !settlingAfterScroll) {
                scheduleSettledSelection(STEP_SETTLE_DELAY_MS);
            }
        });
        super.setOnScrollListener((picker, scrollState) -> {
            scrolling = scrollState != NumberPicker.OnScrollListener.SCROLL_STATE_IDLE;
            if (scrolling) {
                settlingAfterScroll = true;
                removeCallbacks(settleSelection);
            } else if (pendingSelection >= 0) {
                scheduleSettledSelection(SCROLL_SETTLE_DELAY_MS);
            }
        });
        replaceOptions(Collections.emptyList());
    }

    public void setOptions(List<ExposureCatalogs.Option> newOptions) {
        if (newOptions == null) throw new IllegalArgumentException("options are null");
        replaceOptions(newOptions);
    }

    public void clearOptions() {
        replaceOptions(Collections.emptyList());
    }

    public void setSelectedOption(ExposureCatalogs.Option option) {
        if (option == null || touchActive || scrolling || pendingSelection >= 0) return;
        int position = options.indexOf(option);
        if (position < 0 || getValue() == position) return;
        replacingOptions = true;
        setValue(position);
        replacingOptions = false;
    }

    public void setOnOptionSettledListener(OnOptionSettledListener listener) {
        optionSettledListener = listener;
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (!enabled && settleSelection != null) cancelPendingSelection();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        ViewParent parent = getParent();
        if (action == MotionEvent.ACTION_DOWN && isEnabled()) {
            touchActive = true;
            removeCallbacks(settleSelection);
            if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
        }
        boolean handled = super.dispatchTouchEvent(event);
        if (action == MotionEvent.ACTION_UP) {
            touchActive = false;
            if (pendingSelection >= 0 && !scrolling) {
                scheduleSettledSelection(
                        settlingAfterScroll ? SCROLL_SETTLE_DELAY_MS : STEP_SETTLE_DELAY_MS);
            }
            if (parent != null) parent.requestDisallowInterceptTouchEvent(false);
        } else if (action == MotionEvent.ACTION_CANCEL) {
            cancelPendingSelection();
            if (parent != null) parent.requestDisallowInterceptTouchEvent(false);
        }
        return handled;
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelPendingSelection();
        super.onDetachedFromWindow();
    }

    private void replaceOptions(List<ExposureCatalogs.Option> newOptions) {
        for (ExposureCatalogs.Option option : newOptions) {
            if (option == null) throw new IllegalArgumentException("options contain null");
        }
        cancelPendingSelection();
        replacingOptions = true;
        options.clear();
        options.addAll(newOptions);
        String[] labels = displayedLabels();
        setDisplayedValues(null);
        setMinValue(0);
        setMaxValue(labels.length - 1);
        setValue(0);
        setDisplayedValues(labels);
        replacingOptions = false;
    }

    private String[] displayedLabels() {
        if (options.isEmpty()) {
            return new String[]{getContext().getString(R.string.exposure_picker_empty)};
        }
        String[] labels = new String[options.size()];
        for (int index = 0; index < options.size(); index++) {
            labels[index] = ExposureOptionDisplay.format(options.get(index));
        }
        return labels;
    }

    private void scheduleSettledSelection(long delayMillis) {
        removeCallbacks(settleSelection);
        postDelayed(settleSelection, delayMillis);
    }

    private void dispatchSettledSelection() {
        int selection = pendingSelection;
        pendingSelection = -1;
        settlingAfterScroll = false;
        if (!isEnabled() || selection < 0 || selection >= options.size()) return;
        OnOptionSettledListener listener = optionSettledListener;
        if (listener != null) listener.onOptionSettled(options.get(selection));
    }

    private void cancelPendingSelection() {
        removeCallbacks(settleSelection);
        pendingSelection = -1;
        touchActive = false;
        scrolling = false;
        settlingAfterScroll = false;
    }
}
