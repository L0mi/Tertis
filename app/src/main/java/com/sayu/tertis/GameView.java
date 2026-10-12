package com.sayu.tertis;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.MediaPlayer;
import android.os.SystemClock;
import android.text.InputFilter;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

public final class GameView extends View {
    private enum Screen { REGISTER, MENU, SETTINGS, DEADLOCK_WARNING, DIFFICULTY, CHAOS_WARNING, PLAYING, EDITING, PAUSED, FINISHED }
    private enum Mode { SOLO, VERSUS }
    private enum Difficulty { EASY, NORMAL, HARD, CHAOS }
    private enum Hand { STANDARD, LEFT, RIGHT }
    private enum MusicTrack { STANDARD, DEADLOCK, CHAOS }

    private static final int ACCENT = Color.rgb(64,217,219);
    private static final int DARK_BG = Color.rgb(9,14,27);
    private static final int LIGHT_BG = Color.rgb(230,240,250);
    private static final int PANEL = Color.rgb(19,27,46);
    private static final int BLUE = Color.rgb(26,87,110);
    private static final int PURPLE = Color.rgb(87,46,110);
    private static final int RED = Color.rgb(122,31,41);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random random = new Random();
    private final SharedPreferences prefs;
    private final Map<String, RectF> buttons = new LinkedHashMap<>();
    private final TetrisBoard player = new TetrisBoard();
    private final TetrisBoard ai = new TetrisBoard();

    private Screen screen;
    private Mode mode = Mode.SOLO;
    private Difficulty difficulty = Difficulty.NORMAL;
    private Hand hand = Hand.STANDARD;
    private String username;
    private int highScore;
    private boolean darkMode, ghostEnabled, deadlockEnabled;
    private float masterVolume, musicVolume;
    private MediaPlayer music;
    private MusicTrack currentMusicTrack;
    private boolean audioActive = true;
    private long lastFrameNanos;
    private double elapsed, playerAccumulator, aiAccumulator;
    private int configuredPieceId = -1;
    private double randomizedDrop = .72;
    private boolean deadlockHoldAvailable = true, deadlockAppearanceFlipped;
    private boolean soloForcedDeadlock, chaosInstantKill;
    private boolean settingsFromPause, editingPausedGame;
    private boolean chaosUnlocked;
    private int hardTapCount;
    private long lastHardTap;
    private final Runnable hardStart = () -> { if (screen == Screen.DIFFICULTY && !chaosUnlocked) { hardTapCount = 0; difficulty = Difficulty.HARD; startGame(Mode.VERSUS); } };
    private boolean playerFreeze, aiFreeze;
    private double playerFrozenUntil, aiFrozenUntil, aiFreezeUseAt;
    private int playerStreak, aiStreak;
    private int rotationPieceId = -1;
    private double rotationTime;
    private String notice;
    private long noticeUntil;
    private String draggedControl, selectedControl = "rotate";
    private String pressedButton;
    private float touchOffsetX, touchOffsetY;
    private float scale = 1f;

    public GameView(Context context) {
        super(context);
        setFocusable(true);
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
        prefs = context.getSharedPreferences("tertis", Context.MODE_PRIVATE);
        username = prefs.getString("username", "");
        darkMode = prefs.getBoolean("dark", true);
        ghostEnabled = prefs.getBoolean("ghost", true);
        deadlockEnabled = prefs.getBoolean("deadlock", false);
        masterVolume = prefs.getFloat("masterVolume", .8f);
        musicVolume = prefs.getFloat("musicVolume", .7f);
        try { hand = Hand.valueOf(prefs.getString("hand", "STANDARD")); } catch (Exception ignored) {}
        loadHighScore();
        screen = username.trim().isEmpty() ? Screen.REGISTER : Screen.MENU;
        syncMusicToState();
        if (screen == Screen.REGISTER) postDelayed(this::promptUsername, 350);
    }

    private boolean matchScreenActive() {
        return screen == Screen.PLAYING || screen == Screen.PAUSED || screen == Screen.FINISHED
                || (screen == Screen.SETTINGS && settingsFromPause)
                || (screen == Screen.EDITING && editingPausedGame);
    }

    private MusicTrack desiredMusicTrack() {
        if (matchScreenActive() && chaosActive()) return MusicTrack.CHAOS;
        if (deadlockEnabled || (matchScreenActive() && deadlockRules())) return MusicTrack.DEADLOCK;
        return MusicTrack.STANDARD;
    }

    private int musicResource(MusicTrack track) {
        switch (track) {
            case DEADLOCK: return R.raw.tetris_theme_deadlock;
            case CHAOS: return R.raw.tetris_chaos_mode;
            default: return R.raw.background_music;
        }
    }

    private MediaPlayer makeMusicPlayer(MusicTrack track) {
        try {
            MediaPlayer player = MediaPlayer.create(getContext(), musicResource(track));
            if (player != null) player.setLooping(true);
            return player;
        } catch (Exception ignored) { return null; }
    }

    private void syncMusicToState() {
        MusicTrack wanted = desiredMusicTrack();
        if (music != null && currentMusicTrack == wanted) {
            applyVolume();
            if (audioActive && !music.isPlaying()) music.start();
            return;
        }
        MediaPlayer next = makeMusicPlayer(wanted);
        MusicTrack loaded = wanted;
        if (next == null && wanted != MusicTrack.STANDARD) {
            next = makeMusicPlayer(MusicTrack.STANDARD);
            loaded = MusicTrack.STANDARD;
        }
        if (next == null) return;
        MediaPlayer previous = music;
        music = next;
        currentMusicTrack = loaded;
        applyVolume();
        if (audioActive) music.start();
        if (previous != null) {
            previous.stop();
            previous.release();
        }
    }

