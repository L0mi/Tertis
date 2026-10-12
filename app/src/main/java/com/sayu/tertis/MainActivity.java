package com.sayu.tertis;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

public final class MainActivity extends Activity {
    private GameView gameView;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        gameView = new GameView(this);
        setContentView(gameView);
    }

    @Override protected void onResume() { super.onResume(); gameView.resumeAudio(); }
    @Override protected void onPause() { gameView.pauseAudio(); super.onPause(); }
    @Override protected void onDestroy() { gameView.release(); super.onDestroy(); }
}
