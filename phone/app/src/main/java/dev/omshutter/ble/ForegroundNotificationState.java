package dev.omshutter.ble;

import java.util.Objects;

/** Exact variable content rendered into the camera foreground notification. */
final class ForegroundNotificationState {
    enum PrimaryAction {
        NONE,
        TAKE_PHOTO,
        RELEASE_ALL
    }

    private final String title;
    private final String text;
    private final PrimaryAction primaryAction;
    private final String primaryActionLabel;
    private final String disconnectLabel;

    private ForegroundNotificationState(
            String title,
            String text,
            PrimaryAction primaryAction,
            String primaryActionLabel,
            String disconnectLabel) {
        this.title = title;
        this.text = text;
        this.primaryAction = primaryAction;
        this.primaryActionLabel = primaryActionLabel;
        this.disconnectLabel = disconnectLabel;
    }

    static ForegroundNotificationState create(
            String fallbackTitle,
            String cameraName,
            String status,
            String detail,
            boolean ready,
            boolean busy,
            boolean stockMutationAllowed,
            boolean shutterHeld,
            boolean halfHeld,
            String takePhotoLabel,
            String releaseLabel,
            String disconnectLabel) {
        String safeFallbackTitle = nonNull(fallbackTitle);
        String safeCameraName = nonNull(cameraName);
        String safeStatus = nonNull(status);
        String safeDetail = nonNull(detail);
        PrimaryAction action;
        String actionLabel;
        if (shutterHeld || halfHeld) {
            action = PrimaryAction.RELEASE_ALL;
            actionLabel = nonNull(releaseLabel);
        } else if (!ready || busy || !stockMutationAllowed) {
            action = PrimaryAction.NONE;
            actionLabel = "";
        } else {
            action = PrimaryAction.TAKE_PHOTO;
            actionLabel = nonNull(takePhotoLabel);
        }
        return new ForegroundNotificationState(
                safeCameraName.isEmpty() ? safeFallbackTitle : safeCameraName,
                safeStatus + (safeDetail.isEmpty() ? "" : " — " + safeDetail),
                action,
                actionLabel,
                nonNull(disconnectLabel));
    }

    String getTitle() { return title; }
    String getText() { return text; }
    PrimaryAction getPrimaryAction() { return primaryAction; }
    String getPrimaryActionLabel() { return primaryActionLabel; }
    String getDisconnectLabel() { return disconnectLabel; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ForegroundNotificationState)) return false;
        ForegroundNotificationState that = (ForegroundNotificationState) other;
        return title.equals(that.title)
                && text.equals(that.text)
                && primaryAction == that.primaryAction
                && primaryActionLabel.equals(that.primaryActionLabel)
                && disconnectLabel.equals(that.disconnectLabel);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                title, text, primaryAction, primaryActionLabel, disconnectLabel);
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }
}