    void resumeAudio() { audioActive = true; syncMusicToState(); }
    void pauseAudio() { audioActive = false; if (music != null && music.isPlaying()) music.pause(); }
    void release() { removeCallbacks(hardStart); if (music != null) { music.release(); music = null; currentMusicTrack = null; } }
    private void applyVolume() { if (music != null) music.setVolume(masterVolume * musicVolume, masterVolume * musicVolume); }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        scale = getWidth() / 390f;
        buttons.clear();
        updateGame();
        c.drawColor(background());
        switch (screen) {
            case REGISTER: drawRegistration(c); break;
            case MENU: drawMenu(c); break;
            case SETTINGS: drawSettings(c); break;
            case DEADLOCK_WARNING: drawDeadlockWarning(c); break;
            case DIFFICULTY: drawDifficulty(c); break;
            case CHAOS_WARNING: drawChaosWarning(c); break;
            case PLAYING: case EDITING: case PAUSED: case FINISHED: drawGame(c); break;
        }
        if ((screen == Screen.PLAYING || screen == Screen.EDITING || screen == Screen.PAUSED || screen == Screen.FINISHED) && deadlockRules()) drawChaosDecor(c);
        if (notice != null && SystemClock.uptimeMillis() < noticeUntil) text(c, notice, getWidth()/2f, getHeight()*.49f, 15, notice.contains("CURSE") ? Color.RED : Color.YELLOW, true);
        else if (notice != null) notice = null;
        postInvalidateOnAnimation();
    }

    private void updateGame() {
        long now = System.nanoTime();
        if (lastFrameNanos == 0) { lastFrameNanos = now; return; }
        double dt = Math.min((now - lastFrameNanos) / 1_000_000_000.0, .1);
        lastFrameNanos = now;
        if (screen != Screen.PLAYING) return;
        elapsed += dt;
        if (!playerFrozen()) playerAccumulator += dt;
        if (!aiFrozen()) aiAccumulator += dt;
        if (chaosActive() && aiFreeze && elapsed >= aiFreezeUseAt) {
            aiFreeze = false; playerFrozenUntil = elapsed + 3; showNotice("AI USED FREEZE!");
        }
        double speedup = (player.lines / 10) * .07 + Math.min(.36, elapsed / 30 * .025);
        double fall = Math.max(.1, (deadlockRules() ? randomizedDrop : .72) - speedup);
        if (!playerFrozen() && playerAccumulator >= fall) {
            playerAccumulator = 0;
            if (willLock(player)) awardTrickFit();
            Integer cleared = player.tick();
            if (cleared != null) completedPlacement(cleared, player);
        }
        if (mode == Mode.VERSUS && !aiFrozen() && aiAccumulator >= aiInterval()) {
            aiAccumulator = 0; runAI();
        }
        if (player.gameOver || (mode == Mode.VERSUS && ai.gameOver)) finishGame();
        configurePieceRules();
    }

    private int background() {
        boolean renderedDark = deadlockRules() && deadlockAppearanceFlipped ? !darkMode : darkMode;
        if (deadlockRules() && isGameScreen()) return renderedDark ? Color.rgb(19,3,27) : Color.rgb(250,214,232);
        return renderedDark ? DARK_BG : LIGHT_BG;
    }

    private boolean isGameScreen() { return screen == Screen.PLAYING || screen == Screen.EDITING || screen == Screen.PAUSED || screen == Screen.FINISHED; }
    private int textPrimary() { return (darkMode || isGameScreen()) ? Color.WHITE : Color.rgb(15,28,46); }
    private int textSecondary() { return darkMode ? Color.rgb(184,184,184) : Color.rgb(64,82,102); }
    private float p(float value) { return value * scale; }

    private void text(Canvas c, String value, float x, float y, float sp, int color, boolean bold) {
        paint.setStyle(Paint.Style.FILL); paint.setColor(color); paint.setTextSize(p(sp)); paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(bold ? android.graphics.Typeface.create("sans", android.graphics.Typeface.BOLD) : android.graphics.Typeface.create("sans", android.graphics.Typeface.NORMAL));
        Paint.FontMetrics fm = paint.getFontMetrics();
        c.drawText(value, x, y - (fm.ascent + fm.descent)/2f, paint);
    }

    private RectF button(Canvas c, String title, String name, float cx, float cy, float width, float height, int color, float font) {
        RectF r = new RectF(cx-p(width)/2, cy-p(height)/2, cx+p(width)/2, cy+p(height)/2);
        paint.setStyle(Paint.Style.FILL); paint.setColor(color); c.drawRoundRect(r, p(9), p(9), paint);
        stroke.setStyle(Paint.Style.STROKE); stroke.setStrokeWidth(p(1.5f)); stroke.setColor(Color.argb(115,255,255,255)); c.drawRoundRect(r,p(9),p(9),stroke);
        text(c,title,cx,cy,font,Color.WHITE,true); buttons.put(name,r); return r;
    }

    private void showNotice(String value) { notice = value; noticeUntil = SystemClock.uptimeMillis() + 950; }

    private void drawRegistration(Canvas c) {
        text(c,"WELCOME TO TERTIS",getWidth()/2f,p(92),30,ACCENT,true);
        text(c,"REGISTER PLAYER",getWidth()/2f,p(148),18,textPrimary(),true);
        text(c,"Your username keeps your high score saved",getWidth()/2f,p(190),12,textSecondary(),false);
        text(c,"on this device.",getWidth()/2f,p(212),12,textSecondary(),false);
        button(c,"ENTER USERNAME","register",getWidth()/2f,getHeight()*.48f,230,56,BLUE,15);
        copyright(c,getHeight()-p(76));
    }

    private void promptUsername() {
        if (!username.trim().isEmpty() || !(getContext() instanceof MainActivity)) return;
        EditText field = new EditText(getContext());
        field.setHint("Username"); field.setSingleLine(true); field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(16)});
        AlertDialog dialog = new AlertDialog.Builder(getContext()).setTitle("Register Player")
                .setMessage("Enter a username to save your high score on this device.").setView(field)
                .setPositiveButton("SAVE PLAYER", null).create();
        dialog.setOnShowListener(v -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(click -> {
                String entered = field.getText().toString().trim();
                if (entered.isEmpty()) { field.setError("Username is required"); return; }
                username = entered; prefs.edit().putString("username",username).apply(); loadHighScore();
                screen = Screen.MENU; dialog.dismiss(); invalidate();
            });
            field.requestFocus();
            Window w = dialog.getWindow(); if (w != null) w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        });
        dialog.setCancelable(false); dialog.show();
    }

    private void drawMenu(Canvas c) {
        float top = getHeight()*.20f;
        text(c,"TERTIS",getWidth()/2f,top,52,ACCENT,true);
        text(c,"FALL • CLEAR • SURVIVE",getWidth()/2f,top+p(49),14,textSecondary(),true);
        text(c,"PLAYER  •  "+username,getWidth()/2f,top+p(78),12,ACCENT,true);
        button(c,"SINGLE PLAYER","solo",getWidth()/2f,getHeight()*.40f,300,58,BLUE,17);
        button(c,"VS AI","versus",getWidth()/2f,getHeight()*.49f,300,58,PURPLE,17);
        button(c,"OPTIONS","settings",getWidth()/2f,getHeight()*.58f,300,52,PANEL,16);
        text(c,"VS AI: cleared lines send stone rows",getWidth()/2f,getHeight()*.64f,12,textSecondary(),false);
        if (deadlockEnabled) text(c,"DEADLOCK MODE ACTIVE",getWidth()/2f,getHeight()*.675f,11,Color.RED,true);
        text(c,"HIGH SCORE  "+highScore,getWidth()/2f,getHeight()*.78f,17,textPrimary(),true);
        text(c,"Tetris® & © Tetris Holding • Licensed to The Tetris Company",getWidth()/2f,getHeight()-p(15),8,textSecondary(),false);
    }

    private void drawSettings(Canvas c) {
        float x = getWidth()/2f, top = p(56);
        text(c,"OPTIONS",x,top,32,ACCENT,true);
        button(c,"DARK  "+(darkMode?"ON":"OFF"),"dark",x-p(68),top+p(66),126,48,darkMode?BLUE:PANEL,12);
        button(c,"GHOST  "+(ghostEnabled?"ON":"OFF"),"ghost",x+p(68),top+p(66),126,48,ghostEnabled?PURPLE:PANEL,12);
        boolean locked = settingsFromPause && (soloForcedDeadlock || chaosActive());
        button(c,locked?"DEADLOCK MODE  FORCED":"DEADLOCK MODE  "+(deadlockEnabled?"ON":"OFF"),"deadlock",x,top+p(126),245,48,(deadlockEnabled||locked)?RED:PANEL,13);
        text(c,locked?"Locked until this match ends":"Random speed and unreliable HOLD",x,top+p(160),11,locked?Color.RED:textSecondary(),false);
        text(c,"ONE-HAND CONTROLS",x,top+p(197),12,textSecondary(),true);
        button(c,"OFF","handOff",x-p(86),top+p(229),72,38,hand==Hand.STANDARD?Color.rgb(26,107,110):PANEL,11);
        button(c,"LEFT","handLeft",x,top+p(229),72,38,hand==Hand.LEFT?Color.rgb(26,107,110):PANEL,11);
        button(c,"RIGHT","handRight",x+p(86),top+p(229),72,38,hand==Hand.RIGHT?Color.rgb(26,107,110):PANEL,11);
        button(c,"EDIT BUTTONS","edit",x,top+p(278),210,42,PANEL,13);
        drawVolume(c,"MASTER VOLUME",masterVolume,top+p(338),"masterDown","masterUp");
        drawVolume(c,"MUSIC VOLUME",musicVolume,top+p(404),"musicDown","musicUp");
        copyright(c,getHeight()-p(109));
        button(c,"BACK","settingsBack",x,getHeight()-p(43),150,42,PANEL,14);
    }

    private void drawVolume(Canvas c,String title,float value,float y,String down,String up) {
        text(c,title+"  "+Math.round(value*100)+"%",getWidth()/2f,y,13,textPrimary(),true);
        int filled=Math.round(value*10); StringBuilder b=new StringBuilder(); for(int i=0;i<10;i++) b.append(i<filled?'●':'○');
        text(c,b.toString(),getWidth()/2f,y+p(25),15,ACCENT,true);
        button(c,"−",down,getWidth()/2f-p(118),y+p(12),44,40,PANEL,22);
        button(c,"+",up,getWidth()/2f+p(118),y+p(12),44,40,PANEL,21);
    }

    private void copyright(Canvas c,float y) {
        text(c,"Tetris® & © Tetris Holding. All rights reserved.",getWidth()/2f,y,8,textSecondary(),false);
        text(c,"Licensed to The Tetris Company.",getWidth()/2f,y+p(17),8,textSecondary(),false);
        text(c,"Unofficial entertainment-only Toilet-Seating game • Not for Profit",getWidth()/2f,y+p(34),8,textSecondary(),false);
    }

    private void drawDeadlockWarning(Canvas c) {
        c.drawColor(Color.rgb(23,3,10)); float x=getWidth()/2f, top=p(70);
        text(c,"⚠ EPILEPSY WARNING",x,top,25,Color.RED,true);
        text(c,"Deadlock Mode may unexpectedly change",x,top+p(62),14,Color.WHITE,true);
        text(c,"screen brightness, colors, and themes.",x,top+p(87),14,Color.WHITE,true);
        text(c,"Do not enable it if you are sensitive to",x,top+p(130),13,Color.LTGRAY,false);
        text(c,"flashing or rapidly changing visuals.",x,top+p(153),13,Color.LTGRAY,false);
        button(c,"ENABLE DEADLOCK","confirmDeadlock",x,getHeight()*.55f,240,56,RED,14);
        button(c,"CANCEL","cancelDeadlock",x,getHeight()*.64f,180,46,PANEL,13);
    }

    private void drawDifficulty(Canvas c) {
        float x=getWidth()/2f, top=p(65);
        text(c,"VS AI",x,top,34,ACCENT,true); text(c,"SELECT DIFFICULTY",x,top+p(43),14,textSecondary(),true);
        button(c,"EASY","easy",x,p(220),230,58,Color.rgb(31,102,77),16);
        button(c,"NORMAL","normal",x,p(294),230,58,Color.rgb(31,77,117),16);
        button(c,"HARD","hard",x,p(368),230,58,Color.rgb(128,31,43),16);
        if (chaosUnlocked) {
            button(c,"⚠ CHAOS","chaos",x,p(442),230,58,Color.rgb(133,10,92),16);
            text(c,"HIDDEN DIFFICULTY UNLOCKED",x,p(488),11,Color.MAGENTA,true);
        } else text(c,"Easy plays less efficiently • Hard reacts faster",x,p(420),11,textSecondary(),false);
        button(c,"BACK","menu",x,getHeight()-p(45),150,42,PANEL,14);
    }

    private void drawChaosWarning(Canvas c) {
        c.drawColor(Color.rgb(26,1,20)); float x=getWidth()/2f, top=p(62);
        text(c,"⚠ CHAOS MODE",x,top,30,Color.MAGENTA,true);
        text(c,"EPILEPSY & GAMEPLAY WARNING",x,top+p(42),13,Color.RED,true);
        text(c,"Deadlock visuals and random speeds are forced.",x,top+p(84),12,Color.WHITE,false);
        text(c,"Ghost preview is disabled.",x,top+p(108),12,Color.WHITE,false);
        text(c,"Both sides can find 3-second freeze power-ups.",x,top+p(132),12,Color.WHITE,false);
        text(c,"Every AI piece has a 1% instant-kill chance.",x,top+p(156),12,Color.RED,true);
        text(c,"ARE YOU SURE YOU WANT TO CONTINUE?",x,getHeight()*.49f,14,Color.YELLOW,true);
        button(c,"YES, PLAY CHAOS","confirmChaos",x,getHeight()*.58f,230,54,Color.rgb(138,10,77),14);
        button(c,"NO, GO BACK","cancelChaos",x,getHeight()*.67f,190,44,PANEL,12);
    }

    private void drawGame(Canvas c) {
        configurePieceRules();
        float hud=p(44);
        text(c,(mode==Mode.VERSUS?"YOU  ":"SCORE  ")+player.score,mode==Mode.VERSUS?getWidth()*.25f:getWidth()*.37f,hud,mode==Mode.VERSUS?15:17,ACCENT,true);
        if(mode==Mode.VERSUS) text(c,"AI  "+ai.score,getWidth()*.75f,hud,15,Color.MAGENTA,true);
        text(c,"TIME  "+formatTime(),mode==Mode.VERSUS?getWidth()/2f:getWidth()*.67f,hud,13,textSecondary(),true);
        if(screen!=Screen.EDITING) button(c,screen==Screen.PAUSED?"▶":"Ⅱ","pause",getWidth()-p(30),hud,42,36,PANEL,17);

        if(mode==Mode.VERSUS){
            float gap=p(12), bw=(getWidth()-p(24)-gap)/2f;
            float cell=Math.min(bw/10f,(getHeight()-p(360))/20f), bh=cell*20, top=p(126);
            float lx=p(12), rx=lx+bw+gap;
            drawBoard(c,player,lx,top,cell,"PLAYER",true);
            drawBoard(c,ai,rx,top,cell,"AI • "+difficulty.name(),false);
            drawPreview(c,player.hold,deadlockRules()&&!deadlockHoldAvailable?"HOLD ✕":"HOLD",p(35),p(76));
            drawPreview(c,ai.next,"AI NEXT",getWidth()-p(35),p(76));
            if(chaosActive()) drawChaosPower(c,top+bh+p(27),lx+bw/2,rx+bw/2,top+bh/2);
        }else{
            float bw=Math.min(getWidth()-p(140),p(300));
            float cell=Math.min(bw/10f,(getHeight()-p(315))/20f), bh=cell*20, left=(getWidth()-bw)/2, top=p(82);
            drawBoard(c,player,left,top,cell,null,true);
            float previewX=bw/2+p(36);
            drawPreview(c,player.hold,deadlockRules()&&!deadlockHoldAvailable?"HOLD ✕":"HOLD",getWidth()/2-previewX,top+p(45));
            drawPreview(c,player.next,"NEXT",getWidth()/2+previewX,top+p(45));
            text(c,"LINES "+player.lines+"   LEVEL "+(player.lines/10+1),getWidth()/2,top+bh+p(19),13,Color.LTGRAY,true);
        }
        drawControls(c);
        if(screen==Screen.EDITING) drawEditor(c);
        if(screen==Screen.PAUSED) drawPause(c);
        if(screen==Screen.FINISHED) drawGameOver(c);
    }

    private void drawBoard(Canvas c,TetrisBoard board,float left,float top,float cell,String title,boolean human) {
        float width=cell*10,height=cell*20;
        paint.setColor(Color.rgb(6,9,17));paint.setStyle(Paint.Style.FILL);c.drawRoundRect(new RectF(left,top,left+width,top+height),p(4),p(4),paint);
        stroke.setStyle(Paint.Style.STROKE);stroke.setStrokeWidth(p(deadlockRules()?2.5f:2));stroke.setColor(deadlockRules()?Color.RED:Color.DKGRAY);c.drawRoundRect(new RectF(left,top,left+width,top+height),p(4),p(4),stroke);
        stroke.setStrokeWidth(Math.max(1,p(.5f)));stroke.setColor(Color.argb(75,150,150,170));
        for(int x=1;x<10;x++)c.drawLine(left+x*cell,top,left+x*cell,top+height,stroke);
        for(int y=1;y<20;y++)c.drawLine(left,top+y*cell,left+width,top+y*cell,stroke);
        if(title!=null)text(c,title,left+width/2,top-p(12),9,Color.LTGRAY,true);
        for(int y=0;y<20;y++)for(int x=0;x<10;x++)if(board.cells[y][x]!=Color.TRANSPARENT)block(c,left,top,cell,x,y,board.cells[y][x],1f,false);
        if(board.piece!=null){
            TetrisBoard.Piece ghost=new TetrisBoard.Piece(board.piece);while(true){TetrisBoard.Piece d=new TetrisBoard.Piece(ghost);d.y--;if(!board.valid(d))break;ghost=d;}
            if(human&&ghostEnabled&&!chaosActive())for(int[] b:ghost.blocks())if(b[1]<20)block(c,left,top,cell,b[0],b[1],board.piece.kind.color,.18f,true);
            for(int[] b:board.piece.blocks())if(b[1]<20)block(c,left,top,cell,b[0],b[1],board.piece.kind.color,1f,false);
        }
    }

    private void block(Canvas c,float left,float top,float cell,int x,int y,int color,float alpha,boolean outline) {
        float inset=p(.7f), py=top+(19-y)*cell;
        RectF r=new RectF(left+x*cell+inset,py+inset,left+(x+1)*cell-inset,py+cell-inset);
        paint.setStyle(outline?Paint.Style.STROKE:Paint.Style.FILL);paint.setStrokeWidth(p(1));paint.setColor(withAlpha(color,alpha));c.drawRoundRect(r,Math.max(1,cell*.1f),Math.max(1,cell*.1f),paint);
        if(!outline){stroke.setStyle(Paint.Style.STROKE);stroke.setStrokeWidth(Math.max(1,cell*.05f));stroke.setColor(Color.argb((int)(140*alpha),255,255,255));c.drawRoundRect(r,Math.max(1,cell*.1f),Math.max(1,cell*.1f),stroke);}
    }

    private int withAlpha(int color,float a){return Color.argb(Math.round(255*a),Color.red(color),Color.green(color),Color.blue(color));}

    private void drawPreview(Canvas c,TetrisBoard.Kind kind,String title,float cx,float cy){
        RectF r=new RectF(cx-p(31),cy-p(30),cx+p(31),cy+p(30));paint.setColor(Color.argb(225,9,14,26));paint.setStyle(Paint.Style.FILL);c.drawRoundRect(r,p(9),p(9),paint);
        stroke.setStyle(Paint.Style.STROKE);stroke.setStrokeWidth(p(1));stroke.setColor(Color.GRAY);c.drawRoundRect(r,p(9),p(9),stroke);text(c,title,cx,cy-p(18),9,Color.LTGRAY,true);
        if(kind==null)return;float u=p(9);for(int[] b:kind.rotations[0]){paint.setColor(kind.color);paint.setStyle(Paint.Style.FILL);c.drawRoundRect(new RectF(cx+(b[0]-1)*u-u/2,cy+p(3)+(b[1]-1)*u-u/2,cx+(b[0]-1)*u+u/2,cy+p(3)+(b[1]-1)*u+u/2),p(1),p(1),paint);}
    }

    private void drawControls(Canvas c){
        if(hand!=Hand.STANDARD){drawOneHand(c);return;}
        float base=getHeight()-p(85),unit=Math.min(p(62),(getWidth()-p(44))/6),left=p(30)+unit,step=unit-p(4);
        control(c,"←","left",left-step,base,unit-p(5),p(54),PANEL,27);
        control(c,"↑","up",left,base-step,unit-p(5),p(54),Color.rgb(20,87,97),27);
        control(c,"↓","down",left,base+step,unit-p(5),p(54),PANEL,27);
        control(c,"→","right",left+step,base,unit-p(5),p(54),PANEL,27);
        control(c,"↻","rotate",getWidth()-unit*1.15f,base-p(34),unit,p(58),Color.rgb(66,46,107),27);
        boolean jam=deadlockRules()&&!deadlockHoldAvailable&&screen!=Screen.EDITING;
        control(c,jam?"JAMMED":"HOLD","hold",getWidth()-unit*1.15f,base+p(42),unit*1.25f,p(48),jam?RED:PANEL,jam?9:11);
    }

    private void drawOneHand(Canvas c){
        float cx=hand==Hand.LEFT?p(72):getWidth()-p(72),base=getHeight()-p(88),col=p(25),row=p(47),w=p(44),h=p(41);boolean jam=deadlockRules()&&!deadlockHoldAvailable&&screen!=Screen.EDITING;
        control(c,"↻","rotate",cx-col,base-row,w,h,Color.rgb(66,46,107),21);control(c,"↑","up",cx+col,base-row,w,h,Color.rgb(20,87,97),22);
        control(c,"←","left",cx-col,base,w,h,PANEL,22);control(c,"→","right",cx+col,base,w,h,PANEL,22);
        control(c,"↓","down",cx-col,base+row,w,h,PANEL,22);control(c,jam?"JAM":"HOLD","hold",cx+col,base+row,w,h,jam?RED:PANEL,8);
    }

    private void control(Canvas c,String title,String name,float cx,float cy,float width,float height,int color,float font){
        String key=controlKey(name);cx=prefs.getFloat(key+"x",cx/Math.max(1,getWidth()))*getWidth();cy=prefs.getFloat(key+"y",cy/Math.max(1,getHeight()))*getHeight();
        float customScale=controlScale(name),alpha=controlAlpha(name);float w=width*customScale,h=height*customScale;
        RectF r=new RectF(cx-w/2,cy-h/2,cx+w/2,cy+h/2);paint.setColor(withAlpha(color,alpha));paint.setStyle(Paint.Style.FILL);c.drawRoundRect(r,p(9),p(9),paint);
        stroke.setStyle(Paint.Style.STROKE);stroke.setStrokeWidth(p(screen==Screen.EDITING?(name.equals(selectedControl)?4:2):1.5f));stroke.setColor(screen==Screen.EDITING?withAlpha(Color.YELLOW,alpha):Color.argb((int)(115*alpha),255,255,255));c.drawRoundRect(r,p(9),p(9),stroke);
        text(c,title,cx,cy,font*customScale,withAlpha(Color.WHITE,alpha),true);buttons.put(name,r);
    }

    private void drawEditor(Canvas c){
        float x=getWidth()/2,top=p(43);text(c,"SELECT OR DRAG A YELLOW BUTTON",x,top,11,Color.YELLOW,true);
        button(c,"RESET","reset",x-p(75),top+p(39),88,34,PANEL,11);button(c,"DONE","done",x+p(75),top+p(39),88,34,BLUE,11);
        text(c,selectedControl.toUpperCase(Locale.ROOT)+"  •  SIZE "+Math.round(controlScale(selectedControl)*100)+"%  •  OPACITY "+Math.round(controlAlpha(selectedControl)*100)+"%",x,top+p(72),10,Color.WHITE,true);
        text(c,"SIZE",x-p(96),top+p(101),9,Color.LTGRAY,true);button(c,"−","sizeDown",x-p(132),top+p(130),44,38,PANEL,20);button(c,"+","sizeUp",x-p(78),top+p(130),44,38,PANEL,19);
        text(c,"OPACITY",x+p(99),top+p(101),9,Color.LTGRAY,true);button(c,"−","alphaDown",x+p(72),top+p(130),44,38,PANEL,20);button(c,"+","alphaUp",x+p(126),top+p(130),44,38,PANEL,19);
    }

    private void drawChaosPower(Canvas c,float y,float playerX,float aiX,float boardY){
        String title=aiFrozen()?"AI FROZEN":playerFreeze?"⚡ FREEZE AI":"NO POWER-UP";
        button(c,title,"freeze",getWidth()/2,y,126,34,playerFreeze?Color.rgb(26,122,173):Color.DKGRAY,10);
        if(playerFrozen())text(c,"YOU ARE FROZEN",playerX,boardY,11,Color.CYAN,true);
        if(aiFrozen())text(c,"AI FROZEN",aiX,boardY,11,Color.CYAN,true);
        text(c,aiFreeze?"AI FREEZE: READY":"AI SEARCHING...",aiX,y,8,aiFreeze?Color.MAGENTA:Color.GRAY,true);
    }

    private void drawPause(Canvas c){
        paint.setColor(Color.argb(178,0,0,0));paint.setStyle(Paint.Style.FILL);c.drawRect(0,0,getWidth(),getHeight(),paint);float x=getWidth()/2,y=getHeight()*.43f;
        text(c,"PAUSED",x,y-p(54),34,Color.WHITE,true);button(c,"RESUME","resume",x,y,180,50,BLUE,16);button(c,"OPTIONS","pauseSettings",x,y+p(60),180,46,PANEL,14);button(c,"MAIN MENU","menu",x,y+p(116),180,44,PANEL,14);
    }

    private void drawGameOver(Canvas c){
        paint.setColor(Color.argb(205,0,0,0));paint.setStyle(Paint.Style.FILL);c.drawRect(0,0,getWidth(),getHeight(),paint);float x=getWidth()/2,y=getHeight()*.43f;
        String result=chaosInstantKill?"CHAOS STRIKE!":mode==Mode.VERSUS?(player.gameOver?"AI WINS":"YOU WIN!"):"GAME OVER";
        text(c,result,x,y-p(76),36,player.gameOver?Color.WHITE:ACCENT,true);
        text(c,"TIME "+formatTime()+"  •  SCORE "+player.score+"  •  LINES "+player.lines,x,y-p(28),14,Color.WHITE,false);
        if(chaosInstantKill)text(c,"THE AI HIT THE 1% INSTANT KILL",x,y-p(5),11,Color.RED,true);
        button(c,mode==Mode.VERSUS?"REMATCH":"PLAY AGAIN","again",x,y+p(42),190,54,BLUE,15);button(c,"MAIN MENU","menu",x,y+p(108),190,46,PANEL,14);
    }

    private void drawChaosDecor(Canvas c){
        paint.setStyle(Paint.Style.FILL);
        for(int i=0;i<8;i++){paint.setColor(Color.argb(28,i%2==0?255:170,0,i%2==0?60:220));float y=(i*97+SystemClock.uptimeMillis()/15%90)%getHeight();c.save();c.rotate((i-4)*4,getWidth()/2,y);c.drawRect(0,y,getWidth(),y+p(2),paint);c.restore();}
        text(c,soloForcedDeadlock&&!chaosActive()?"⚠ 1% DEADLOCK CURSE // LOCKED":"⚠ DEADLOCK // CHAOS",getWidth()/2,getHeight()-p(10),10,Color.RED,true);
    }

    private String formatTime(){int total=(int)elapsed;return String.format(Locale.US,"%02d:%02d",total/60,total%60);}

    private void startGame(Mode selected){
        mode=selected;soloForcedDeadlock=selected==Mode.SOLO&&random.nextInt(100)==0;
        player.reset();ai.reset();elapsed=0;playerAccumulator=0;aiAccumulator=0;configuredPieceId=-1;
        playerFreeze=false;aiFreeze=false;playerFrozenUntil=0;aiFrozenUntil=0;aiFreezeUseAt=0;playerStreak=0;aiStreak=0;
        chaosInstantKill=false;rotationPieceId=-1;settingsFromPause=false;editingPausedGame=false;screen=Screen.PLAYING;
        if(soloForcedDeadlock)showNotice("1% DEADLOCK CURSE!");configurePieceRules();lastFrameNanos=System.nanoTime();syncMusicToState();
    }

    private void finishGame(){
        if(screen==Screen.FINISHED)return;screen=Screen.FINISHED;
        if(player.score>highScore){highScore=player.score;saveHighScore();}
    }

    private void configurePieceRules(){
        if(configuredPieceId==player.pieceId)return;configuredPieceId=player.pieceId;if(rotationPieceId!=player.pieceId)rotationPieceId=-1;
        if(deadlockRules()){randomizedDrop=.18+random.nextDouble()*.82;deadlockHoldAvailable=random.nextInt(100)<65;deadlockAppearanceFlipped=random.nextInt(100)<28;}
        else{randomizedDrop=.72;deadlockHoldAvailable=true;deadlockAppearanceFlipped=false;}
    }

    private boolean deadlockRules(){return deadlockEnabled||chaosActive()||soloForcedDeadlock;}
    private boolean chaosActive(){return mode==Mode.VERSUS&&difficulty==Difficulty.CHAOS;}
    private boolean playerFrozen(){return chaosActive()&&elapsed<playerFrozenUntil;}
    private boolean aiFrozen(){return chaosActive()&&elapsed<aiFrozenUntil;}
    private double aiInterval(){switch(difficulty){case EASY:return 1.35;case NORMAL:return .88;case HARD:return .55;default:return .42;}}

    private void completedPlacement(int cleared,TetrisBoard board){
        if(chaosActive()){
            if(board==player){playerStreak=cleared>0?playerStreak+1:0;if(playerFreeze)playerStreak=0;else if(playerStreak>=3||random.nextInt(100)<8){playerFreeze=true;playerStreak=0;showNotice("FREEZE POWER-UP ACQUIRED!");}}
            else{aiStreak=cleared>0?aiStreak+1:0;if(aiFreeze)aiStreak=0;else if(aiStreak>=3||random.nextInt(100)<8){aiFreeze=true;aiStreak=0;aiFreezeUseAt=elapsed+1.5+random.nextDouble()*3.5;showNotice("AI FOUND A FREEZE!");}}
        }
        if(cleared>0&&mode==Mode.VERSUS){if(board==player)ai.addGarbage(cleared);else player.addGarbage(cleared);showNotice(cleared==1?"LINE CLEAR!":cleared+" LINES!");}
        else if(cleared>0)showNotice(cleared==1?"LINE CLEAR!":cleared+" LINES!");
    }

    private void runAI(){
        if(ai.gameOver||ai.piece==null)return;List<TetrisBoard.Piece> choices=new ArrayList<>();List<Double> values=new ArrayList<>();TetrisBoard.Piece current=ai.piece;
        for(int r=0;r<current.kind.rotations.length;r++)for(int x=-2;x<=TetrisBoard.WIDTH;x++){
            TetrisBoard.Piece candidate=new TetrisBoard.Piece(current);candidate.rotation=r;candidate.x=x;candidate.y=20;if(!ai.valid(candidate))continue;
            while(true){TetrisBoard.Piece down=new TetrisBoard.Piece(candidate);down.y--;if(!ai.valid(down))break;candidate=down;}
            choices.add(candidate);values.add(evaluateAI(candidate));
        }
        if(choices.isEmpty()){completedPlacement(ai.hardDrop(),ai);return;}
        int index=0;if(difficulty==Difficulty.EASY)index=random.nextInt(choices.size());else if(difficulty==Difficulty.NORMAL){List<Integer> order=sortedIndices(values);int top=Math.max(1,order.size()/3);index=order.get(random.nextInt(top));}else index=sortedIndices(values).get(0);
        ai.piece=choices.get(index);completedPlacement(ai.hardDrop(),ai);
        if(chaosActive()&&random.nextInt(100)==0){chaosInstantKill=true;player.gameOver=true;}
    }

    private List<Integer> sortedIndices(List<Double> values){List<Integer> ids=new ArrayList<>();for(int i=0;i<values.size();i++)ids.add(i);ids.sort((a,b)->Double.compare(values.get(b),values.get(a)));return ids;}

    private double evaluateAI(TetrisBoard.Piece piece){
        boolean[][] grid=new boolean[TetrisBoard.HEIGHT][TetrisBoard.WIDTH];for(int y=0;y<TetrisBoard.HEIGHT;y++)for(int x=0;x<TetrisBoard.WIDTH;x++)grid[y][x]=ai.cells[y][x]!=Color.TRANSPARENT;
        for(int[] b:piece.blocks())if(b[1]>=0&&b[1]<TetrisBoard.HEIGHT&&b[0]>=0&&b[0]<TetrisBoard.WIDTH)grid[b[1]][b[0]]=true;
        int completed=0,holes=0,sum=0,bump=0;int[] heights=new int[10];
        for(int y=0;y<TetrisBoard.HEIGHT;y++){boolean full=true;for(int x=0;x<10;x++)if(!grid[y][x]){full=false;break;}if(full)completed++;}
        for(int x=0;x<10;x++){boolean found=false;for(int y=TetrisBoard.HEIGHT-1;y>=0;y--){if(grid[y][x]){if(!found){heights[x]=y+1;found=true;}}else if(found)holes++;}sum+=heights[x];if(x>0)bump+=Math.abs(heights[x]-heights[x-1]);}
        return completed*12-sum*.45-holes*5.5-bump*.6;
    }

    private boolean willLock(TetrisBoard board){if(board.piece==null)return false;TetrisBoard.Piece below=new TetrisBoard.Piece(board.piece);below.y--;return !board.valid(below);}

    private boolean precisionCandidate(){
        if(player.piece==null||player.piece.kind.rotations.length<=1)return false;TetrisBoard.Piece landing=new TetrisBoard.Piece(player.piece);int drop=0;
        while(true){TetrisBoard.Piece down=new TetrisBoard.Piece(landing);down.y--;if(!player.valid(down))break;landing=down;if(++drop>1)return false;}
        TetrisBoard.Piece left=new TetrisBoard.Piece(landing);left.x--;TetrisBoard.Piece right=new TetrisBoard.Piece(landing);right.x++;
        return drop<=1&&(!player.valid(left)||!player.valid(right));
    }

    private void registerRotation(){if(precisionCandidate()){rotationPieceId=player.pieceId;rotationTime=elapsed;}else rotationPieceId=-1;}
    private void awardTrickFit(){if(rotationPieceId==player.pieceId&&elapsed-rotationTime<=.45&&precisionCandidate()){int bonus=250*Math.max(1,player.lines/10+1);player.score+=bonus;showNotice("TRICK FIT!  +"+bonus+"  •  "+Math.max(1,Math.round((elapsed-rotationTime)*1000))+" ms");rotationPieceId=-1;}}

    @Override public boolean onTouchEvent(MotionEvent event){
        float x=event.getX(),y=event.getY();
        if(event.getAction()==MotionEvent.ACTION_DOWN){
            pressedButton=findButton(x,y);
            if(pressedButton==null)return true;
            if(screen==Screen.EDITING&&isControl(pressedButton)){
                selectedControl=pressedButton;draggedControl=pressedButton;RectF r=buttons.get(pressedButton);touchOffsetX=x-r.centerX();touchOffsetY=y-r.centerY();invalidate();return true;
            }
            handle(pressedButton);return true;
        }
        if(event.getAction()==MotionEvent.ACTION_MOVE&&draggedControl!=null){
            RectF r=buttons.get(draggedControl);float halfW=r==null?p(24):r.width()/2,halfH=r==null?p(24):r.height()/2;
            float cx=Math.max(halfW,Math.min(getWidth()-halfW,x-touchOffsetX)),cy=Math.max(halfH,Math.min(getHeight()-halfH,y-touchOffsetY));
            prefs.edit().putFloat(controlKey(draggedControl)+"x",cx/getWidth()).putFloat(controlKey(draggedControl)+"y",cy/getHeight()).apply();invalidate();return true;
        }
        if(event.getAction()==MotionEvent.ACTION_UP||event.getAction()==MotionEvent.ACTION_CANCEL){if(event.getAction()==MotionEvent.ACTION_UP&&pressedButton!=null)performClick();draggedControl=null;pressedButton=null;invalidate();return true;}
        return true;
    }

    @Override public boolean performClick(){super.performClick();return true;}

    private String findButton(float x,float y){String found=null;for(Map.Entry<String,RectF> e:buttons.entrySet())if(e.getValue().contains(x,y))found=e.getKey();return found;}
    private boolean isControl(String n){return n.equals("left")||n.equals("right")||n.equals("up")||n.equals("down")||n.equals("rotate")||n.equals("hold");}

    private void handle(String name){
        if(screen==Screen.PLAYING&&playerFrozen()&&isControl(name)){showNotice("YOU ARE FROZEN!");return;}
        switch(name){
            case "register":promptUsername();break;
            case "solo":startGame(Mode.SOLO);break;
            case "versus":hardTapCount=0;screen=Screen.DIFFICULTY;break;
            case "settings":settingsFromPause=false;screen=Screen.SETTINGS;break;
            case "dark":darkMode=!darkMode;prefs.edit().putBoolean("dark",darkMode).apply();break;
            case "ghost":ghostEnabled=!ghostEnabled;prefs.edit().putBoolean("ghost",ghostEnabled).apply();break;
            case "deadlock":
                if(settingsFromPause&&(soloForcedDeadlock||chaosActive()))showNotice("LOCKED FOR THIS MATCH");
                else if(deadlockEnabled){deadlockEnabled=false;prefs.edit().putBoolean("deadlock",false).apply();configuredPieceId=-1;}
                else screen=Screen.DEADLOCK_WARNING;break;
            case "confirmDeadlock":deadlockEnabled=true;prefs.edit().putBoolean("deadlock",true).apply();configuredPieceId=-1;screen=Screen.SETTINGS;break;
            case "cancelDeadlock":screen=Screen.SETTINGS;break;
            case "handOff":setHand(Hand.STANDARD);break;case "handLeft":setHand(Hand.LEFT);break;case "handRight":setHand(Hand.RIGHT);break;
            case "masterDown":changeVolume(true,-.1f);break;case "masterUp":changeVolume(true,.1f);break;case "musicDown":changeVolume(false,-.1f);break;case "musicUp":changeVolume(false,.1f);break;
            case "settingsBack":screen=settingsFromPause?Screen.PAUSED:Screen.MENU;break;
            case "edit":
                editingPausedGame=settingsFromPause;if(!editingPausedGame){mode=Mode.SOLO;player.reset();ai.reset();elapsed=0;configuredPieceId=-1;configurePieceRules();}
                screen=Screen.EDITING;break;
            case "done":screen=Screen.SETTINGS;break;
            case "reset":resetControls();break;
            case "sizeDown":adjustControl("scale",-.1f,.65f,1.5f);break;case "sizeUp":adjustControl("scale",.1f,.65f,1.5f);break;
            case "alphaDown":adjustControl("alpha",-.1f,.25f,1f);break;case "alphaUp":adjustControl("alpha",.1f,.25f,1f);break;
            case "easy":removeCallbacks(hardStart);difficulty=Difficulty.EASY;startGame(Mode.VERSUS);break;
            case "normal":removeCallbacks(hardStart);difficulty=Difficulty.NORMAL;startGame(Mode.VERSUS);break;
            case "hard":handleHardTap();break;
            case "chaos":screen=Screen.CHAOS_WARNING;break;
            case "confirmChaos":difficulty=Difficulty.CHAOS;startGame(Mode.VERSUS);break;
            case "cancelChaos":screen=Screen.DIFFICULTY;break;
            case "left":if(screen==Screen.PLAYING)player.move(-1,0);break;case "right":if(screen==Screen.PLAYING)player.move(1,0);break;
            case "down":if(screen==Screen.PLAYING){if(player.move(0,-1))player.score++;else{awardTrickFit();Integer cl=player.tick();if(cl!=null)completedPlacement(cl,player);}checkEnd();}break;
            case "rotate":if(screen==Screen.PLAYING&&player.rotate())registerRotation();break;
            case "up":if(screen==Screen.PLAYING){awardTrickFit();completedPlacement(player.hardDrop(),player);checkEnd();}break;
            case "hold":if(screen==Screen.PLAYING){if(deadlockRules()&&!deadlockHoldAvailable)showNotice("HOLD JAMMED!");else{player.useHold();rotationPieceId=-1;checkEnd();}}break;
            case "freeze":if(screen==Screen.PLAYING&&chaosActive()&&playerFreeze){playerFreeze=false;aiFrozenUntil=elapsed+3;showNotice("AI FROZEN FOR 3 SECONDS");}break;
            case "pause":if(screen==Screen.PLAYING)screen=Screen.PAUSED;else if(screen==Screen.PAUSED){screen=Screen.PLAYING;lastFrameNanos=System.nanoTime();}break;
            case "resume":screen=Screen.PLAYING;lastFrameNanos=System.nanoTime();break;
            case "pauseSettings":settingsFromPause=true;screen=Screen.SETTINGS;break;
            case "again":startGame(mode);break;
            case "menu":removeCallbacks(hardStart);settingsFromPause=false;editingPausedGame=false;screen=Screen.MENU;break;
        }
        syncMusicToState();
        invalidate();
    }

    private void checkEnd(){if(player.gameOver||(mode==Mode.VERSUS&&ai.gameOver))finishGame();configurePieceRules();}
    private void handleHardTap(){
        if(chaosUnlocked){difficulty=Difficulty.HARD;startGame(Mode.VERSUS);return;}long now=SystemClock.uptimeMillis();if(now-lastHardTap>750)hardTapCount=0;lastHardTap=now;hardTapCount++;removeCallbacks(hardStart);
        if(hardTapCount>=5){hardTapCount=0;chaosUnlocked=true;showNotice("CHAOS UNLOCKED!");}
        else postDelayed(hardStart,650);
    }

    private void setHand(Hand value){hand=value;prefs.edit().putString("hand",value.name()).apply();}
    private void changeVolume(boolean master,float delta){if(master){masterVolume=clamp(masterVolume+delta,0,1);prefs.edit().putFloat("masterVolume",masterVolume).apply();}else{musicVolume=clamp(musicVolume+delta,0,1);prefs.edit().putFloat("musicVolume",musicVolume).apply();}applyVolume();}
    private float clamp(float v,float min,float max){return Math.max(min,Math.min(max,v));}
    private String controlKey(String name){return "control_"+hand.name().toLowerCase(Locale.ROOT)+"_"+name+"_";}
    private float controlScale(String name){return prefs.getFloat(controlKey(name)+"scale",1f);}
    private float controlAlpha(String name){return prefs.getFloat(controlKey(name)+"alpha",1f);}
    private void adjustControl(String attribute,float delta,float min,float max){if(selectedControl==null)return;String key=controlKey(selectedControl)+attribute;float old=attribute.equals("scale")?controlScale(selectedControl):controlAlpha(selectedControl);prefs.edit().putFloat(key,clamp(old+delta,min,max)).apply();}
    private void resetControls(){SharedPreferences.Editor e=prefs.edit();for(String n:new String[]{"left","right","up","down","rotate","hold"}){String k=controlKey(n);e.remove(k+"x").remove(k+"y").remove(k+"scale").remove(k+"alpha");}e.apply();}

    private void loadHighScore(){highScore=prefs.getInt("score_"+username.trim().toLowerCase(Locale.ROOT),0);}
    private void saveHighScore(){prefs.edit().putInt("score_"+username.trim().toLowerCase(Locale.ROOT),highScore).apply();}
}
