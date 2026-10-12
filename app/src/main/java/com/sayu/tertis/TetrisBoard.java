package com.sayu.tertis;

import android.graphics.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

final class TetrisBoard {
    static final int WIDTH = 10, VISIBLE_HEIGHT = 20, HEIGHT = 40;
    private static final Random RNG = new Random();

    enum Kind {
        I(Color.rgb(31, 209, 240), new int[][][]{{{0,1},{1,1},{2,1},{3,1}},{{2,0},{2,1},{2,2},{2,3}}}),
        O(Color.rgb(252, 209, 46), new int[][][]{{{1,0},{2,0},{1,1},{2,1}}}),
        T(Color.rgb(171, 92, 230), new int[][][]{{{0,1},{1,1},{2,1},{1,0}},{{1,0},{1,1},{2,1},{1,2}},{{1,2},{0,1},{1,1},{2,1}},{{1,0},{0,1},{1,1},{1,2}}}),
        S(Color.rgb(64, 204, 110), new int[][][]{{{1,0},{2,0},{0,1},{1,1}},{{1,0},{1,1},{2,1},{2,2}}}),
        Z(Color.rgb(240, 71, 82), new int[][][]{{{0,0},{1,0},{1,1},{2,1}},{{2,0},{1,1},{2,1},{1,2}}}),
        J(Color.rgb(61, 110, 235), new int[][][]{{{0,0},{0,1},{1,1},{2,1}},{{1,0},{2,0},{1,1},{1,2}},{{0,1},{1,1},{2,1},{2,2}},{{1,0},{1,1},{0,2},{1,2}}}),
        L(Color.rgb(245, 140, 51), new int[][][]{{{2,0},{0,1},{1,1},{2,1}},{{1,0},{1,1},{1,2},{2,2}},{{0,1},{1,1},{2,1},{0,2}},{{0,0},{1,0},{1,1},{1,2}}});

        final int color;
        final int[][][] rotations;
        Kind(int color, int[][][] rotations) { this.color = color; this.rotations = rotations; }
        static Kind random() { Kind[] values = values(); return values[RNG.nextInt(values.length)]; }
    }

    static final class Piece {
        Kind kind;
        int rotation, x = 3, y = 20;
        Piece(Kind kind) { this.kind = kind; }
        Piece(Piece other) { kind = other.kind; rotation = other.rotation; x = other.x; y = other.y; }
        int[][] blocks() {
            int[][] source = kind.rotations[rotation];
            int[][] result = new int[source.length][2];
            for (int i = 0; i < source.length; i++) { result[i][0] = x + source[i][0]; result[i][1] = y + source[i][1]; }
            return result;
        }
    }

    final int[][] cells = new int[HEIGHT][WIDTH];
    Piece piece;
    Kind hold, next = Kind.random();
    boolean canHold = true, gameOver;
    int score, lines, pieceId;

    void reset() {
        for (int[] row : cells) Arrays.fill(row, Color.TRANSPARENT);
        piece = null; hold = null; canHold = true; next = Kind.random();
        score = 0; lines = 0; gameOver = false; pieceId = 0; spawn(null);
    }

    void spawn(Kind requested) {
        pieceId++;
        Kind selected = requested == null ? next : requested;
        if (requested == null) next = Kind.random();
        Piece candidate = new Piece(selected);
        if (valid(candidate)) piece = candidate;
        else { piece = null; gameOver = true; }
    }

    boolean valid(Piece candidate) {
        for (int[] b : candidate.blocks()) {
            if (b[0] < 0 || b[0] >= WIDTH || b[1] < 0 || b[1] >= HEIGHT || cells[b[1]][b[0]] != Color.TRANSPARENT) return false;
        }
        return true;
    }

    boolean move(int dx, int dy) {
        if (piece == null || gameOver) return false;
        Piece candidate = new Piece(piece); candidate.x += dx; candidate.y += dy;
        if (!valid(candidate)) return false;
        piece = candidate; return true;
    }

    boolean rotate() {
        if (piece == null || gameOver) return false;
        for (int kick : new int[]{0,-1,1,-2,2}) {
            Piece candidate = new Piece(piece);
            candidate.rotation = (candidate.rotation + 1) % candidate.kind.rotations.length;
            candidate.x += kick;
            if (valid(candidate)) { piece = candidate; return true; }
        }
        return false;
    }

    int hardDrop() {
        int distance = 0;
        while (move(0, -1)) distance++;
        score += distance * 2;
        return lock();
    }

    Integer tick() { return move(0, -1) ? null : lock(); }

    void useHold() {
        if (!canHold || piece == null || gameOver) return;
        Kind old = hold; hold = piece.kind; piece = null; canHold = false;
        spawn(old);
    }

    private int lock() {
        if (piece == null) return 0;
        Kind kind = piece.kind;
        for (int[] b : piece.blocks()) if (b[1] >= 0 && b[1] < HEIGHT) cells[b[1]][b[0]] = kind.color;
        piece = null;
        int cleared = clearLines();
        canHold = true;
        if (hasHiddenBlocks()) { gameOver = true; return cleared; }
        spawn(null);
        return cleared;
    }

    private int clearLines() {
        List<int[]> kept = new ArrayList<>();
        for (int y = 0; y < HEIGHT; y++) {
            boolean full = true;
            for (int x = 0; x < WIDTH; x++) if (cells[y][x] == Color.TRANSPARENT) { full = false; break; }
            if (!full) kept.add(Arrays.copyOf(cells[y], WIDTH));
        }
        int count = HEIGHT - kept.size();
        if (count == 0) return 0;
        for (int y = 0; y < kept.size(); y++) System.arraycopy(kept.get(y), 0, cells[y], 0, WIDTH);
        for (int y = kept.size(); y < HEIGHT; y++) Arrays.fill(cells[y], Color.TRANSPARENT);
        lines += count;
        int level = Math.max(1, lines / 10 + 1);
        int[] points = {0,100,300,500,800};
        score += points[Math.min(count, 4)] * level;
        return count;
    }

    void addGarbage(int count) {
        for (int n = 0; n < count && !gameOver; n++) {
            for (int y = HEIGHT - 1; y > 0; y--) System.arraycopy(cells[y - 1], 0, cells[y], 0, WIDTH);
            int hole = RNG.nextInt(WIDTH);
            for (int x = 0; x < WIDTH; x++) cells[0][x] = x == hole ? Color.TRANSPARENT : Color.rgb(97,97,97);
            if (hasHiddenBlocks()) { gameOver = true; piece = null; return; }
            if (piece != null) {
                Piece raised = new Piece(piece); raised.y++;
                if (valid(raised)) piece = raised; else { gameOver = true; piece = null; }
            }
        }
    }

    private boolean hasHiddenBlocks() {
        for (int y = VISIBLE_HEIGHT; y < HEIGHT; y++) for (int x = 0; x < WIDTH; x++) if (cells[y][x] != Color.TRANSPARENT) return true;
        return false;
    }
}
