package tv.widgetv.home;

import android.app.Activity;
import android.content.res.AssetFileDescriptor;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;

/**
 * Looping fireplace behind the home screen. Drawn in the window, under the scrim, so the clock and
 * the hadith stay readable. The picture and the crackle stop when the launcher leaves the screen.
 * The adhan takes the speakers; the flames keep running with the sound muted.
 */
final class FireBackdrop implements TextureView.SurfaceTextureListener, SurfaceHolder.Callback {
    private static final String TAG = Config.TAG;

    private final Activity host;
    private final TextureView texture;
    private final SurfaceView surfaceView;
    /** Fire OS leaves a TextureView black. API 25 uses the surface; newer TVs use the texture. */
    private final boolean useSurface;
    private final View scrim;
    private final View dim;
    private final View root;
    private MediaPlayer player;
    private Surface surface;
    private AudioFocusRequest focusRequest;
    private boolean enabled;
    private boolean adhan;
    private boolean starting;
    private int resumeAt;

    FireBackdrop(Activity host, TextureView texture, SurfaceView surfaceView, View scrim, View dim, View root) {
        this.host = host;
        this.texture = texture;
        this.surfaceView = surfaceView;
        this.scrim = scrim;
        this.dim = dim;
        this.root = root;
        useSurface = surfaceView != null;
        if (useSurface) surfaceView.getHolder().addCallback(this);
        else texture.setSurfaceTextureListener(this);
    }

    void setEnabled(boolean on) {
        enabled = on;
        int vis = on ? View.VISIBLE : View.GONE;
        if (useSurface) {
            surfaceView.setVisibility(vis);
            texture.setVisibility(View.GONE);
        } else {
            texture.setVisibility(vis);
        }
        scrim.setVisibility(vis);
        dim.setVisibility(vis);
        root.setBackgroundColor(on ? Color.TRANSPARENT : Lapis.LAPIS_900);
        if (!on) releasePlayer();
        else if (useSurface) {
            Surface current = surfaceView.getHolder().getSurface();
            if (current != null && current.isValid()) {
                surface = current;
                start();
            }
        } else if (texture.isAvailable()) start();
    }

    /** 0 at the top of the page, 1 once the grid fills the screen. */
    void setDim(float fraction) {
        dim.setAlpha(Math.max(0f, Math.min(1f, fraction)) * 0.78f);
    }

    /** True while the adhan owns the speakers. */
    void setAdhan(boolean playing) {
        adhan = playing;
        if (playing) {
            silence();
            abandonFocus();
        } else if (enabled && player != null) {
            requestFocus();
            applyVolume();
        }
    }

    void stop() {
        releasePlayer();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (!useSurface) return;
        surface = holder.getSurface();
        if (enabled) start();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (!useSurface) return;
        rememberPosition();
        releasePlayer();
        surface = null;
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
        surface = new Surface(surfaceTexture);
        if (enabled) start();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
        rememberPosition();
        releasePlayer();
        if (surface != null) {
            surface.release();
            surface = null;
        }
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
    }

    private void start() {
        if (!enabled || player != null || starting || surface == null) return;
        starting = true;
        try {
            AssetFileDescriptor afd = host.getAssets().openFd("fire.mp4");
            MediaPlayer mp = new MediaPlayer();
            player = mp;
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();
            mp.setLooping(true);
            if (useSurface) {
                mp.setDisplay(surfaceView.getHolder());
                mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
            } else {
                mp.setSurface(surface);
            }
            mp.setOnPreparedListener(prepared -> {
                starting = false;
                if (!enabled || prepared != player) {
                    prepared.release();
                    if (prepared == player) player = null;
                    return;
                }
                if (resumeAt > 0) prepared.seekTo(resumeAt);
                prepared.start();
                if (adhan) silence();
                else {
                    requestFocus();
                    applyVolume();
                }
                Log.i(TAG, "fire: playing");
            });
            mp.setOnErrorListener((mp1, what, extra) -> {
                Log.w(TAG, "fire: player error " + what + "/" + extra);
                starting = false;
                releasePlayer();
                return true;
            });
            mp.prepareAsync();
        } catch (Exception e) {
            Log.w(TAG, "fire: cannot play", e);
            starting = false;
            releasePlayer();
        }
    }

    private void rememberPosition() {
        if (player == null) return;
        try {
            if (player.isPlaying()) resumeAt = player.getCurrentPosition();
        } catch (IllegalStateException ignored) {
            // not started yet
        }
    }

    private void silence() {
        if (player == null) return;
        try {
            player.setVolume(0f, 0f);
        } catch (IllegalStateException ignored) {
        }
    }

    private void applyVolume() {
        if (player == null || adhan) return;
        try {
            player.setVolume(1f, 1f);
        } catch (IllegalStateException ignored) {
        }
    }

    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        if (change == AudioManager.AUDIOFOCUS_GAIN) applyVolume();
        else silence();
    };

    private void requestFocus() {
        AudioManager audio = host.getSystemService(AudioManager.class);
        if (audio == null || adhan) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (focusRequest == null) {
                focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build())
                        .setOnAudioFocusChangeListener(focusListener)
                        .build();
            }
            audio.requestAudioFocus(focusRequest);
        } else {
            requestFocusLegacy(audio);
        }
    }

    @SuppressWarnings("deprecation")
    private void requestFocusLegacy(AudioManager audio) {
        audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
    }

    @SuppressWarnings("deprecation")
    private void abandonFocus() {
        AudioManager audio = host.getSystemService(AudioManager.class);
        if (audio == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
            audio.abandonAudioFocusRequest(focusRequest);
        } else {
            audio.abandonAudioFocus(focusListener);
        }
    }

    private void releasePlayer() {
        starting = false;
        rememberPosition();
        abandonFocus();
        MediaPlayer mp = player;
        player = null;
        if (mp == null) return;
        try {
            mp.setOnPreparedListener(null);
            mp.setOnErrorListener(null);
            if (useSurface) mp.setDisplay(null);
            else mp.setSurface(null);
            mp.stop();
        } catch (IllegalStateException ignored) {
            // released or not started
        }
        mp.release();
    }
}
