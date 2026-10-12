package org.schabi.newpipe;

import android.app.PictureInPictureParams;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Rational;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import org.schabi.newpipe.player.Player;
import org.schabi.newpipe.player.helper.PlayerHolder;

public final class SystemPictureInPictureActivity extends AppCompatActivity {
    private static final String TAG = "SystemPiPActivity";

    private FrameLayout playerContainer;
    private boolean playerViewMoved;
    private boolean enteringPictureInPicture;
    private boolean returningToMainActivity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable switchToPopupResolutionRunnable = () -> {
        final Player player = PlayerHolder.getInstance().getPlayer();
        if (player != null
                && playerViewMoved
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && isInPictureInPictureMode()) {
            player.setUsePopupResolutionForVideoPlayer(true);
        }
    };

    public static void start(@NonNull final Context context) {
        final Intent intent = new Intent(context, SystemPictureInPictureActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        playerContainer = new FrameLayout(this);
        playerContainer.setBackgroundColor(0xFF000000);
        playerContainer.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left == oldRight - oldLeft && bottom - top == oldBottom - oldTop) {
                return;
            }

            final Player player = PlayerHolder.getInstance().getPlayer();
            if (player != null && playerViewMoved) {
                updatePlayerLayout(player);
            }
        });
        setContentView(playerContainer, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        final Player player = PlayerHolder.getInstance().getPlayer();
        if (player == null || !player.videoPlayerSelected() || !player.isPlaying()) {
            finishWithoutAnimation();
            return;
        }

        attachPlayerView(player);
        playerContainer.post(() -> enterPictureInPicture(player));
    }

    @Override
    public void onPictureInPictureModeChanged(final boolean isInPictureInPictureMode,
                                              @NonNull final Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        MainActivity.setPictureInPictureStartingOrActive(isInPictureInPictureMode);
        enteringPictureInPicture = false;

        final Player player = PlayerHolder.getInstance().getPlayer();
        if (isInPictureInPictureMode) {
            if (player != null) {
                updatePlayerLayout(player);
                player.hideControls(0, 0);
                schedulePopupResolutionSwitch();
            }
        } else {
            returnToMainActivity();
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull final Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        final Player player = PlayerHolder.getInstance().getPlayer();
        if (player != null) {
            updatePlayerLayout(player);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !isInPictureInPictureMode()
                && !enteringPictureInPicture
                && !returningToMainActivity
                && !isFinishing()) {
            returnToMainActivity();
        }
    }

    @Override
    protected void onDestroy() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !isInPictureInPictureMode()) {
            restoreMainPlayerResolution();
        }
        detachPlayerView();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !isInPictureInPictureMode()) {
            MainActivity.setPictureInPictureStartingOrActive(false);
        }
        super.onDestroy();
    }

    private void attachPlayerView(@NonNull final Player player) {
        final View playerView = player.getRootView();
        final ViewGroup parent = playerView.getParent() instanceof ViewGroup
                ? (ViewGroup) playerView.getParent() : null;
        if (parent != playerContainer) {
            if (parent != null) {
                parent.removeView(playerView);
            }
            playerContainer.addView(playerView, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
        }

        playerViewMoved = true;
        player.changeFullscreen(false);
        player.hideControls(0, 0);
        updatePlayerLayout(player);
    }

    private void enterPictureInPicture(@NonNull final Player player) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || isFinishing()
                || isDestroyed()
                || isInPictureInPictureMode()) {
            return;
        }

        try {
            enteringPictureInPicture = true;
            MainActivity.setPictureInPictureStartingOrActive(true);
            if (!enterPictureInPictureMode(buildPictureInPictureParams(player))) {
                enteringPictureInPicture = false;
                returnToMainActivity();
            }
        } catch (final IllegalArgumentException | IllegalStateException e) {
            enteringPictureInPicture = false;
            Log.w(TAG, "Unable to enter picture-in-picture mode", e);
            returnToMainActivity();
        }
    }

    private PictureInPictureParams buildPictureInPictureParams(@NonNull final Player player) {
        final View sourceView = player.getSurfaceView();
        final PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder()
                .setAspectRatio(getPictureInPictureAspectRatio(player, sourceView));

        final Rect sourceRect = new Rect();
        if (sourceView.isShown() && sourceView.getGlobalVisibleRect(sourceRect)) {
            builder.setSourceRectHint(sourceRect);
        }

        return builder.build();
    }

    private Rational getPictureInPictureAspectRatio(@NonNull final Player player,
                                                    @NonNull final View sourceView) {
        final float videoRatio = player.getVideoNaturalAspectRatio();
        if (isValidPictureInPictureAspectRatio(videoRatio)) {
            return new Rational(Math.round(videoRatio * 1000), 1000);
        }

        final int width = sourceView.getWidth();
        final int height = sourceView.getHeight();
        if (width <= 0 || height <= 0) {
            return new Rational(16, 9);
        }

        final float ratio = (float) width / height;
        return isValidPictureInPictureAspectRatio(ratio)
                ? new Rational(width, height) : new Rational(16, 9);
    }

    private boolean isValidPictureInPictureAspectRatio(final float ratio) {
        return ratio >= 0.42f && ratio <= 2.39f;
    }

    private void updatePlayerLayout(@NonNull final Player player) {
        final int containerHeight = playerContainer.getHeight() > 0
                ? playerContainer.getHeight() : getWindow().getDecorView().getHeight();
        player.fitVideoSurfaceToWindowHeight(containerHeight);
        player.getRootView().requestLayout();
        player.getSurfaceView().requestLayout();
    }

    private void returnToMainActivity() {
        if (returningToMainActivity) {
            return;
        }
        returningToMainActivity = true;
        MainActivity.setPictureInPictureStartingOrActive(false);
        restoreMainPlayerResolution();
        detachPlayerView();

        final Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        startActivity(intent);
        finishWithoutAnimation();
    }

    private void detachPlayerView() {
        handler.removeCallbacks(switchToPopupResolutionRunnable);
        if (playerViewMoved && playerContainer != null) {
            playerContainer.removeAllViews();
        }
        playerViewMoved = false;
    }

    private void schedulePopupResolutionSwitch() {
        handler.removeCallbacks(switchToPopupResolutionRunnable);
        handler.postDelayed(switchToPopupResolutionRunnable, 1500);
    }

    private void restoreMainPlayerResolution() {
        handler.removeCallbacks(switchToPopupResolutionRunnable);
        final Player player = PlayerHolder.getInstance().getPlayer();
        if (player != null) {
            player.setUsePopupResolutionForVideoPlayer(false);
        }
    }

    private void finishWithoutAnimation() {
        if (!isFinishing()) {
            finish();
        }
        overridePendingTransition(0, 0);
    }
}
