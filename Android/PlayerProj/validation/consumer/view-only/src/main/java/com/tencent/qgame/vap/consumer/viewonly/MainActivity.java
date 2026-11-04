package com.tencent.qgame.vap.consumer.viewonly;

import android.app.Activity;
import android.os.Bundle;
import com.tencent.qgame.animplayer.AnimConfig;
import com.tencent.qgame.animplayer.AnimView;
import com.tencent.qgame.animplayer.inter.IAnimListener;
import com.tencent.qgame.vap.consumer.FixtureResult;
import java.util.concurrent.atomic.AtomicInteger;

public final class MainActivity extends Activity {
    private static final String TAG = "VapMavenView";
    private AnimView animation;
    private final AtomicInteger frames = new AtomicInteger();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FixtureResult result = new FixtureResult(getFilesDir(), TAG);
        setContentView(R.layout.main);
        animation = findViewById(R.id.animation);
        animation.setMute(true);
        animation.setAnimListener(new IAnimListener() {
            @Override public void onVideoStart() { result.note("STARTED"); }
            @Override public void onVideoRender(int frameIndex, AnimConfig config) { frames.incrementAndGet(); }
            @Override public void onVideoComplete() { result.note("COMPLETED frames=" + frames.get()); }
            @Override public void onVideoDestroy() { result.note("DESTROYED"); }
            @Override public void onFailed(int errorType, String message) {
                result.fail("FAILED code=" + errorType + " message=" + message, null);
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        frames.set(0);
        animation.startPlay(getAssets(), "demo.mp4");
    }

    @Override
    protected void onStop() {
        animation.stopPlay();
        super.onStop();
    }
}
