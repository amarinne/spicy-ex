package com.eza.spicyex.hooks;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.eza.spicyex.SettingsUiStrings;
import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.lyrics.AppliedLine;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.xposed.XpLog;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Request;
import okhttp3.Response;

import static com.eza.spicyex.lyrics.LyricUtils.isBlank;
import static com.eza.spicyex.lyrics.LyricUtils.safe;
import static com.eza.spicyex.lyrics.LyricUtils.spotifyUriToWebUrl;
import static com.eza.spicyex.lyrics.LyricUtils.trackIdFromUri;

/**
 * Lyric share card: a preview sheet over the lyrics, and the image it shares.
 *
 * <p>The card is drawn in its own fixed 1080x1350 pixel space (never device dp, so text is the
 * same size on every phone). It comes in four designs - Glass, Classic, Minimal, Polaroid - over
 * one of three backdrops: the blurred artwork (the lyrics screen's own look), the artwork's
 * colours as a gradient, or the artist's photo, fetched from Spotify's Web API. Lyrics are fitted
 * by shrinking to a floor size; a selection that still would not fit is refused rather than
 * overflowing, and the card says so.
 *
 * <p>Changes animate instead of blinking: another design slides in from the side it was picked
 * on, extra lines slide the card up or down, and toggling the translation cross-fades. Cards
 * render off the main thread; the newest request wins. The shared image can carry the track's
 * Spotify Code (scannable in the Spotify app's camera search), and the share text carries the
 * track as a tappable URL - an image itself cannot hold a link.
 */
final class LyricsShareCardController {
    private static final String TAG = "[SpicyExLyricsShare]";
    private static final int W = 1080;
    /** Spotify Code width on the card. At ~300px (a quarter of the card) it was too small to read
     *  at a glance or to scan from a screenshot; these keep the title beside it readable. */
    private static final int CODE_W = 430;
    private static final int CODE_W_POLAROID = 410;
    private static final int CODE_W_CLASSIC = 380;
    private static final int H = 1350;
    // A short quote is set large enough to fill the card instead of floating in empty space.
    private static final float MAX_TEXT = 128f;
    private static final float MIN_TEXT = 34f;
    private static final String PREFS = "SpotifyPlus";
    private static final String PREF_DESIGN = "share_card_design";
    private static final String PREF_BACKDROP = "share_card_backdrop";
    private static final String PREF_CODE = "share_card_spotify_code";

    enum Design { GLASS, CLASSIC, MINIMAL, POLAROID, POSTER, VINYL, TICKET, SPOTLIGHT }
    enum Backdrop { BLUR, COLOR, ARTIST }

    /** Renders the lyrics screen's own background at a given size. */
    interface BackgroundSnapshot {
        Bitmap render(int width, int height);
    }
    /** SLIDE_UP/DOWN and TEXT move only the lyric text layer; the rest swap the whole card. */
    /** ALIGN: the lines re-aligned or moved on the card, each sliding to its new place. */
    private enum Transition { SLIDE_LEFT, SLIDE_RIGHT, SLIDE_UP, SLIDE_DOWN, FADE, TEXT, ALIGN }

    private static final ExecutorService RENDER = Executors.newSingleThreadExecutor();
    private static final ExecutorService THUMBS = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(() -> {
            // Just below normal: the background class is throttled so hard inside a busy Spotify
            // that half the strip never arrived.
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_LESS_FAVORABLE);
            runnable.run();
        }, "SpicyExShareThumbs");
        thread.setDaemon(true);
        return thread;
    });
    /** Design strip thumbnails, in dp: the card's own 4:5. */
    private static final int THUMB_W_DP = 58;
    private static final int THUMB_H_DP = 72;
    private static final String PREF_ALIGN = "share_card_text_align";
    private static final String PREF_POS = "share_card_text_pos";
    private static final java.util.Map<String, Bitmap> ARTIST_IMAGES = new java.util.LinkedHashMap<>();
    /** Spotify Code images by "uri|dark" - bars white on black, or black on white for paper. */
    private static final java.util.Map<String, Bitmap> SPOTIFY_CODES = new java.util.LinkedHashMap<>();

    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private final SettingsUiStrings strings;
    private View overlay;
    private FrameLayout cardHost;
    /** The card on screen: its artwork/chrome layer and, above it, the lyric text layer. */
    private FrameLayout currentCard;
    private ImageView currentBase;
    /** The lyric text over the card, one view per lyric line (with its translation). */
    private FrameLayout currentTextLayer;
    private Map<Integer, ImageView> pieceViews = new java.util.HashMap<>();
    private Map<Integer, Piece> pieceData = new java.util.HashMap<>();

    /** One lyric line (and its translation) as set on the card: its image and where it sits. */
    private static final class Piece {
        final int id;
        final Bitmap bitmap;
        final float x;
        final float y;
        final float size;
        /** Each wrapped line in the image (the lyric's, then its translation's): left, right,
         *  top, bottom - so a re-alignment can slide every line on its own. */
        final float[] lineLeft, lineRight, lineTop, lineBottom;

        Piece(int id, Bitmap bitmap, float x, float y, float size, List<float[]> lines) {
            this.id = id;
            this.bitmap = bitmap;
            this.x = x;
            this.y = y;
            this.size = size;
            int n = lines.size();
            lineLeft = new float[n];
            lineRight = new float[n];
            lineTop = new float[n];
            lineBottom = new float[n];
            for (int i = 0; i < n; i++) {
                float[] line = lines.get(i);
                lineLeft[i] = line[0];
                lineRight[i] = line[1];
                lineTop[i] = line[2];
                lineBottom[i] = line[3];
            }
        }
    }

    /** The wrapped lines of a layout drawn {@code dy} down the piece's image. */
    private static void addLines(List<float[]> out, StaticLayout layout, float dy) {
        for (int l = 0; l < layout.getLineCount(); l++) {
            out.add(new float[]{layout.getLineLeft(l), layout.getLineRight(l),
                    dy + layout.getLineTop(l), dy + layout.getLineBottom(l)});
        }
    }
    // Carousel: the neighbouring designs peek in at both edges, so sideways swiping is visible
    // without a row of design chips.
    private ImageView peekPrev;
    private ImageView peekNext;
    private int peekGeneration;
    /** The shown card as it is shared: drawn full size on demand. */
    private CardRecipe currentRecipe;
    /** The Spotify Code on the shown card (null without one), drawn by its own view. */
    private Bitmap currentCode;
    /** The code view on the shown card: a stand-in dancing until the real code arrives. */
    private CodeView currentCodeView;
    /** The code could not be fetched this opening: the card is laid out without it. */
    private boolean codeFailed;
    private int cardWidthPx;
    /** selectionFits by design, translation and range - asked again on every drag step. */
    private final Map<String, Boolean> fitCache = new java.util.HashMap<>();
    private TextView hint;
    private final List<TextView> backdropChips = new ArrayList<>();
    private TextView codeChip;
    private TextView translationChip;
    /** Top bar: the current design's name. */
    private TextView designLabel;
    private TextView designCounter;
    private final List<TextView> tabChips = new ArrayList<>();
    private final List<ImageView> alignButtons = new ArrayList<>();
    private final List<ImageView> posButtons = new ArrayList<>();
    private View alignGroup;
    private TextView autoChip;
    // Design strip: a small live render of every design, the current one ringed.
    private HorizontalScrollView designStrip;
    private final List<ImageView> designThumbs = new ArrayList<>();
    private final List<View> designFrames = new ArrayList<>();
    private final List<TextView> designNames = new ArrayList<>();
    private volatile int thumbGeneration;
    private LyricsDocument document;
    /** The lyric lines on the card, by document index: any lines, not only a run of them. */
    private final java.util.TreeSet<Integer> picked = new java.util.TreeSet<>();
    private boolean showTranslation = true;
    private SpotifyTrack track;
    private Bitmap artwork;
    private Bitmap artistImage;
    private ViewGroup root;
    private Design design;
    private Backdrop backdrop;
    private boolean spotifyCode;
    private TextStyle textStyle;
    private int generation;
    private BackgroundSnapshot backgroundSnapshot;
    /** The lyrics background frozen when the sheet opened; the "lyrics background" backdrop. */
    private Bitmap lyricsBackground;

    void setBackgroundSnapshot(BackgroundSnapshot snapshot) {
        backgroundSnapshot = snapshot;
    }

    /** What the lyrics view under the sheet may skip: it is covered, or at least not visible
     *  in motion, while the sheet is up. */
    interface SheetListener {
        /** @param showing the sheet is up (the lyrics can stop animating: nobody sees them move)
         *  @param covering it hides them completely (they need not even be drawn) */
        void onSheet(boolean showing, boolean covering);
    }

    private SheetListener sheetListener;
    private boolean sheetCovering;

    void setSheetListener(SheetListener listener) {
        sheetListener = listener;
    }

    View overlayView() {
        return overlay;
    }

    private void reportSheet(boolean showing, boolean covering) {
        if (!showing) covering = false;
        sheetCovering = covering;
        if (sheetListener != null) sheetListener.onSheet(showing, covering);
    }

    LyricsShareCardController(Activity activity) {
        this.activity = activity;
        this.prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.strings = com.eza.spicyex.UiLanguage.strings(activity,
                com.eza.spicyex.SpotifyPlusConfig.from(activity).get(com.eza.spicyex.Settings.UI_LANGUAGE));
        this.design = enumOr(Design.class, prefs.getString(PREF_DESIGN, null), Design.GLASS);
        this.backdrop = enumOr(Backdrop.class, prefs.getString(PREF_BACKDROP, null), Backdrop.BLUR);
        this.spotifyCode = prefs.getBoolean(PREF_CODE, false);
        this.textStyle = new TextStyle(enumOr(TextAlign.class, prefs.getString(PREF_ALIGN, null), TextAlign.AUTO),
                enumOr(TextPos.class, prefs.getString(PREF_POS, null), TextPos.AUTO));
    }

    /** Long-pressed a real lyric line: quote it, with its translation underneath if present. */
    /** The lyric row that was pressed; its text flies into the card as the sheet opens. */
    private View sourceRow;

    void showForLine(ViewGroup root, LyricsDocument doc, SpotifyTrack track, Bitmap art, int index,
                     View sourceRow) {
        this.sourceRow = sourceRow;
        showForLine(root, doc, track, art, index);
    }

    void showForLine(ViewGroup root, LyricsDocument doc, SpotifyTrack track, Bitmap art, int index) {
        this.root = root;
        this.document = doc;
        this.track = track;
        this.artwork = art;
        this.picked.clear();
        this.picked.add(index);
        this.showTranslation = true;
        show();
    }

    /** No lyrics for this track at all: fall back to a plain "now playing" track card. */
    void showTrackCard(ViewGroup root, SpotifyTrack track, Bitmap art) {
        this.sourceRow = null;
        this.root = root;
        this.document = null;
        this.track = track;
        this.artwork = art;
        this.picked.clear();
        this.showTranslation = false;
        show();
    }

    private String s(String key, String fallback) {
        return strings.get("share_" + key, fallback);
    }

    private void show() {
        if (activity == null || root == null || track == null) return;
        dismiss();
        artistImage = cachedArtist(track);
        lyricsBackground = null;
        fitCache.clear();
        codePlayed = false;
        codeFailed = false;
        overlay = buildPreview();
        // Above the lyric screen's own chrome, which is raised with elevation and otherwise draws
        // through the sheet regardless of child order.
        overlay.setElevation(dp(64));
        root.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // From the long press on, the lyrics underneath hold still: their blurred rows keep the
        // blur they have instead of re-rendering it every frame under the opening sheet.
        reportSheet(true, false);
        BackgroundSnapshot snapshot = backgroundSnapshot;
        if (snapshot != null) {
            // Off the main thread; the card redraws with it as soon as it is ready.
            RENDER.execute(() -> {
                Bitmap frame = snapshot.render(W, H);
                main.post(() -> {
                    if (overlay == null || frame == null) return;
                    lyricsBackground = frame;
                    if (backdrop == Backdrop.COLOR) {
                        render(Transition.FADE);
                        renderThumbs();
                    }
                });
            });
        }
        render(Transition.FADE);
        renderThumbs();
        if (backdrop == Backdrop.ARTIST && artistImage == null) fetchArtistImage();
        if (spotifyCode) fetchSpotifyCode();
        if (document != null && !picked.isEmpty()) {
            View teaseHost = overlay;
            teaseHost.postDelayed(() -> {
                if (overlay == teaseHost) teaseNextLine();
            }, row0Delay());
        }
        // The pressed line lifts off at once, on the sheet's first frame - waiting for the card
        // left it hidden under the fading-in blur for a moment. If the card is not rendered yet
        // when the words arrive, they hover in place until it fades in under them.
        View row = sourceRow;
        sourceRow = null;
        if (row != null && document != null && row.isAttachedToWindow()) {
            View host = overlay;
            host.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    host.getViewTreeObserver().removeOnPreDrawListener(this);
                    if (overlay == host) flyQuoteIn(row);
                    return true;
                }
            });
        }
    }

    /** Words that landed before the first card: handed over to its text when it comes. */
    private Runnable pendingHandOff;
    /** The words in flight (or hovering), which the held finger's pull also moves. */
    private View currentFlight;
    /** The Spotify Code builds itself once per opening; later cards show it whole. */
    private boolean codePlayed;

    /**
     * The pressed lyric lifts out of the list and glides into the card: a snapshot of the row
     * travels from where it sits on screen to where the quote is set in the card, scaled to the
     * card's type size, and dissolves as the card itself fades in underneath it.
     */
    private void flyQuoteIn(View row) {
        try {
            if (flyWordsIn(row)) return;
        } catch (Throwable error) {
            XpLog.log(TAG + " word fly-in skipped: " + error);
        }
        flySnapshotIn(row);
    }

    /** True while the pressed lyric's words are in flight: the card's own text waits for them. */
    private boolean quoteFlying;

    /**
     * Word by word: each word of the pressed line leaves its place in the lyric list and lands on
     * its own place in the card - its own line and x there - a beat after the word before it. On
     * the way it turns from the word as it looked in the list (its real pixels: font, size,
     * highlight) into the card's own glyphs (the card's font, size and colour), so that it lands
     * exactly on the card's text, which takes over under it.
     *
     * <p>A row is either one text view for the line or, for word-synced lyrics, one small text
     * view per word (or syllable); both are mapped by finding each view's text in the line.
     *
     * @return false when the row's text cannot be mapped onto the card's (the snapshot flies).
     */
    private boolean flyWordsIn(View row) {
        if (!(overlay instanceof ViewGroup) || cardHost == null || cardHost.getWidth() <= 0) return false;
        List<String> quotes = collectQuotes(selection());
        if (quotes.isEmpty()) return false;
        String quote = quotes.get(0);

        // The row's text views, each placed at its offset in the line.
        List<TextView> views = new ArrayList<>();
        collectTexts(row, views);
        List<SourceRun> runs = new ArrayList<>();
        int cursor = 0;
        for (TextView view : views) {
            if (view.getLayout() == null || view.getWidth() <= 0) continue;
            String own = view.getText().toString();
            String trimmed = own.trim();
            if (trimmed.isEmpty()) continue;
            int at = quote.indexOf(trimmed, cursor);
            // Only a gap of spacing between runs: anything further is another line's text
            // (a translation, the romanization) that merely contains the same letters.
            if (at < 0 || !quote.substring(cursor, at).trim().isEmpty()) continue;
            runs.add(new SourceRun(view, at, own.indexOf(trimmed), trimmed.length()));
            cursor = at + trimmed.length();
        }
        if (runs.isEmpty() || !quote.substring(cursor).trim().isEmpty()) return false;

        TextBox box = lyricBox(design, textStyle, layoutCode(design) != null);
        Fitted fitted = layoutLyrics(quotes, collectTranslations(selection()), box, false);
        if (fitted.main.size() != quotes.size()) return false;
        StaticLayout target = fitted.main.get(0);
        float targetSize = target.getPaint().getTextSize();
        TextPaint cardPaint = new TextPaint(target.getPaint());
        cardPaint.setColor(box.color);
        Paint.FontMetrics cardMetrics = cardPaint.getFontMetrics();

        float cardScale = cardHost.getWidth() / (float) W;
        float quoteTop = quoteTop(design, textStyle, box, fitted.height);
        int[] overlayLoc = new int[2];
        int[] cardLoc = new int[2];
        overlay.getLocationOnScreen(overlayLoc);
        cardHost.getLocationOnScreen(cardLoc);
        // The card is still rising into place; aim for where it comes to rest.
        View carousel = (View) cardHost.getParent();
        float settle = carousel == null ? 0f : carousel.getTranslationY();

        // Each source view drawn once; words are cut from it with their highlight as seen.
        Map<TextView, Bitmap> drawn = new java.util.HashMap<>();
        List<FlyingWord> flying = new ArrayList<>();
        java.text.BreakIterator words = java.text.BreakIterator.getWordInstance();
        words.setText(quote);
        int order = 0;
        for (int start = words.first(), end = words.next(); end != java.text.BreakIterator.DONE;
             start = end, end = words.next()) {
            if (quote.substring(start, end).trim().isEmpty()) continue;
            boolean any = false;
            // A word may span several syllable views: each piece flies, all starting together.
            for (SourceRun run : runs) {
                int pieceStart = Math.max(start, run.quoteStart);
                int pieceEnd = Math.min(end, run.quoteStart + run.length);
                if (pieceEnd <= pieceStart) continue;
                FlyingWord w = flyingPiece(run, quote, pieceStart, pieceEnd, drawn, overlayLoc,
                        target, cardPaint, cardMetrics, targetSize, box, quoteTop, cardScale, cardLoc, settle);
                if (w == null) continue;
                w.order = order;
                flying.add(w);
                any = true;
            }
            if (any) order++;
        }
        for (Bitmap whole : drawn.values()) whole.recycle();
        if (flying.isEmpty()) return false;

        FlightView flight = new FlightView(activity, flying, dp(14));
        flight.setElevation(dp(70));
        ((ViewGroup) overlay).addView(flight, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        quoteFlying = true;
        currentFlight = flight;
        flight.setTranslationX(stageView == null ? 0f : stageView.getTranslationX());
        flight.setTranslationY(stageView == null ? 0f : stageView.getTranslationY());
        if (currentTextLayer != null) currentTextLayer.setAlpha(0f);

        int n = order;
        long stagger = n <= 1 ? 0 : Math.min(40L, 420L / (n - 1));
        long total = FlightView.FLIGHT_MS + stagger * (n - 1);
        flight.stagger = stagger;
        android.animation.ValueAnimator animator = android.animation.ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(total);
        animator.setInterpolator(null);
        animator.addUpdateListener(a -> flight.setElapsed(a.getAnimatedFraction() * total));
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                quoteFlying = false;
                // The words sit exactly on the card's text now: swap them for it - once the card
                // is there; until then they hover where its text will be.
                handOff(() -> {
                    // Handing over: the pull no longer moves it (it would cancel the fade).
                    if (currentFlight == flight) currentFlight = null;
                    if (currentTextLayer != null) {
                        currentTextLayer.animate().alpha(1f).setDuration(120).start();
                    }
                    flight.animate().alpha(0f).setStartDelay(60).setDuration(160).withEndAction(() -> {
                        if (flight.getParent() instanceof ViewGroup) ((ViewGroup) flight.getParent()).removeView(flight);
                    }).start();
                });
            }
        });
        animator.start();
        return true;
    }

    /** One run's share of a word: cut from the row as seen, and set as the card sets it. */
    private FlyingWord flyingPiece(SourceRun run, String quote, int pieceStart, int pieceEnd,
                                   Map<TextView, Bitmap> drawn, int[] overlayLoc, StaticLayout target,
                                   TextPaint cardPaint, Paint.FontMetrics cardMetrics, float targetSize,
                                   TextBox box, float quoteTop, float cardScale, int[] cardLoc, float settle) {
        TextView view = run.view;
        android.text.Layout from = view.getLayout();
        int s0 = run.viewStart + (pieceStart - run.quoteStart);
        int s1 = run.viewStart + (pieceEnd - run.quoteStart);
        int line = from.getLineForOffset(s0);
        float left = from.getPrimaryHorizontal(s0);
        float right = from.getLineForOffset(Math.max(s0, s1 - 1)) == line && s1 < view.getText().length()
                ? from.getPrimaryHorizontal(s1) : from.getLineRight(line);
        if (right <= left) return null;
        Bitmap whole = drawn.get(view);
        if (whole == null) {
            whole = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
            view.draw(new Canvas(whole));
            drawn.put(view, whole);
        }
        int cropLeft = Math.max(0, Math.round(view.getTotalPaddingLeft() + left) - 2);
        int cropRight = Math.min(whole.getWidth(), Math.round(view.getTotalPaddingLeft() + right) + 2);
        int cropTop = Math.max(0, view.getTotalPaddingTop() + from.getLineTop(line));
        int cropBottom = Math.min(whole.getHeight(), view.getTotalPaddingTop() + from.getLineBottom(line));
        if (cropRight <= cropLeft || cropBottom <= cropTop) return null;

        float viewScale = 1f;
        for (View v = view; v != null && v != root; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
            viewScale *= v.getScaleX();
        }
        int[] viewLoc = new int[2];
        view.getLocationOnScreen(viewLoc);
        FlyingWord w = new FlyingWord();
        w.source = Bitmap.createBitmap(whole, cropLeft, cropTop, cropRight - cropLeft, cropBottom - cropTop);
        w.sourceSize = view.getTextSize();
        w.sourceOriginX = view.getTotalPaddingLeft() + left - cropLeft;
        w.sourceBaseline = view.getTotalPaddingTop() + from.getLineBaseline(line) - cropTop;
        w.fromX = viewLoc[0] - overlayLoc[0] + (view.getTotalPaddingLeft() + left) * viewScale;
        w.fromBaseline = viewLoc[1] - overlayLoc[1]
                + (view.getTotalPaddingTop() + from.getLineBaseline(line)) * viewScale;
        w.fromScreenSize = view.getTextSize() * viewScale;

        // The same letters as the card sets them, at the card's own size and colour.
        String text = quote.substring(pieceStart, pieceEnd);
        int pad = Math.round(targetSize * 0.25f);
        Bitmap card = Bitmap.createBitmap(Math.max(1, Math.round(cardPaint.measureText(text)) + pad * 2),
                Math.max(1, Math.round(cardMetrics.bottom - cardMetrics.top) + pad * 2),
                Bitmap.Config.ARGB_8888);
        new Canvas(card).drawText(text, pad, pad - cardMetrics.top, cardPaint);
        w.card = card;
        w.cardOriginX = pad;
        w.cardBaseline = pad - cardMetrics.top;
        w.cardSize = targetSize;
        int dstLine = target.getLineForOffset(pieceStart);
        w.toX = cardLoc[0] - overlayLoc[0] + (box.left + target.getPrimaryHorizontal(pieceStart)) * cardScale;
        w.toBaseline = cardLoc[1] - settle - overlayLoc[1]
                + (quoteTop + target.getLineBaseline(dstLine)) * cardScale;
        w.toScreenSize = targetSize * cardScale;
        return w;
    }

    private static void collectTexts(View view, List<TextView> out) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof TextView) {
            if (((TextView) view).getText().length() > 0) out.add((TextView) view);
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectTexts(group.getChildAt(i), out);
        }
    }

    /** One of the row's text views and where its text sits in the line. */
    private static final class SourceRun {
        final TextView view;
        final int quoteStart;
        final int viewStart;
        final int length;

        SourceRun(TextView view, int quoteStart, int viewStart, int length) {
            this.view = view;
            this.quoteStart = quoteStart;
            this.viewStart = viewStart;
            this.length = length;
        }
    }

    /** A word in flight: its look in the list and on the card, and both places. */
    private static final class FlyingWord {
        int order;
        Bitmap source;
        float sourceSize, sourceOriginX, sourceBaseline;
        float fromX, fromBaseline, fromScreenSize;
        Bitmap card;
        float cardSize, cardOriginX, cardBaseline;
        float toX, toBaseline, toScreenSize;
    }

    /** Draws every flying word; one view for all of them, redrawn per frame. */
    private static final class FlightView extends View {
        static final long FLIGHT_MS = 640L;
        private final List<FlyingWord> words;
        private final float hop;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final PathInterpolator along = new PathInterpolator(0.3f, 0f, 0.1f, 1f);
        private final PathInterpolator down = new PathInterpolator(0.45f, 0f, 0.15f, 1f);
        long stagger;
        private float elapsed;

        FlightView(Context context, List<FlyingWord> words, float hop) {
            super(context);
            this.words = words;
            this.hop = hop;
        }

        void setElapsed(float elapsed) {
            this.elapsed = elapsed;
            invalidate();
        }

        private static float smooth(float edge0, float edge1, float x) {
            float t = Math.max(0f, Math.min(1f, (x - edge0) / (edge1 - edge0)));
            return t * t * (3f - 2f * t);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            for (int i = 0; i < words.size(); i++) {
                FlyingWord w = words.get(i);
                float t = Math.max(0f, Math.min(1f, (elapsed - w.order * stagger) / FLIGHT_MS));
                float ex = along.getInterpolation(t);
                float ey = down.getInterpolation(t);
                float x = w.fromX + (w.toX - w.fromX) * ex;
                // x leads and y follows, with a small lift mid-way: a gentle arc.
                float baseline = w.fromBaseline + (w.toBaseline - w.fromBaseline) * ey
                        - hop * (float) Math.sin(Math.PI * t);
                float size = w.fromScreenSize + (w.toScreenSize - w.fromScreenSize) * ex;
                float cardAlpha = smooth(0.12f, 0.7f, t);
                if (cardAlpha < 1f) draw(canvas, w.source, w.sourceOriginX, w.sourceBaseline,
                        size / w.sourceSize, x, baseline, 1f - smooth(0.2f, 0.8f, t));
                if (cardAlpha > 0f) draw(canvas, w.card, w.cardOriginX, w.cardBaseline,
                        size / w.cardSize, x, baseline, cardAlpha);
            }
        }

        private void draw(Canvas canvas, Bitmap bitmap, float originX, float originBaseline, float scale,
                          float x, float baseline, float alpha) {
            if (alpha <= 0f) return;
            float left = x - originX * scale;
            float top = baseline - originBaseline * scale;
            rect.set(left, top, left + bitmap.getWidth() * scale, top + bitmap.getHeight() * scale);
            paint.setAlpha(Math.round(255 * alpha));
            canvas.drawBitmap(bitmap, null, rect, paint);
        }
    }

    /** Runs now if the first card is on screen, else when it arrives (see swapCard). */
    private void handOff(Runnable run) {
        if (currentCard != null) run.run();
        else pendingHandOff = run;
    }

    // Still holding after the long press opened the sheet: the finger's moves pull the card (and
    // the words flying to it) a little after it, as on a rubber band, and let go it springs back.
    private View stageView;
    private boolean heldTracking;
    private float heldX0, heldY0;

    /** The rest of the long-press gesture, forwarded by the lyrics view once the sheet is up. */
    void heldDrag(android.view.MotionEvent event) {
        if (overlay == null || stageView == null) return;
        switch (event.getActionMasked()) {
            case android.view.MotionEvent.ACTION_MOVE: {
                if (!heldTracking) {
                    heldTracking = true;
                    heldX0 = event.getRawX();
                    heldY0 = event.getRawY();
                }
                float x = rubber(event.getRawX() - heldX0, dp(26));
                float y = rubber(event.getRawY() - heldY0, dp(26));
                pullHeld(x, y, false);
                break;
            }
            case android.view.MotionEvent.ACTION_UP:
            case android.view.MotionEvent.ACTION_CANCEL:
                if (heldTracking) pullHeld(0f, 0f, true);
                heldTracking = false;
                break;
            default:
                break;
        }
    }

    private void pullHeld(float x, float y, boolean release) {
        View[] moved = {stageView, currentFlight};
        for (View view : moved) {
            if (view == null) continue;
            view.animate().cancel();
            if (release) {
                view.animate().translationX(0f).translationY(0f).setDuration(460)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(1.6f)).start();
            } else {
                view.setTranslationX(x);
                view.setTranslationY(y);
            }
        }
    }

    /** The whole row as one image - when its words cannot be matched to the card's. */
    private void flySnapshotIn(View row) {
        try {
            if (!(overlay instanceof ViewGroup) || cardHost == null || cardHost.getWidth() <= 0
                    || row.getWidth() <= 0 || row.getHeight() <= 0) return;
            TextView text = firstText(row);
            if (text == null || text.getTextSize() <= 0f) return;
            List<String> quotes = collectQuotes(selection());
            if (quotes.isEmpty()) return;
            TextBox box = lyricBox(design, textStyle, layoutCode(design) != null);
            Fitted fitted = layoutLyrics(quotes, collectTranslations(selection()), box, false);
            if (fitted.main.isEmpty()) return;
            float cardScale = cardHost.getWidth() / (float) W;
            float quoteTop = quoteTop(design, textStyle, box, fitted.height);
            float ghostScale = fitted.main.get(0).getPaint().getTextSize() * cardScale / text.getTextSize();

            Bitmap snapshot = Bitmap.createBitmap(row.getWidth(), row.getHeight(), Bitmap.Config.ARGB_8888);
            row.draw(new Canvas(snapshot));

            int[] overlayLoc = new int[2];
            int[] rowLoc = new int[2];
            int[] textLoc = new int[2];
            int[] cardLoc = new int[2];
            overlay.getLocationOnScreen(overlayLoc);
            row.getLocationOnScreen(rowLoc);
            text.getLocationOnScreen(textLoc);
            cardHost.getLocationOnScreen(cardLoc);
            // The sheet itself is still sliding up; aim for where the card will come to rest.
            View column = (View) cardHost.getParent();
            float settle = column == null ? 0f : column.getTranslationY();
            float textOffX = textLoc[0] - rowLoc[0] + text.getTotalPaddingLeft();
            float textOffY = textLoc[1] - rowLoc[1] + text.getTotalPaddingTop();
            float targetX = cardLoc[0] - overlayLoc[0] + box.left * cardScale - textOffX * ghostScale;
            float targetY = cardLoc[1] - settle - overlayLoc[1] + quoteTop * cardScale - textOffY * ghostScale;

            ImageView ghost = new ImageView(activity);
            ghost.setImageBitmap(snapshot);
            ghost.setPivotX(0f);
            ghost.setPivotY(0f);
            ((ViewGroup) overlay).addView(ghost, new FrameLayout.LayoutParams(row.getWidth(), row.getHeight()));
            ghost.setX(rowLoc[0] - overlayLoc[0]);
            ghost.setY(rowLoc[1] - overlayLoc[1]);
            ghost.setElevation(dp(2));

            ghost.animate().x(targetX).y(targetY).scaleX(ghostScale).scaleY(ghostScale)
                    .setDuration(560).setInterpolator(new PathInterpolator(0.3f, 0f, 0.1f, 1f))
                    .withEndAction(() -> handOff(() -> ghost.animate().alpha(0f).setDuration(220)
                            .withEndAction(() -> {
                                if (ghost.getParent() instanceof ViewGroup) {
                                    ((ViewGroup) ghost.getParent()).removeView(ghost);
                                }
                            }).start()))
                    .start();
        } catch (Throwable error) {
            XpLog.log(TAG + " quote fly-in skipped: " + error);
        }
    }

    /** After the quote has landed (fly-in ~800ms), or sooner when there was none. */
    private long row0Delay() {
        return sourceRow != null ? 1150L : 650L;
    }

    /**
     * Gesture hint: the next lyric peeks up from the bottom of the card as if about to be pulled
     * in, the card lifts a touch to make room, then both settle back - "swipe up to add".
     */
    private void teaseNextLine() {
        if (document == null || cardHost == null || !(cardHost.getParent() instanceof FrameLayout)) return;
        if (picked.isEmpty()) return;
        int next = nextLyric(picked.last(), document.appliedLines.size() - 1);
        if (next < 0) return;
        java.util.TreeSet<Integer> candidate = new java.util.TreeSet<>(picked);
        candidate.add(next);
        if (!selectionFits(candidate)) return;
        FrameLayout carousel = (FrameLayout) cardHost.getParent();

        // The next line waits just under the card's foot, a small arrow over it.
        LinearLayout pill = new LinearLayout(activity);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(12), dp(8), dp(16), dp(8));
        android.graphics.drawable.GradientDrawable bg = glass(dp(20), 0, 40);
        bg.setColor(Color.argb(170, 18, 18, 22));
        pill.setBackground(bg);
        pill.setElevation(dp(24));
        ImageView arrow = new ImageView(activity);
        arrow.setImageDrawable(new LineIcon(LineIcon.Kind.ARROW_UP, Color.WHITE));
        LinearLayout.LayoutParams arrowLp = new LinearLayout.LayoutParams(dp(16), dp(16));
        arrowLp.rightMargin = dp(8);
        pill.addView(arrow, arrowLp);
        TextView text = new TextView(activity);
        text.setText(safe(document.appliedLines.get(next).text));
        text.setTextColor(Color.WHITE);
        text.setTextSize(14);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setMaxWidth(Math.max(dp(100), cardHost.getWidth() - dp(90)));
        pill.addView(text);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = dp(14);
        carousel.addView(pill, lp);
        teasePill = pill;

        // In: the card lifts a touch as if to make room, and the line rises into view with it.
        PathInterpolator glide = new PathInterpolator(0.2f, 0.9f, 0.2f, 1f);
        pill.setAlpha(0f);
        pill.setTranslationY(dp(22));
        pill.setScaleX(0.94f);
        pill.setScaleY(0.94f);
        cardHost.animate().translationY(-dp(12)).setDuration(560).setInterpolator(glide).start();
        pill.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(560)
                .setInterpolator(glide).start();
        // While it waits, the arrow beckons upward twice.
        android.animation.ObjectAnimator bob = android.animation.ObjectAnimator.ofFloat(arrow,
                View.TRANSLATION_Y, 0f, -dp(3), 0f);
        bob.setDuration(520);
        bob.setRepeatCount(1);
        bob.setStartDelay(560);
        bob.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        bob.start();
        // Out: the line is drawn up toward the card and dissolves; the card settles back.
        pill.postDelayed(() -> endTease(pill, true), 1700);
    }

    /** The swipe-up hint on screen, if any: a touch on the card ends it early. */
    private View teasePill;

    private void endTease(View pill, boolean gently) {
        if (pill == null || pill.getParent() == null) return;
        if (teasePill == pill) teasePill = null;
        pill.animate().cancel();
        if (cardHost != null) {
            cardHost.animate().translationY(0f).setDuration(gently ? 620 : 240)
                    .setInterpolator(gently ? new android.view.animation.OvershootInterpolator(1.1f)
                            : new PathInterpolator(0.2f, 0.9f, 0.2f, 1f)).start();
        }
        pill.animate().alpha(0f).translationY(gently ? -dp(16) : 0f).scaleX(0.9f).scaleY(0.9f)
                .setDuration(gently ? 440 : 160).setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f))
                .withEndAction(() -> {
                    if (pill.getParent() instanceof ViewGroup) ((ViewGroup) pill.getParent()).removeView(pill);
                }).start();
    }

    private static TextView firstText(View view) {
        if (view instanceof TextView && ((TextView) view).getText().length() > 0
                && view.getVisibility() == View.VISIBLE) {
            return (TextView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = firstText(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    boolean isShowing() {
        return overlay != null;
    }

    void dismiss() {
        generation++;
        if (overlay != null) reportSheet(false, false);
        if (overlay != null && overlay.getParent() instanceof ViewGroup) {
            View leaving = overlay;
            leaving.animate().alpha(0f).setDuration(180).withLayer()
                    .withEndAction(() -> {
                        if (leaving.getParent() instanceof ViewGroup) {
                            ((ViewGroup) leaving.getParent()).removeView(leaving);
                        }
                    }).start();
        }
        overlay = null;
        cardHost = null;
        currentCard = null;
        thumbGeneration++;
        designThumbs.clear();
        designFrames.clear();
        designNames.clear();
        designStrip = null;
        designLabel = null;
        translationChip = null;
        designCounter = null;
        autoChip = null;
        alignGroup = null;
        alignButtons.clear();
        posButtons.clear();
        peekPrev = null;
        peekNext = null;
        currentTextLayer = null;
        currentCode = null;
        currentCodeView = null;
        lineSlides.clear();
        // Full-size bitmaps kept only for the open sheet: the frozen lyrics background and the
        // shareable card (drawn at most twice, 5-6 MB each) go with it.
        lyricsBackground = null;
        currentRecipe = null;
        pendingHandOff = null;
        currentFlight = null;
        stageView = null;
        heldTracking = false;
        picker = null;
        pickRows.clear();
        pieceViews = new java.util.HashMap<>();
        pieceData = new java.util.HashMap<>();
    }

    // ---------------------------------------------------------------- preview UI

    private View buildPreview() {
        FrameLayout scrim = new FrameLayout(activity);
        scrim.setOnClickListener(v -> dismiss());
        // The backdrop fades in on its own (a static layer), not the whole sheet: fading the
        // root re-drew every control and the card offscreen on each frame of the opening.
        FrameLayout ground = new FrameLayout(activity);
        ground.setBackgroundColor(Color.BLACK);
        ground.setAlpha(0f);
        scrim.addView(ground, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // The song's artwork, blurred and dimmed, fills the sheet behind everything.
        if (artwork != null) {
            ImageView ambience = new ImageView(activity);
            ambience.setScaleType(ImageView.ScaleType.CENTER_CROP);
            ambience.setImageBitmap(cachedBlur(artwork));
            ColorMatrix saturate = new ColorMatrix();
            saturate.setSaturation(1.4f);
            ambience.setColorFilter(new ColorMatrixColorFilter(saturate));
            ground.addView(ambience, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        View shade = new View(activity);
        shade.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.argb(150, 0, 0, 0), Color.argb(120, 0, 0, 0), Color.argb(215, 0, 0, 0)}));
        ground.addView(shade, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        android.util.DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        int screenW = root.getWidth() > 0 ? root.getWidth() : metrics.widthPixels;
        int screenH = root.getHeight() > 0 ? root.getHeight() : metrics.heightPixels;
        int insetTop = 0;
        int insetBottom = 0;
        int insetLeft = 0;
        int insetRight = 0;
        android.view.WindowInsets insets = root.getRootWindowInsets();
        if (insets != null) {
            insetTop = insets.getSystemWindowInsetTop();
            insetBottom = insets.getSystemWindowInsetBottom();
            insetLeft = insets.getSystemWindowInsetLeft();
            insetRight = insets.getSystemWindowInsetRight();
        }
        // Landscape puts the controls in a side panel so the card keeps the screen's height.
        boolean landscape = screenW > screenH;

        // Pulled down anywhere but on the card (whose vertical drags pick lines), the whole sheet
        // follows the finger and a flick or a long enough pull closes it - as the Layout
        // Editor's sheet does.
        PullSheet page = new PullSheet(activity);
        page.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        page.setPadding(insetLeft, insetTop, insetRight, insetBottom);
        page.onProgress = progress -> {
            ground.setAlpha(1f - 0.7f * progress);
            // Pulled down, the lyrics show through the dimmed backdrop again; back up, covered.
            boolean covering = progress <= 0.001f;
            if (overlay == scrim && covering != sheetCovering) reportSheet(true, covering);
        };
        page.onEmptyTap = this::dismiss;
        page.onDismiss = velocity -> {
            ground.animate().alpha(0f).setDuration(220).start();
            page.slideAway(velocity, this::dismiss);
        };

        int panelW = landscape ? Math.min(dp(400), Math.round(screenW * 0.46f)) : screenW - insetLeft - insetRight;
        View panel = buildPanel(landscape);
        int panelH = 0;
        if (!landscape) {
            panel.measure(View.MeasureSpec.makeMeasureSpec(panelW - dp(24), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            panelH = panel.getMeasuredHeight() + dp(12);
        }

        LinearLayout stageColumn = new LinearLayout(activity);
        stageColumn.setOrientation(LinearLayout.VERTICAL);
        // Nothing on the stage is cut at its frame: the card's shadow, the card lifting or
        // springing past its size, the hint rising from below it.
        stageColumn.setClipChildren(false);
        stageColumn.setClipToPadding(false);
        page.setClipChildren(false);
        page.setClipToPadding(false);
        // Not clickable: taps around the card fall through to the scrim and close the sheet.
        stageColumn.addView(topBar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(60)));

        // As large as the room between the top bar and the controls allows.
        int availW = landscape ? screenW - panelW - insetLeft - insetRight : screenW;
        int availH = screenH - insetTop - insetBottom - dp(60 + 30 + 12) - panelH;
        int cardWidthPx = Math.round(Math.min(availW * (landscape ? 0.84f : 0.82f),
                Math.max(dp(160), availH) * (float) W / H));
        int cardHeightPx = cardWidthPx * H / W;
        this.cardWidthPx = cardWidthPx;

        FrameLayout stage = new FrameLayout(activity);
        stageView = stage;
        stage.setClipChildren(false);
        stage.setClipToPadding(false);
        FrameLayout carousel = new FrameLayout(activity);
        carousel.setClipChildren(false);
        carousel.setClipToPadding(false);
        float peekOffset = cardWidthPx + dp(16);
        peekPrev = peekView(-peekOffset);
        peekNext = peekView(peekOffset);
        peekPrev.setOnClickListener(v -> stepDesign(-1));
        peekNext.setOnClickListener(v -> stepDesign(1));
        carousel.addView(peekPrev, new FrameLayout.LayoutParams(cardWidthPx, cardHeightPx, Gravity.CENTER));
        carousel.addView(peekNext, new FrameLayout.LayoutParams(cardWidthPx, cardHeightPx, Gravity.CENTER));
        cardHost = new FrameLayout(activity);
        // Hidden until the first card is in it: an empty host still casts its shadow frame.
        cardHost.setAlpha(0f);
        cardHost.setClipChildren(true);
        cardHost.setElevation(dp(18));
        cardHost.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                // The card's own rounded corners (64 of 1080), for its shadow.
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                        view.getWidth() * 64f / W);
            }
        });
        carousel.addView(cardHost, new FrameLayout.LayoutParams(cardWidthPx, cardHeightPx, Gravity.CENTER));
        stage.addView(carousel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, cardHeightPx, Gravity.CENTER));
        stageColumn.addView(stage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        installPreviewGestures(cardHost);
        page.excluded = cardHost;

        hint = new TextView(activity);
        hint.setTextColor(Color.argb(130, 255, 255, 255));
        hint.setTextSize(12);
        hint.setGravity(Gravity.CENTER);
        hint.setText(s("hint", "Swipe up to add lyrics · Tap to toggle translation"));
        hint.setVisibility(document == null ? View.INVISIBLE : View.VISIBLE);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = dp(8);
        hintLp.bottomMargin = dp(8);
        stageColumn.addView(hint, hintLp);

        if (landscape) {
            page.addView(stageColumn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(panelW, ViewGroup.LayoutParams.MATCH_PARENT);
            lp.setMargins(0, dp(12), dp(12), dp(12));
            page.addView(panel, lp);
        } else {
            page.addView(stageColumn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(12), 0, dp(12), dp(12));
            page.addView(panel, lp);
        }
        scrim.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        updateDesignChrome(false);
        updateTextControls();

        // The card rises into place (the quote fly-in aims for where it comes to rest) while the
        // controls slide in from their edge.
        PathInterpolator ease = new PathInterpolator(0.2f, 0.9f, 0.2f, 1f);
        carousel.setTranslationY(dp(40));
        stageColumn.setAlpha(0f);
        panel.setAlpha(0f);
        if (landscape) panel.setTranslationX(dp(60));
        else panel.setTranslationY(dp(80));
        scrim.post(() -> {
            ground.animate().alpha(1f).setDuration(220).withLayer().withEndAction(() -> {
                // Opaque now: the lyrics under it need not be drawn at all.
                if (overlay == scrim) reportSheet(true, true);
            }).start();
            stageColumn.animate().alpha(1f).setDuration(200).start();
            carousel.animate().translationY(0f).setDuration(420).setInterpolator(ease).start();
            panel.animate().translationX(0f).translationY(0f).alpha(1f).setStartDelay(60)
                    .setDuration(460).setInterpolator(ease).start();
        });
        return scrim;
    }

    /** Close on the left; the current design's name and its place among them in the middle. */
    private View topBar() {
        FrameLayout bar = new FrameLayout(activity);
        ImageView close = iconButton(LineIcon.Kind.CLOSE, dp(40));
        close.setContentDescription(s("close", "Close"));
        close.setOnClickListener(v -> dismiss());
        FrameLayout.LayoutParams closeLp = new FrameLayout.LayoutParams(dp(40), dp(40),
                Gravity.START | Gravity.CENTER_VERTICAL);
        closeLp.leftMargin = dp(16);
        bar.addView(close, closeLp);

        LinearLayout title = new LinearLayout(activity);
        title.setOrientation(LinearLayout.VERTICAL);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        designLabel = new TextView(activity);
        designLabel.setTextColor(Color.WHITE);
        designLabel.setTextSize(16);
        designLabel.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        designLabel.setGravity(Gravity.CENTER);
        title.addView(designLabel);
        designCounter = new TextView(activity);
        designCounter.setTextColor(Color.argb(140, 255, 255, 255));
        designCounter.setTextSize(11);
        designCounter.setGravity(Gravity.CENTER);
        title.addView(designCounter);
        bar.addView(title, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        if (document != null) {
            // Pick exactly which lines go on the card - any of them, not only neighbours.
            TextView pick = new TextView(activity);
            pick.setText(s("pick_lines", "Lyrics"));
            pick.setTextSize(13);
            pick.setTextColor(Color.WHITE);
            pick.setTypeface(Typeface.DEFAULT_BOLD);
            pick.setGravity(Gravity.CENTER_VERTICAL);
            pick.setPadding(dp(12), 0, dp(14), 0);
            pick.setCompoundDrawablePadding(dp(6));
            LineIcon icon = new LineIcon(LineIcon.Kind.LYRICS, Color.WHITE);
            icon.setBounds(0, 0, dp(18), dp(18));
            pick.setCompoundDrawablesRelative(icon, null, null, null);
            pick.setBackground(glass(dp(20), 34, 26));
            pick.setOnClickListener(v -> openPicker());
            FrameLayout.LayoutParams pickLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(40), Gravity.END | Gravity.CENTER_VERTICAL);
            pickLp.rightMargin = dp(16);
            bar.addView(pick, pickLp);
        }
        return bar;
    }

    /** A round glass button holding a line icon. */
    private ImageView iconButton(LineIcon.Kind kind, int size) {
        ImageView button = new ImageView(activity);
        button.setImageDrawable(new LineIcon(kind, Color.WHITE));
        int pad = Math.round(size * 0.27f);
        button.setPadding(pad, pad, pad, pad);
        button.setBackground(glass(size / 2f, 34, 26));
        return button;
    }

    /** Frosted fill with a hairline edge, the look of the whole sheet's controls. */
    private android.graphics.drawable.GradientDrawable glass(float radius, int fillAlpha, int edgeAlpha) {
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(radius);
        bg.setColor(Color.argb(fillAlpha, 255, 255, 255));
        if (edgeAlpha > 0) bg.setStroke(Math.max(1, dp(1) / 2 + 1), Color.argb(edgeAlpha, 255, 255, 255));
        return bg;
    }

    /**
     * The floating controls: tabs for Design (live thumbnails), Background (backdrop, Spotify
     * Code, translation) and Text (alignment and position), above the share targets.
     */
    private View buildPanel(boolean landscape) {
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(12), 0, dp(14));

        String[] tabs = {s("design_label", "Design"), s("backdrop_label", "Background"), s("text_label", "Text")};
        FrameLayout pages = new FrameLayout(activity);
        View[] pageViews = {designPage(), backgroundPage(), textPage()};
        for (int i = 0; i < pageViews.length; i++) {
            pageViews[i].setVisibility(i == 0 ? View.VISIBLE : View.GONE);
            pages.addView(pageViews[i], new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
        }
        LinearLayout tabRow = segmented(tabs, tabChips, 0, index -> {
            for (int i = 0; i < pageViews.length; i++) {
                View pageView = pageViews[i];
                if (i == index) {
                    pageView.setAlpha(0f);
                    pageView.setVisibility(View.VISIBLE);
                    pageView.animate().alpha(1f).setDuration(180).start();
                } else {
                    pageView.setVisibility(View.GONE);
                }
            }
        });
        for (TextView tab : tabChips) {
            tab.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        LinearLayout.LayoutParams tabLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tabLp.setMargins(dp(12), 0, dp(12), 0);
        content.addView(tabRow, tabLp);
        // One height for every tab, so switching never moves the card.
        LinearLayout.LayoutParams pagesLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(108));
        pagesLp.topMargin = dp(6);
        content.addView(pages, pagesLp);

        View divider = new View(activity);
        divider.setBackgroundColor(Color.argb(26, 255, 255, 255));
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2 + 1));
        divLp.setMargins(dp(16), dp(4), dp(16), dp(12));
        content.addView(divider, divLp);

        // Share targets, as in Spotify's own sheet: the story apps that are installed (with their
        // own icons), then copy link, save and the system sheet.
        HorizontalScrollView targetsScroll = new HorizontalScrollView(activity);
        targetsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout targets = new LinearLayout(activity);
        targets.setPadding(dp(8), 0, dp(8), 0);
        if (canShareTo(StoryTarget.INSTAGRAM)) {
            targets.addView(shareTarget(appIcon(StoryTarget.INSTAGRAM.pkg), null,
                    s("instagram_story", "Instagram Stories"), v -> shareToStory(StoryTarget.INSTAGRAM)));
        }
        if (canShareTo(StoryTarget.FACEBOOK)) {
            targets.addView(shareTarget(appIcon(StoryTarget.FACEBOOK.pkg), null,
                    s("facebook_story", "Facebook Stories"), v -> shareToStory(StoryTarget.FACEBOOK)));
        }
        if (!isBlank(webLink(track))) {
            targets.addView(shareTarget(null, LineIcon.Kind.LINK, s("copy_link", "Copy link"), v -> copyLink()));
        }
        targets.addView(shareTarget(null, LineIcon.Kind.DOWNLOAD, s("save", "Save"), v -> saveOnly()));
        // Installed chat apps, found off the main thread, join after the story targets.
        int directAt = targets.getChildCount();
        targets.addView(shareTarget(null, LineIcon.Kind.MORE, s("more", "More"), v -> shareCard(null)));
        targetsScroll.addView(targets);
        THUMBS.execute(() -> {
            List<DirectTarget> direct = findDirectTargets();
            if (direct.isEmpty()) return;
            main.post(() -> {
                if (!targets.isAttachedToWindow()) return;
                for (int i = 0; i < direct.size(); i++) {
                    DirectTarget target = direct.get(i);
                    View item = shareTarget(target.icon, null, target.label, v -> shareCard(target.component));
                    item.setAlpha(0f);
                    targets.addView(item, directAt + i);
                    item.animate().alpha(1f).setStartDelay(40L * i).setDuration(220).start();
                }
            });
        });
        content.addView(targetsScroll);

        android.graphics.drawable.GradientDrawable bg = glass(dp(28), 0, 30);
        bg.setColor(Color.argb(150, 20, 20, 24));
        // Taps on the panel stay on it instead of closing everything.
        if (landscape) {
            android.widget.ScrollView scroll = new android.widget.ScrollView(activity);
            scroll.setFillViewport(true);
            scroll.setVerticalScrollBarEnabled(false);
            scroll.setBackground(bg);
            scroll.setClickable(true);
            scroll.setClipToOutline(true);
            content.setGravity(Gravity.CENTER_VERTICAL);
            scroll.addView(content, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return scroll;
        }
        content.setBackground(bg);
        content.setClickable(true);
        return content;
    }

    private View designPage() {
        designStrip = new HorizontalScrollView(activity);
        designStrip.setHorizontalScrollBarEnabled(false);
        LinearLayout strip = new LinearLayout(activity);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.setPadding(dp(10), 0, dp(10), 0);
        designThumbs.clear();
        designFrames.clear();
        designNames.clear();
        for (Design d : Design.values()) {
            LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(
                    dp(THUMB_W_DP + 16), ViewGroup.LayoutParams.WRAP_CONTENT);
            strip.addView(designItem(d), itemLp);
        }
        designStrip.addView(strip);
        return designStrip;
    }

    private View backgroundPage() {
        LinearLayout page = new LinearLayout(activity);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        String[] backdropLabels = {s("backdrop_blur", "Blurred artwork"), s("backdrop_lyrics", "Lyrics background"),
                s("backdrop_artist", "Artist photo")};
        LinearLayout backdrops = segmented(backdropLabels, backdropChips, backdrop.ordinal(), index -> {
            Backdrop next = Backdrop.values()[index];
            if (next == backdrop) return;
            backdrop = next;
            prefs.edit().putString(PREF_BACKDROP, backdrop.name()).apply();
            render(Transition.FADE);
            renderThumbs();
            if (backdrop == Backdrop.ARTIST && artistImage == null) fetchArtistImage();
        });
        page.addView(centredScroll(backdrops));

        LinearLayout toggles = new LinearLayout(activity);
        toggles.setGravity(Gravity.CENTER);
        codeChip = toggleChip(LineIcon.Kind.CODE, s("spotify_code", "Spotify Code"), spotifyCode);
        codeChip.setOnClickListener(v -> {
            spotifyCode = !spotifyCode;
            prefs.edit().putBoolean(PREF_CODE, spotifyCode).apply();
            styleToggle(codeChip, spotifyCode);
            if (spotifyCode) {
                // Switched on: the code starts building at once, whether or not it has loaded.
                codePlayed = false;
                codeFailed = false;
            }
            trimSelectionToFit();
            render(Transition.FADE);
            if (spotifyCode) fetchSpotifyCode();
        });
        toggles.addView(codeChip);
        if (hasAnyTranslation()) {
            translationChip = toggleChip(LineIcon.Kind.GLOBE, s("translation", "Translation"), showTranslation);
            translationChip.setOnClickListener(v -> toggleTranslation());
            LinearLayout.LayoutParams trLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            trLp.leftMargin = dp(8);
            toggles.addView(translationChip, trLp);
        }
        LinearLayout.LayoutParams togglesLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        togglesLp.topMargin = dp(10);
        page.addView(toggles, togglesLp);
        return page;
    }

    /** Alignment (start / centre / end) and position (top / middle / bottom) of the lyric. */
    private View textPage() {
        LinearLayout page = new LinearLayout(activity);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER);
        alignGroup = iconSegmented(new LineIcon.Kind[]{LineIcon.Kind.ALIGN_START, LineIcon.Kind.ALIGN_CENTER,
                LineIcon.Kind.ALIGN_END}, alignButtons, index -> setTextStyle(
                new TextStyle(TextAlign.values()[index + 1], textStyle.pos)));
        row.addView(labelled(s("text_align", "Align"), alignGroup));
        View posGroup = iconSegmented(new LineIcon.Kind[]{LineIcon.Kind.POS_TOP, LineIcon.Kind.POS_MIDDLE,
                LineIcon.Kind.POS_BOTTOM}, posButtons, index -> setTextStyle(
                new TextStyle(textStyle.align, TextPos.values()[index + 1])));
        LinearLayout.LayoutParams posLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        posLp.leftMargin = dp(18);
        row.addView(labelled(s("text_position", "Position"), posGroup), posLp);
        page.addView(row);

        autoChip = new TextView(activity);
        autoChip.setText(s("text_auto", "Design default"));
        autoChip.setTextSize(12);
        autoChip.setGravity(Gravity.CENTER);
        autoChip.setPadding(dp(14), dp(6), dp(14), dp(6));
        autoChip.setOnClickListener(v -> setTextStyle(new TextStyle(TextAlign.AUTO, TextPos.AUTO)));
        LinearLayout.LayoutParams autoLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        autoLp.topMargin = dp(10);
        page.addView(autoChip, autoLp);
        return page;
    }

    // ---------------------------------------------------------------- line picker

    /** The line picker over the sheet, while open, and its row per lyric line. */
    private View picker;
    private TextView pickCount;
    private final Map<Integer, View> pickRows = new java.util.LinkedHashMap<>();

    boolean closePickerIfOpen() {
        if (picker == null) return false;
        closePicker();
        return true;
    }

    /**
     * Every lyric line of the song in a list rising from the bottom; tapping one puts it on the
     * card or takes it off - any lines, in song order on the card. A line the card has no room
     * for is refused, and the card updates live above the list.
     */
    private void openPicker() {
        if (!(overlay instanceof FrameLayout) || document == null || picker != null) return;
        FrameLayout host = (FrameLayout) overlay;
        FrameLayout layer = new FrameLayout(activity);
        layer.setElevation(dp(80));
        View dim = new View(activity);
        dim.setBackgroundColor(Color.argb(110, 0, 0, 0));
        dim.setOnClickListener(v -> closePicker());
        layer.addView(dim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        PullSheet sheet = new PullSheet(activity);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setClickable(true);
        sheet.onProgress = progress -> dim.setAlpha(1f - progress);
        sheet.onDismiss = velocity -> closePicker(velocity);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        float r = dp(28);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        bg.setColor(Color.argb(246, 22, 22, 26));
        sheet.setBackground(bg);
        int insetBottom = 0;
        android.view.WindowInsets insets = host.getRootWindowInsets();
        if (insets != null) insetBottom = insets.getSystemWindowInsetBottom();
        sheet.setPadding(0, dp(10), 0, insetBottom);

        View grip = new View(activity);
        grip.setBackground(glass(dp(3), 70, 0));
        LinearLayout.LayoutParams gripLp = new LinearLayout.LayoutParams(dp(36), dp(5));
        gripLp.gravity = Gravity.CENTER_HORIZONTAL;
        sheet.addView(grip, gripLp);

        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20), dp(10), dp(14), dp(8));
        LinearLayout titles = new LinearLayout(activity);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(activity);
        title.setText(s("pick_title", "Choose lyrics"));
        title.setTextSize(17);
        title.setTextColor(Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titles.addView(title);
        pickCount = new TextView(activity);
        pickCount.setTextSize(12);
        pickCount.setTextColor(Color.argb(150, 255, 255, 255));
        titles.addView(pickCount);
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView done = new TextView(activity);
        done.setText(s("done", "Done"));
        done.setTextSize(14);
        done.setTextColor(Color.BLACK);
        done.setTypeface(Typeface.DEFAULT_BOLD);
        done.setGravity(Gravity.CENTER);
        done.setPadding(dp(18), dp(8), dp(18), dp(8));
        done.setBackground(glass(dp(18), 255, 0));
        done.setOnClickListener(v -> closePicker());
        header.addView(done);
        sheet.addView(header);

        android.widget.ScrollView scroll = new android.widget.ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(10), dp(4), dp(10), dp(16));
        // A row that shakes or springs is never cut at the list's edge.
        list.setClipChildren(false);
        list.setClipToPadding(false);
        pickRows.clear();
        for (int i = 0; i < document.appliedLines.size(); i++) {
            if (isBlankLine(i)) continue;
            View row = pickRow(i);
            pickRows.put(i, row);
            list.addView(row);
        }
        scroll.addView(list);
        sheet.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        // The list hands a downward pull over to the sheet once it is scrolled to its top.
        sheet.scroller = scroll;

        int height = Math.round((host.getHeight() > 0 ? host.getHeight()
                : activity.getResources().getDisplayMetrics().heightPixels) * 0.6f);
        layer.addView(sheet, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height,
                Gravity.BOTTOM));
        host.addView(layer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        picker = layer;
        updatePicker();

        // Open on the lines already on the card.
        scroll.post(() -> {
            View first = picked.isEmpty() ? null : pickRows.get(picked.first());
            if (first != null) scroll.scrollTo(0, Math.max(0, first.getTop() - dp(56)));
        });
        dim.setAlpha(0f);
        dim.animate().alpha(1f).setDuration(200).start();
        sheet.setTranslationY(height);
        sheet.animate().translationY(0f).setDuration(360)
                .setInterpolator(new PathInterpolator(0.2f, 0.9f, 0.2f, 1f)).start();
    }

    private void closePicker() {
        closePicker(0f);
    }

    private void closePicker(float velocity) {
        View layer = picker;
        picker = null;
        pickRows.clear();
        pickCount = null;
        if (!(layer instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) layer;
        View dim = group.getChildAt(0);
        View sheet = group.getChildAt(1);
        dim.animate().alpha(0f).setDuration(200).start();
        Runnable remove = () -> {
            if (layer.getParent() instanceof ViewGroup) ((ViewGroup) layer.getParent()).removeView(layer);
        };
        if (sheet instanceof PullSheet) {
            ((PullSheet) sheet).slideAway(velocity, remove);
        } else {
            sheet.animate().translationY(sheet.getHeight()).setDuration(260).withEndAction(remove).start();
        }
    }

    private interface FloatListener {
        void on(float value);
    }

    /** iOS sheet curve, as the Layout Editor's sheet: quick departure, long soft landing. */
    private static final android.animation.TimeInterpolator SHEET_EASE = new PathInterpolator(0.32f, 0.72f, 0f, 1f);

    /**
     * A sheet that a downward drag moves with the finger - anywhere on it, and from a scrolling
     * list once the list has nothing left above (the same gesture carries on into the sheet) -
     * and that a flick or a long enough pull closes, otherwise springing back. The Layout
     * Editor's sheet behaviour, handled in dispatchTouchEvent so a list that has started
     * scrolling can still hand the gesture over.
     */
    private final class PullSheet extends LinearLayout {
        private static final int DISMISS_VELOCITY_DP = 900;
        private static final float DISMISS_FRACTION = 0.25f;
        private final int touchSlop;
        /** A list inside the sheet, or null. */
        View scroller;
        /** A child with its own vertical gestures (the card), never pulled from. */
        View excluded;
        FloatListener onProgress;
        FloatListener onDismiss;
        /** A tap nothing inside took (the empty backdrop): the sheet takes every touch to be
         *  able to follow a pull, so the tap that closed it from outside is handled here. */
        Runnable onEmptyTap;
        private boolean childTook;
        private android.view.VelocityTracker velocity;
        private float downRawX, downRawY, lastRawY, pull;
        private boolean dragging, fromOutsideList, blocked, childLocked, disallowRequested;

        PullSheet(Context context) {
            super(context);
            touchSlop = android.view.ViewConfiguration.get(context).getScaledTouchSlop();
        }

        @Override
        public void requestDisallowInterceptTouchEvent(boolean disallow) {
            if (disallow) disallowRequested = true;
            super.requestDisallowInterceptTouchEvent(disallow);
        }

        private boolean inside(View view, float rawX, float rawY) {
            if (view == null || !view.isShown()) return false;
            int[] at = new int[2];
            view.getLocationOnScreen(at);
            return rawX >= at[0] && rawX < at[0] + view.getWidth() * view.getScaleX()
                    && rawY >= at[1] && rawY < at[1] + view.getHeight() * view.getScaleY();
        }

        @Override
        public boolean dispatchTouchEvent(android.view.MotionEvent event) {
            int action = event.getActionMasked();
            float rawY = event.getRawY();
            trackVelocity(event, action == android.view.MotionEvent.ACTION_DOWN);
            if (action == android.view.MotionEvent.ACTION_DOWN) {
                dragging = false;
                pull = 0f;
                downRawX = event.getRawX();
                downRawY = lastRawY = rawY;
                blocked = inside(excluded, event.getRawX(), rawY);
                fromOutsideList = !inside(scroller, event.getRawX(), rawY);
                disallowRequested = false;
                childTook = super.dispatchTouchEvent(event);
                childLocked = disallowRequested;
                return true;
            }
            if (action == android.view.MotionEvent.ACTION_UP && !dragging && !childTook && onEmptyTap != null
                    && Math.hypot(event.getRawX() - downRawX, rawY - downRawY) < touchSlop) {
                onEmptyTap.run();
                return true;
            }
            float dy = rawY - lastRawY;
            lastRawY = rawY;
            if (dragging) {
                if (action == android.view.MotionEvent.ACTION_MOVE) {
                    setTranslationY(Math.max(0f, getTranslationY() + dy));
                    progress();
                } else if (action == android.view.MotionEvent.ACTION_UP
                        || action == android.view.MotionEvent.ACTION_CANCEL) {
                    dragging = false;
                    settle(action == android.view.MotionEvent.ACTION_UP ? releaseVelocity() : 0f);
                }
                return true;
            }
            if (action == android.view.MotionEvent.ACTION_MOVE && !blocked && !childLocked) {
                boolean listAtTop = scroller == null || !scroller.canScrollVertically(-1);
                if ((fromOutsideList || listAtTop) && dy > 0f) {
                    pull += dy;
                } else if (dy < 0f || !listAtTop) {
                    pull = 0f;
                }
                float sideways = Math.abs(event.getRawX() - downRawX);
                if (pull > touchSlop && sideways < Math.abs(rawY - downRawY)) {
                    dragging = true;
                    animate().cancel();
                    android.view.MotionEvent cancel = android.view.MotionEvent.obtain(event);
                    cancel.setAction(android.view.MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(cancel);
                    cancel.recycle();
                    return true;
                }
            }
            return super.dispatchTouchEvent(event);
        }

        private float travel() {
            return Math.max(dp(120), getHeight());
        }

        private void progress() {
            if (onProgress != null) onProgress.on(Math.min(1f, getTranslationY() / travel()));
        }

        private void settle(float velocityPxPerSec) {
            boolean dismiss = velocityPxPerSec > dp(DISMISS_VELOCITY_DP)
                    || (velocityPxPerSec > -dp(DISMISS_VELOCITY_DP) / 3f
                        && getTranslationY() > travel() * DISMISS_FRACTION);
            if (dismiss && onDismiss != null) {
                onDismiss.on(velocityPxPerSec);
            } else {
                moveTo(0f, velocityPxPerSec, null);
            }
        }

        /** Off the bottom edge, carrying on at the finger's speed, then {@code end}. */
        void slideAway(float velocityPxPerSec, Runnable end) {
            moveTo(travel(), velocityPxPerSec, end);
        }

        /** Duration from the remaining distance and the release speed, so a flick carries on. */
        private void moveTo(float target, float velocityPxPerSec, Runnable end) {
            animate().cancel();
            float distance = Math.abs(target - getTranslationY());
            long duration = 340L;
            float speed = Math.abs(velocityPxPerSec);
            if (speed > 1f) duration = Math.round(1000f * 2.3f * distance / speed);
            duration = Math.max(160L, Math.min(360L, duration));
            animate().translationY(target).setDuration(duration).setInterpolator(SHEET_EASE)
                    .setUpdateListener(a -> progress())
                    .withEndAction(end).start();
        }

        private void trackVelocity(android.view.MotionEvent event, boolean reset) {
            if (velocity == null) velocity = android.view.VelocityTracker.obtain();
            if (reset) velocity.clear();
            android.view.MotionEvent screen = android.view.MotionEvent.obtain(event);
            screen.setLocation(event.getRawX(), event.getRawY());
            velocity.addMovement(screen);
            screen.recycle();
        }

        private float releaseVelocity() {
            velocity.computeCurrentVelocity(1000);
            return velocity.getYVelocity();
        }
    }

    /** One lyric line in the picker: a tick circle, the line, and its translation under it. */
    private View pickRow(int index) {
        AppliedLine line = document.appliedLines.get(index);
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(14), dp(10));
        ImageView tick = new ImageView(activity);
        int pad = dp(3);
        tick.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams tickLp = new LinearLayout.LayoutParams(dp(22), dp(22));
        tickLp.rightMargin = dp(14);
        row.addView(tick, tickLp);
        LinearLayout texts = new LinearLayout(activity);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView text = new TextView(activity);
        text.setText(safe(line.text));
        text.setTextSize(16);
        texts.addView(text);
        if (!isBlank(line.translatedText)) {
            TextView sub = new TextView(activity);
            sub.setText(safe(line.translatedText));
            sub.setTextSize(12);
            sub.setTextColor(Color.argb(120, 255, 255, 255));
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            subLp.topMargin = dp(2);
            texts.addView(sub, subLp);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.setTag(new Object[]{tick, text});
        row.setOnClickListener(v -> togglePicked(index, row));
        pressable(row);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(2);
        row.setLayoutParams(lp);
        return row;
    }

    private void togglePicked(int index, View row) {
        java.util.TreeSet<Integer> next = new java.util.TreeSet<>(picked);
        if (next.contains(index)) {
            // The card always keeps one line.
            if (next.size() <= 1) {
                nudge(row);
                return;
            }
            next.remove(index);
        } else {
            next.add(index);
            if (!selectionFits(next)) {
                nudge(row);
                Toast.makeText(activity, s("full", "No room for more lines on this card"),
                        Toast.LENGTH_SHORT).show();
                return;
            }
        }
        setPicked(next);
        render(Transition.TEXT);
        renderThumbs();
    }

    /** "No": a short, decaying head-shake that stays within a few dp of the row's place. */
    private void nudge(View view) {
        android.animation.ObjectAnimator shake = android.animation.ObjectAnimator.ofFloat(view,
                View.TRANSLATION_X, 0f, dp(6), -dp(5), dp(3), -dp(2), 0f);
        shake.setDuration(380);
        shake.setInterpolator(null);
        shake.start();
    }

    /** Ticks and the count follow the selection, however it changed (picker or swipes). */
    private void updatePicker() {
        if (picker == null) return;
        if (pickCount != null) {
            String format = s("pick_count", "%1$d selected");
            String count;
            try {
                count = String.format(java.util.Locale.getDefault(), format, picked.size());
            } catch (Throwable error) {
                count = picked.size() + "";
            }
            pickCount.setText(count);
        }
        for (Map.Entry<Integer, View> entry : pickRows.entrySet()) {
            boolean on = picked.contains(entry.getKey());
            View row = entry.getValue();
            Object[] parts = (Object[]) row.getTag();
            ImageView tick = (ImageView) parts[0];
            TextView text = (TextView) parts[1];
            row.setBackground(on ? glass(dp(14), 26, 0) : null);
            android.graphics.drawable.GradientDrawable circle = new android.graphics.drawable.GradientDrawable();
            circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            if (on) {
                circle.setColor(Color.rgb(30, 215, 96));
                tick.setImageDrawable(new LineIcon(LineIcon.Kind.CHECK, Color.BLACK));
            } else {
                circle.setStroke(dp(2), Color.argb(110, 255, 255, 255));
                tick.setImageDrawable(null);
            }
            tick.setBackground(circle);
            text.setTextColor(on ? Color.WHITE : Color.argb(170, 255, 255, 255));
            text.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
    }

    private View labelled(String label, View control) {
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView text = new TextView(activity);
        text.setText(label);
        text.setTextSize(11);
        text.setTextColor(Color.argb(130, 255, 255, 255));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        column.addView(text, lp);
        // Its own width: a vertical LinearLayout's default is match_parent, which sized the group
        // to the label above it and cut the buttons off.
        column.addView(control, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return column;
    }

    private HorizontalScrollView centredScroll(View child) {
        HorizontalScrollView scroll = new HorizontalScrollView(activity);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        LinearLayout inner = new LinearLayout(activity);
        inner.setGravity(Gravity.CENTER_HORIZONTAL);
        inner.setPadding(dp(12), 0, dp(12), 0);
        inner.addView(child);
        scroll.addView(inner, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private void setTextStyle(TextStyle style) {
        textStyle = style;
        prefs.edit().putString(PREF_ALIGN, style.align.name()).putString(PREF_POS, style.pos.name()).apply();
        updateTextControls();
        // The card stays; its lines glide to the new alignment and position.
        render(Transition.ALIGN);
        renderThumbs();
    }

    /** The alignment and position the current design actually uses, highlighted. */
    private void updateTextControls() {
        if (alignButtons.isEmpty() || posButtons.isEmpty()) return;
        TextAlign align = textStyle.align;
        if (align == TextAlign.AUTO) {
            Layout.Alignment own = textBox(design, true).align;
            align = own == Layout.Alignment.ALIGN_CENTER ? TextAlign.CENTER
                    : own == Layout.Alignment.ALIGN_OPPOSITE ? TextAlign.END : TextAlign.START;
        }
        TextPos pos = textStyle.pos;
        if (pos == TextPos.AUTO) pos = design == Design.POSTER ? TextPos.BOTTOM : TextPos.MIDDLE;
        for (int i = 0; i < alignButtons.size(); i++) styleIconSegment(alignButtons.get(i), i == align.ordinal() - 1);
        for (int i = 0; i < posButtons.size(); i++) styleIconSegment(posButtons.get(i), i == pos.ordinal() - 1);
        if (autoChip != null) {
            boolean auto = textStyle.align == TextAlign.AUTO && textStyle.pos == TextPos.AUTO;
            autoChip.setBackground(auto ? glass(dp(16), 60, 0) : glass(dp(16), 0, 60));
            autoChip.setTextColor(auto ? Color.WHITE : Color.argb(190, 255, 255, 255));
        }
    }

    private LinearLayout iconSegmented(LineIcon.Kind[] kinds, List<ImageView> buttons, IndexListener listener) {
        LinearLayout group = new LinearLayout(activity);
        group.setPadding(dp(3), dp(3), dp(3), dp(3));
        group.setBackground(glass(dp(20), 30, 0));
        buttons.clear();
        for (int i = 0; i < kinds.length; i++) {
            ImageView button = new ImageView(activity);
            button.setImageDrawable(new LineIcon(kinds[i], Color.WHITE));
            button.setPadding(dp(10), dp(7), dp(10), dp(7));
            final int index = i;
            button.setOnClickListener(v -> listener.onPick(index));
            buttons.add(button);
            group.addView(button, new LinearLayout.LayoutParams(dp(44), dp(34)));
        }
        return group;
    }

    private void styleIconSegment(ImageView button, boolean selected) {
        button.setBackground(selected ? glass(dp(17), 255, 0) : null);
        if (button.getDrawable() instanceof LineIcon) {
            ((LineIcon) button.getDrawable()).setColor(selected ? Color.BLACK : Color.argb(210, 255, 255, 255));
        }
    }

    private View designItem(Design d) {
        LinearLayout item = new LinearLayout(activity);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout frame = new FrameLayout(activity);
        frame.setPadding(dp(3), dp(3), dp(3), dp(3));
        ImageView thumb = new ImageView(activity);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setBackground(glass(dp(9), 30, 0));
        thumb.setClipToOutline(true);
        frame.addView(thumb, new FrameLayout.LayoutParams(dp(THUMB_W_DP), dp(THUMB_H_DP)));
        // Every item one width, the whole thumbnail in it: a short name ("Glass") used to size
        // the item and crop its thumbnail to a sliver.
        item.addView(frame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView name = new TextView(activity);
        name.setText(designName(d));
        name.setTextSize(11);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameLp.topMargin = dp(5);
        item.addView(name, nameLp);
        item.setOnClickListener(v -> selectDesign(d));
        designThumbs.add(thumb);
        designFrames.add(frame);
        designNames.add(name);
        return item;
    }

    private String designName(Design d) {
        String key = d.name().toLowerCase(java.util.Locale.ROOT);
        return s("design_" + key, d.name().charAt(0) + key.substring(1));
    }

    /** Title, ring on the current thumbnail, and the strip scrolled to show it. */
    private void updateDesignChrome(boolean animate) {
        if (designLabel != null) designLabel.setText(designName(design));
        if (designCounter != null) {
            designCounter.setText((design.ordinal() + 1) + " / " + Design.values().length);
        }
        for (int i = 0; i < designFrames.size(); i++) {
            boolean selected = i == design.ordinal();
            View frame = designFrames.get(i);
            if (selected) {
                android.graphics.drawable.GradientDrawable ring = new android.graphics.drawable.GradientDrawable();
                ring.setCornerRadius(dp(12));
                ring.setStroke(dp(2), Color.WHITE);
                frame.setBackground(ring);
            } else {
                frame.setBackground(null);
            }
            frame.animate().scaleX(selected ? 1f : 0.92f).scaleY(selected ? 1f : 0.92f)
                    .alpha(selected ? 1f : 0.72f).setDuration(animate ? 200 : 0).start();
            TextView name = designNames.get(i);
            name.setTextColor(selected ? Color.WHITE : Color.argb(130, 255, 255, 255));
            name.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
        updateTextControls();
        HorizontalScrollView strip = designStrip;
        int index = design.ordinal();
        if (strip == null || index >= designFrames.size()) return;
        strip.post(() -> {
            View item = (View) designFrames.get(Math.min(index, designFrames.size() - 1)).getParent();
            if (item == null) return;
            int x = item.getLeft() + item.getWidth() / 2 - strip.getWidth() / 2;
            if (animate) strip.smoothScrollTo(Math.max(0, x), 0);
            else strip.scrollTo(Math.max(0, x), 0);
        });
    }

    /**
     * Every design rendered small with the current content, one at a time on the render thread
     * (after the card itself), so the strip shows what each would actually look like.
     */
    private void renderThumbs() {
        if (designThumbs.isEmpty() || track == null) return;
        int token = ++thumbGeneration;
        List<String> quotes = collectQuotes(selection());
        List<String> translations = collectTranslations(selection());
        TextStyle style = textStyle;
        Backdrop b = backdrop;
        Bitmap art = artwork;
        Bitmap artist = artistImage;
        Bitmap lyricsBg = lyricsBackground;
        SpotifyTrack t = track;
        float scale = thumbScale();
        // The current design first, then outward from it - what is on screen fills in first.
        List<Design> order = new ArrayList<>();
        int n = Design.values().length;
        order.add(design);
        for (int step = 1; order.size() < n; step++) {
            Design after = designAt(design.ordinal() + step);
            if (!order.contains(after)) order.add(after);
            Design before = designAt(design.ordinal() - step);
            if (!order.contains(before)) order.add(before);
        }
        // Their own thread: the card and its neighbours never queue behind them.
        THUMBS.execute(() -> {
            for (Design d : order) {
                if (token != thumbGeneration) return;
                Bitmap small;
                try {
                    small = renderCardScaled(d, style, b, art, artist, lyricsBg, quotes, translations,
                            safe(t.title), safe(t.artist), scale);
                } catch (Throwable error) {
                    XpLog.log(TAG + " thumbnail " + d + " failed: " + error);
                    continue;
                }
                int index = d.ordinal();
                main.post(() -> {
                    if (token != thumbGeneration || index >= designThumbs.size()) return;
                    designThumbs.get(index).setImageBitmap(small);
                });
            }
        });
    }

    private float thumbScale() {
        return Math.min(1f, dp(THUMB_H_DP) * 2f / H);
    }

    private static Bitmap shrink(Bitmap full, float scale) {
        return Bitmap.createScaledBitmap(full, Math.max(1, Math.round(W * scale)),
                Math.max(1, Math.round(H * scale)), true);
    }

    /** A card rendered anyway (the current one, a neighbour) doubles as its thumbnail. */
    private void setThumb(Design d, Bitmap small) {
        if (small != null && d.ordinal() < designThumbs.size()) designThumbs.get(d.ordinal()).setImageBitmap(small);
    }

    private boolean hasAnyTranslation() {
        if (document == null) return false;
        for (AppliedLine line : document.appliedLines) {
            if (!line.dotLine && !isBlank(line.translatedText)) return true;
        }
        return false;
    }

    private void toggleTranslation() {
        if (document == null) return;
        showTranslation = !showTranslation;
        if (translationChip != null) styleToggle(translationChip, showTranslation);
        render(Transition.TEXT);
    }

    private interface IndexListener {
        void onPick(int index);
    }

    /** One pill holding the choices; the chosen one is a white segment inside it. */
    private LinearLayout segmented(String[] labels, List<TextView> segments, int selected,
                                   IndexListener listener) {
        LinearLayout group = new LinearLayout(activity);
        group.setPadding(dp(3), dp(3), dp(3), dp(3));
        group.setBackground(glass(dp(20), 30, 0));
        segments.clear();
        for (int i = 0; i < labels.length; i++) {
            TextView segment = new TextView(activity);
            segment.setText(labels[i]);
            segment.setTextSize(13);
            segment.setSingleLine(true);
            segment.setGravity(Gravity.CENTER);
            segment.setPadding(dp(14), dp(7), dp(14), dp(7));
            styleSegment(segment, i == selected);
            final int index = i;
            segment.setOnClickListener(v -> {
                for (int j = 0; j < segments.size(); j++) styleSegment(segments.get(j), j == index);
                listener.onPick(index);
            });
            segments.add(segment);
            group.addView(segment);
        }
        return group;
    }

    private void styleSegment(TextView segment, boolean selected) {
        segment.setBackground(selected ? glass(dp(17), 255, 0) : null);
        segment.setTextColor(selected ? Color.BLACK : Color.argb(200, 255, 255, 255));
        segment.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    }

    private TextView toggleChip(LineIcon.Kind icon, String label, boolean on) {
        TextView chip = new TextView(activity);
        chip.setText(label);
        chip.setTag(icon);
        chip.setTextSize(13);
        chip.setSingleLine(true);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(12), dp(8), dp(14), dp(8));
        chip.setCompoundDrawablePadding(dp(6));
        styleToggle(chip, on);
        return chip;
    }

    /** On: Spotify green with a check; off: glass with the feature's own icon. */
    private void styleToggle(TextView chip, boolean on) {
        android.graphics.drawable.GradientDrawable bg = glass(dp(20), on ? 255 : 30, 0);
        if (on) bg.setColor(Color.rgb(30, 215, 96));
        chip.setBackground(bg);
        int color = on ? Color.BLACK : Color.argb(220, 255, 255, 255);
        LineIcon icon = new LineIcon(on ? LineIcon.Kind.CHECK : (LineIcon.Kind) chip.getTag(), color);
        icon.setBounds(0, 0, dp(16), dp(16));
        chip.setCompoundDrawablesRelative(icon, null, null, null);
        chip.setTextColor(color);
        chip.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    }

    private android.graphics.drawable.Drawable appIcon(String pkg) {
        try {
            return activity.getPackageManager().getApplicationIcon(pkg);
        } catch (Throwable error) {
            return null;
        }
    }

    /** A round button (an app's own icon, or a line icon on glass) over its label. */
    private View shareTarget(android.graphics.drawable.Drawable appIcon, LineIcon.Kind kind, String label,
                             View.OnClickListener onClick) {
        LinearLayout item = new LinearLayout(activity);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        item.setPadding(dp(4), dp(2), dp(4), dp(2));
        ImageView button;
        if (appIcon != null) {
            button = new ImageView(activity);
            button.setImageDrawable(appIcon);
            button.setScaleType(ImageView.ScaleType.FIT_CENTER);
            android.graphics.drawable.GradientDrawable round = new android.graphics.drawable.GradientDrawable();
            round.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            round.setColor(Color.TRANSPARENT);
            button.setBackground(round);
            button.setClipToOutline(true);
        } else {
            button = iconButton(kind, dp(52));
        }
        item.addView(button, new LinearLayout.LayoutParams(dp(52), dp(52)));
        TextView name = new TextView(activity);
        name.setText(label);
        name.setTextSize(11);
        name.setTextColor(Color.argb(200, 255, 255, 255));
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(2);
        name.setWidth(dp(70));
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameLp.topMargin = dp(6);
        item.addView(name, nameLp);
        item.setOnClickListener(onClick);
        // A press dips the button a little.
        item.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            if (action == android.view.MotionEvent.ACTION_DOWN) {
                button.animate().scaleX(0.9f).scaleY(0.9f).setDuration(90).start();
            } else if (action == android.view.MotionEvent.ACTION_UP
                    || action == android.view.MotionEvent.ACTION_CANCEL) {
                button.animate().scaleX(1f).scaleY(1f).setDuration(160).start();
            }
            return false;
        });
        return item;
    }

    /**
     * Line icons drawn on a 24-unit grid (2-unit round strokes), in place of text glyphs whose
     * look depended on the font.
     */
    static final class LineIcon extends android.graphics.drawable.Drawable {
        enum Kind { CLOSE, LINK, DOWNLOAD, MORE, CHECK, ALIGN_START, ALIGN_CENTER, ALIGN_END,
            POS_TOP, POS_MIDDLE, POS_BOTTOM, CODE, GLOBE, LYRICS, ARROW_UP }

        private final Kind kind;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        LineIcon(Kind kind, int color) {
            this.kind = kind;
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(2f);
        }

        void setColor(int color) {
            paint.setColor(color);
            invalidateSelf();
        }

        @Override
        public void draw(Canvas c) {
            android.graphics.Rect b = getBounds();
            float s = Math.min(b.width(), b.height()) / 24f;
            c.save();
            c.translate(b.left + (b.width() - 24 * s) / 2f, b.top + (b.height() - 24 * s) / 2f);
            c.scale(s, s);
            Paint p = paint;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2f);
            switch (kind) {
                case CLOSE:
                    c.drawLine(6, 6, 18, 18, p);
                    c.drawLine(18, 6, 6, 18, p);
                    break;
                case LINK: {
                    Path path = new Path();
                    path.moveTo(10, 7);
                    path.lineTo(7.5f, 7);
                    path.arcTo(new RectF(2.5f, 7, 12.5f, 17), 270, -180);
                    path.lineTo(10, 17);
                    path.moveTo(14, 7);
                    path.lineTo(16.5f, 7);
                    path.arcTo(new RectF(11.5f, 7, 21.5f, 17), 270, 180);
                    path.lineTo(14, 17);
                    c.drawPath(path, p);
                    c.drawLine(8.5f, 12, 15.5f, 12, p);
                    break;
                }
                case DOWNLOAD:
                    c.drawLine(12, 4, 12, 15, p);
                    c.drawLine(7, 10, 12, 15, p);
                    c.drawLine(17, 10, 12, 15, p);
                    c.drawLine(5, 20, 19, 20, p);
                    break;
                case MORE:
                    p.setStyle(Paint.Style.FILL);
                    c.drawCircle(5.5f, 12, 1.9f, p);
                    c.drawCircle(12, 12, 1.9f, p);
                    c.drawCircle(18.5f, 12, 1.9f, p);
                    break;
                case CHECK: {
                    Path path = new Path();
                    path.moveTo(5, 12.5f);
                    path.lineTo(10, 17.5f);
                    path.lineTo(19, 7);
                    c.drawPath(path, p);
                    break;
                }
                case ALIGN_START:
                case ALIGN_CENTER:
                case ALIGN_END: {
                    float[] ys = {6, 10, 14, 18};
                    for (int i = 0; i < ys.length; i++) {
                        boolean full = i % 2 == 0;
                        float len = full ? 16 : 10;
                        float x0 = kind == Kind.ALIGN_START ? 4 : kind == Kind.ALIGN_END ? 20 - len : 12 - len / 2f;
                        c.drawLine(x0, ys[i], x0 + len, ys[i], p);
                    }
                    break;
                }
                case POS_TOP:
                case POS_MIDDLE:
                case POS_BOTTOM: {
                    p.setStrokeWidth(1.6f);
                    c.drawRoundRect(new RectF(3.5f, 3.5f, 20.5f, 20.5f), 3.5f, 3.5f, p);
                    p.setStrokeWidth(2f);
                    float y = kind == Kind.POS_TOP ? 8 : kind == Kind.POS_MIDDLE ? 10.5f : 13;
                    c.drawLine(8, y, 16, y, p);
                    c.drawLine(8, y + 3, 14, y + 3, p);
                    break;
                }
                case CODE: {
                    p.setStyle(Paint.Style.FILL);
                    c.drawCircle(5, 12, 2.6f, p);
                    p.setStyle(Paint.Style.STROKE);
                    float[] heights = {5, 10, 6, 12, 7, 4};
                    for (int i = 0; i < heights.length; i++) {
                        float x = 10 + i * 2.2f;
                        c.drawLine(x, 12 - heights[i] / 2f, x, 12 + heights[i] / 2f, p);
                    }
                    break;
                }
                case ARROW_UP:
                    c.drawLine(12, 19, 12, 5.5f, p);
                    c.drawLine(6.5f, 11, 12, 5.5f, p);
                    c.drawLine(17.5f, 11, 12, 5.5f, p);
                    break;
                case LYRICS: {
                    // A list with the first rows ticked: pick lines.
                    float[] ys = {6.5f, 12, 17.5f};
                    for (int i = 0; i < ys.length; i++) {
                        c.drawLine(10, ys[i], i == 2 ? 16 : 20, ys[i], p);
                    }
                    p.setStrokeWidth(1.8f);
                    c.drawLine(3.5f, 6.5f, 5, 8, p);
                    c.drawLine(5, 8, 7.5f, 5, p);
                    c.drawLine(3.5f, 12, 5, 13.5f, p);
                    c.drawLine(5, 13.5f, 7.5f, 10.5f, p);
                    break;
                }
                case GLOBE:
                    p.setStrokeWidth(1.7f);
                    c.drawCircle(12, 12, 9, p);
                    c.drawOval(new RectF(8, 3, 16, 21), p);
                    c.drawLine(3, 12, 21, 12, p);
                    break;
                default:
                    break;
            }
            c.restore();
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter filter) {
            paint.setColorFilter(filter);
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    private void installPreviewGestures(View view) {
        android.view.GestureDetector detector = new android.view.GestureDetector(activity,
                new android.view.GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(android.view.MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(android.view.MotionEvent e) {
                toggleTranslation();
                return true;
            }

            @Override
            public boolean onFling(android.view.MotionEvent e1, android.view.MotionEvent e2,
                                   float velocityX, float velocityY) {
                if (e1 == null || e2 == null) return false;
                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();
                if (Math.abs(dx) > Math.abs(dy) * 1.2f) {
                    // Sideways: previous / next design (the carousel wraps around).
                    stepDesign(dx < 0 ? 1 : -1);
                    return true;
                }
                if (document == null || picked.isEmpty()) return false;
                // A press-and-drag already stepped through lines as it went (see below).
                if (dragStepped) return true;
                // A flick, however long, brings in exactly one line: up pulls the next lyric
                // up into the card, down brings back the one before.
                flingHandled = true;
                if (!expandSelection(dy < 0)) bounceText(dy < 0);
                return true;
            }
        });
        int touchSlop = android.view.ViewConfiguration.get(activity).getScaledTouchSlop();
        view.setOnTouchListener((v, event) -> {
            detector.onTouchEvent(event);
            handleDrag(event, touchSlop);
            if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN && teasePill != null) {
                endTease(teasePill, false);
            }
            pressCard(event);
            return true;
        });
    }

    // Press-and-drag: holding and pulling up (or down) keeps bringing lines in, one per
    // DRAG_STEP_DP of travel, with the text following the finger in between.
    private static final int DRAG_STEP_DP = 64;
    private float dragDownX;
    private float dragDownY;
    private float dragAnchorY;
    private boolean dragging;
    private boolean dragStepped;
    private boolean dragHitEdge;
    private boolean flingHandled;
    private long dragDownAt;
    private long dragStartAt;
    /** The finger rested before it moved: a hold-and-drag, which steps through lines. */
    private boolean dragHeld;
    /** Rest this long before moving and a drag counts as held. */
    private static final long HOLD_MS = 180L;
    /** A drag still going after this long is deliberate, not a flick, and steps too. */
    private static final long DELIBERATE_MS = 320L;

    private void handleDrag(android.view.MotionEvent event, int touchSlop) {
        if (document == null || picked.isEmpty()) return;
        switch (event.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                dragDownX = event.getX();
                dragDownY = event.getY();
                dragAnchorY = event.getY();
                dragging = false;
                dragStepped = false;
                dragHitEdge = false;
                flingHandled = false;
                dragDownAt = event.getEventTime();
                break;
            case android.view.MotionEvent.ACTION_MOVE: {
                float totalX = event.getX() - dragDownX;
                float totalY = event.getY() - dragDownY;
                if (!dragging) {
                    if (Math.abs(totalY) > touchSlop && Math.abs(totalY) > Math.abs(totalX) * 1.2f) {
                        dragging = true;
                        dragAnchorY = event.getY();
                        dragStartAt = event.getEventTime();
                        dragHeld = dragStartAt - dragDownAt >= HOLD_MS;
                    } else {
                        break;
                    }
                }
                float dy = event.getY() - dragAnchorY;
                // Only a held or deliberate drag steps through lines as it goes; a quick swipe,
                // however far, just leans the text and brings in one line when it lets go.
                boolean steps = dragHeld || event.getEventTime() - dragStartAt >= DELIBERATE_MS;
                if (steps && Math.abs(dy) >= dp(DRAG_STEP_DP) && !dragHitEdge) {
                    boolean up = dy < 0;
                    if (expandSelection(up)) {
                        dragStepped = true;
                        dragAnchorY = event.getY();
                        dy = 0f;
                    } else {
                        dragHitEdge = true;
                    }
                }
                // The text leans after the finger, stiffening as it goes (harder at the edge).
                followDrag(rubber(dy, dragHitEdge ? dp(26) : dp(40)));
                break;
            }
            case android.view.MotionEvent.ACTION_UP:
            case android.view.MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    float travelled = event.getY() - dragDownY;
                    if (dragHitEdge) {
                        bounceText(event.getY() - dragAnchorY < 0);
                    } else if (event.getActionMasked() == android.view.MotionEvent.ACTION_UP
                            && !dragStepped && !flingHandled && Math.abs(travelled) >= dp(40)) {
                        // A swipe released too slowly to count as a flick: still one line.
                        if (!expandSelection(travelled < 0)) bounceText(travelled < 0);
                    } else {
                        settleText();
                    }
                }
                dragging = false;
                break;
            default:
                break;
        }
    }

    /**
     * The card gives under the finger, as in Apple Music: it sinks a little when pressed and
     * springs back on release - or as soon as a drag or swipe takes over.
     */
    private void pressCard(android.view.MotionEvent event) {
        View card = cardHost;
        if (card == null) return;
        int action = event.getActionMasked();
        if (action == android.view.MotionEvent.ACTION_DOWN) {
            cardPressed = true;
            card.setPivotX(card.getWidth() / 2f);
            card.setPivotY(card.getHeight() / 2f);
            card.animate().scaleX(0.96f).scaleY(0.96f).setDuration(160)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        } else if (cardPressed && (action == android.view.MotionEvent.ACTION_UP
                || action == android.view.MotionEvent.ACTION_CANCEL
                || (action == android.view.MotionEvent.ACTION_MOVE && dragging))) {
            cardPressed = false;
            springBack(card);
        }
    }

    private boolean cardPressed;

    private static void springBack(View view) {
        view.animate().scaleX(1f).scaleY(1f).setDuration(460)
                .setInterpolator(new android.view.animation.OvershootInterpolator(2.2f)).start();
    }

    /** List rows (the line picker): the same give on press. */
    private static void pressable(View view) {
        view.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            if (action == android.view.MotionEvent.ACTION_DOWN) {
                v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(140)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
            } else if (action == android.view.MotionEvent.ACTION_UP
                    || action == android.view.MotionEvent.ACTION_CANCEL) {
                springBack(v);
            }
            return false;
        });
    }

    private static float rubber(float distance, float limit) {
        float sign = Math.signum(distance);
        float x = Math.abs(distance);
        return sign * limit * (1f - 1f / (x / limit * 0.55f + 1f));
    }

    private void followDrag(float offset) {
        if (currentTextLayer == null) return;
        currentTextLayer.animate().cancel();
        currentTextLayer.setTranslationY(offset);
    }

    private void settleText() {
        if (currentTextLayer == null) return;
        currentTextLayer.animate().translationY(0f).setDuration(320)
                .setInterpolator(new PathInterpolator(0.2f, 0.9f, 0.2f, 1f)).start();
    }

    /**
     * Nothing more that way (the song's first or last line): the text reaches for a next line
     * that is not there and springs back, instead of the gesture silently doing nothing.
     */
    private void bounceText(boolean up) {
        if (currentTextLayer == null) return;
        FrameLayout layer = currentTextLayer;
        float reach = (up ? -1f : 1f) * dp(22);
        float from = layer.getTranslationY();
        layer.animate().cancel();
        layer.animate().translationY(Math.abs(from) > Math.abs(reach) ? from : reach)
                .setDuration(140).setInterpolator(new PathInterpolator(0.3f, 0f, 0.4f, 1f))
                .withEndAction(() -> layer.animate().translationY(0f).setDuration(520)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(2.2f)).start())
                .start();
    }

    private ImageView peekView(float offsetX) {
        ImageView view = new ImageView(activity);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        view.setTranslationX(offsetX);
        view.setScaleX(0.9f);
        view.setScaleY(0.9f);
        view.setAlpha(0.45f);
        return view;
    }

    private static Design designAt(int ordinal) {
        int n = Design.values().length;
        return Design.values()[((ordinal % n) + n) % n];
    }

    /** Moves the carousel one design left (-1) or right (+1). */
    private void stepDesign(int direction) {
        applyDesign(designAt(design.ordinal() + direction), direction);
    }

    /** Picked from the strip: slides the way the strip reads. */
    private void selectDesign(Design next) {
        if (next == design) return;
        applyDesign(next, next.ordinal() > design.ordinal() ? 1 : -1);
    }

    private void applyDesign(Design next, int direction) {
        design = next;
        prefs.edit().putString(PREF_DESIGN, design.name()).apply();
        trimSelectionToFit();
        render(direction > 0 ? Transition.SLIDE_LEFT : Transition.SLIDE_RIGHT);
        updateDesignChrome(true);
    }

    /** Renders the neighbouring designs into the peek views (same content, small cost). */
    private void renderPeeks() {
        if (peekPrev == null || peekNext == null) return;
        int token = ++peekGeneration;
        List<String> quotes = collectQuotes(selection());
        List<String> translations = collectTranslations(selection());
        Design prev = designAt(design.ordinal() - 1);
        Design next = designAt(design.ordinal() + 1);
        TextStyle style = textStyle;
        Backdrop b = backdrop;
        Bitmap art = artwork;
        Bitmap artist = artistImage;
        Bitmap lyricsBg = lyricsBackground;
        SpotifyTrack t = track;
        float thumbScale = thumbScale();
        float peekScale = Math.max(thumbScale, Math.min(1f, cardWidthPx / (float) W));
        RENDER.execute(() -> {
            Bitmap left;
            Bitmap right;
            Bitmap leftThumb;
            Bitmap rightThumb;
            try {
                // At the size they are shown (dimmed, mostly off-screen), not the full 1080px.
                left = renderCardScaled(prev, style, b, art, artist, lyricsBg, quotes, translations,
                        safe(t.title), safe(t.artist), peekScale);
                right = renderCardScaled(next, style, b, art, artist, lyricsBg, quotes, translations,
                        safe(t.title), safe(t.artist), peekScale);
                leftThumb = shrink(left, thumbScale);
                rightThumb = shrink(right, thumbScale);
            } catch (Throwable error) {
                XpLog.log(TAG + " peek render failed: " + error);
                return;
            }
            main.post(() -> {
                if (token != peekGeneration || peekPrev == null) return;
                peekPrev.setImageBitmap(left);
                peekNext.setImageBitmap(right);
                setThumb(prev, leftThumb);
                setThumb(next, rightThumb);
            });
        });
    }

    /** Adds the next line below (swipe down) or the previous one above (swipe up), unless the
     *  card would no longer fit it - then it says so rather than overflowing. */
    /** @return false at the start/end of the song, when there is no line to bring in. */
    private boolean expandSelection(boolean down) {
        if (document == null || picked.isEmpty()) return false;
        int line = down ? nextLyric(picked.last(), document.appliedLines.size() - 1)
                : previousLyric(picked.first(), 0);
        if (line < 0) return false;
        java.util.TreeSet<Integer> next = new java.util.TreeSet<>(picked);
        next.add(line);
        // Full card: the new line still comes in, and the line at the far end is pushed out -
        // the selection scrolls through the song instead of stopping.
        boolean pushed = false;
        while (next.size() > 1 && !selectionFits(next)) {
            if (down) next.pollFirst();
            else next.pollLast();
            pushed = true;
        }
        setPicked(next);
        // Only a line actually leaving the card slides the text; a plain addition settles in place.
        render(!pushed ? Transition.TEXT : down ? Transition.SLIDE_UP : Transition.SLIDE_DOWN);
        return true;
    }

    private void setPicked(java.util.Collection<Integer> lines) {
        picked.clear();
        picked.addAll(lines);
        updatePicker();
    }

    /** The picked lines that are lyrics, in song order. */
    private List<Integer> selection() {
        return new ArrayList<>(picked);
    }

    /**
     * How much lyric a card holds: five lines of ordinary length. Measured as wrapped lines at a
     * reference size across the card's text width, so short lines can go a little past five
     * (up to seven when each still fits on one line) and long ones stop sooner.
     */
    static boolean withinLineBudget(List<String> quotes) {
        if (quotes == null || quotes.size() <= 1) return true;
        if (quotes.size() > 7) return false;
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextSize(54f);
        int width = W - 240;
        int visualLines = 0;
        for (String quote : quotes) {
            String text = quote == null ? "" : quote;
            visualLines += Math.max(1, StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                    .build().getLineCount());
        }
        if (quotes.size() <= 5) return visualLines <= 8;
        return visualLines <= quotes.size();
    }

    private boolean selectionFits(java.util.Collection<Integer> lines) {
        List<Integer> sorted = new ArrayList<>(new java.util.TreeSet<>(lines));
        String key = design.name() + '|' + showTranslation + '|' + sorted;
        Boolean known = fitCache.get(key);
        if (known != null) return known;
        List<String> quotes = collectQuotes(sorted);
        boolean fits = withinLineBudget(quotes) && fits(quotes, collectTranslations(sorted));
        fitCache.put(key, fits);
        return fits;
    }

    /** First lyric line after {@code from}, not past {@code limit}; -1 when {@code from} is the last. */
    private int nextLyric(int from, int limit) {
        for (int i = from + 1; i <= limit; i++) if (!isBlankLine(i)) return i;
        return -1;
    }

    /** Last lyric line before {@code from}, not before {@code limit}; -1 when none. */
    private int previousLyric(int from, int limit) {
        for (int i = from - 1; i >= limit; i--) if (!isBlankLine(i)) return i;
        return -1;
    }

    /**
     * Lines added on one design may not fit another (Polaroid's caption is much smaller than
     * Minimal's page), or once the Spotify Code takes its footer. Drops lines from the end until the
     * selection fits the current card again.
     */
    private void trimSelectionToFit() {
        if (document == null || picked.isEmpty()) return;
        if (picked.size() <= 1 || selectionFits(picked)) return;
        java.util.TreeSet<Integer> next = new java.util.TreeSet<>(picked);
        while (next.size() > 1 && !selectionFits(next)) next.pollLast();
        setPicked(next);
    }

    private boolean isBlankLine(int index) {
        AppliedLine line = document.appliedLines.get(index);
        return line.dotLine || isBlank(line.text);
    }

    // ---------------------------------------------------------------- rendering + transitions

    private void render(Transition transition) {
        int token = ++generation;
        List<String> quotes = collectQuotes(selection());
        List<String> translations = collectTranslations(selection());
        List<Integer> ids = collectQuoteIds(selection());
        Design d = design;
        TextStyle style = textStyle;
        Backdrop b = backdrop;
        Bitmap code = layoutCode(d);
        boolean wantCode = spotifyCode;
        Bitmap art = artwork;
        Bitmap artist = artistImage;
        Bitmap lyricsBg = lyricsBackground;
        SpotifyTrack t = track;
        float thumbScale = thumbScale();
        CodeSlot slot = codeSlot(d, !quotes.isEmpty());
        // The image that is shared: drawn only when it is actually shared or saved - every
        // render used to copy and redraw a full-size card for it, most of them never used.
        // The code as it is when shared: the stand-in is swapped for the real one if it came.
        CardRecipe recipe = new CardRecipe(rounded -> {
            Bitmap shared = wantCode ? cachedCode(t, onPaper(d)) : null;
            return renderCard(d, style, b, shared, art, artist, lyricsBg,
                    quotes, translations, safe(t.title), safe(t.artist), true, true, rounded);
        });
        RENDER.execute(() -> {
            Bitmap base;
            List<Piece> pieces;
            Bitmap thumb;
            CodeArt codeArt;
            try {
                // The code's slot is left empty: the preview draws the code over it, animated.
                base = renderCard(d, style, b, code, art, artist, lyricsBg, quotes, translations,
                        safe(t.title), safe(t.artist), false, false);
                pieces = quotePieces(d, style, quotes, translations, code != null, ids);
                codeArt = code == null || code == PENDING_CODE ? null : codeArt(code, slot.paper);
                thumb = renderCardScaled(d, style, b, art, artist, lyricsBg, quotes, translations,
                        safe(t.title), safe(t.artist), thumbScale);
            } catch (Throwable error) {
                XpLog.log(TAG + " render failed: " + error);
                return;
            }
            main.post(() -> {
                if (token != generation || cardHost == null) return;
                currentRecipe = recipe;
                setThumb(d, thumb);
                boolean textOnly = transition == Transition.SLIDE_UP || transition == Transition.SLIDE_DOWN
                        || transition == Transition.TEXT || transition == Transition.ALIGN;
                Runnable apply = () -> {
                    if (token != generation || cardHost == null) return;
                    // Only the text may change in place; a code that came or went swaps the card.
                    if (textOnly && currentCard != null && code == currentCode) {
                        swapText(base, pieces, transition);
                    } else {
                        swapCard(base, pieces, textOnly ? Transition.FADE : transition, code, codeArt, slot);
                    }
                };
                // The pieces are placed in the card's own scale: wait for it to be laid out.
                if (cardHost.getWidth() > 0) apply.run();
                else cardHost.post(apply);
            });
        });
        // The neighbours after the card itself, so the card never waits behind them.
        renderPeeks();
    }

    /**
     * Lines added, pushed out or translated: the card stays put and the lyric lines themselves
     * move. A line that stays glides from where (and how large) it was to its new place - the
     * text re-fits as lines come and go - a new line slides in from the side it was added on, and
     * a line pushed off the card slides out the other way.
     */
    private void swapText(Bitmap base, List<Piece> pieces, Transition transition) {
        if (transition != Transition.ALIGN) settleLineSlides();
        currentBase.setImageBitmap(base);
        FrameLayout old = currentTextLayer;
        Map<Integer, ImageView> oldViews = pieceViews;
        Map<Integer, Piece> oldData = pieceData;
        Map<Integer, ImageView> newViews = new java.util.HashMap<>();
        Map<Integer, Piece> newData = new java.util.HashMap<>();
        FrameLayout layer = buildTextLayer(pieces, newViews, newData);
        currentCard.addView(layer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        currentTextLayer = layer;
        pieceViews = newViews;
        pieceData = newData;

        // One soft curve for every line, so the block moves as one list scrolling: lines that
        // stay glide (re-sizing as the text re-fits), a line coming in follows on from the
        // side it was added on by the same distance, and a line pushed out carries on the same
        // way, fading as it goes.
        PathInterpolator ease = new PathInterpolator(0.22f, 1f, 0.36f, 1f);
        long moveMs = 540L;
        float minTravel = dp(22);
        int oldFirst = Integer.MAX_VALUE;
        int oldLast = Integer.MIN_VALUE;
        for (int id : oldData.keySet()) {
            oldFirst = Math.min(oldFirst, id);
            oldLast = Math.max(oldLast, id);
        }
        int newFirst = Integer.MAX_VALUE;
        int newLast = Integer.MIN_VALUE;
        for (int id : newData.keySet()) {
            newFirst = Math.min(newFirst, id);
            newLast = Math.max(newLast, id);
        }
        boolean translationChange = transition == Transition.TEXT || transition == Transition.ALIGN;
        float cardScale = cardHost.getWidth() / (float) W;
        // The old text may still lean after a drag: lines leave from where they are seen.
        float oldLean = old != null ? old.getTranslationY() : 0f;
        // How far the lines that stay move: the whole block's shift.
        float shift = 0f;
        int staying = 0;
        for (Piece piece : pieces) {
            Piece before = oldData.get(piece.id);
            ImageView beforeView = oldViews.get(piece.id);
            if (before == null || beforeView == null || piece.id < 0) continue;
            shift += Math.round(piece.y * cardScale)
                    - (Math.round(before.y * cardScale) + beforeView.getTranslationY() + oldLean);
            staying++;
        }
        if (staying > 0) shift /= staying;
        for (Piece piece : pieces) {
            ImageView view = newViews.get(piece.id);
            Piece before = oldData.get(piece.id);
            ImageView beforeView = oldViews.get(piece.id);
            if (view == null) continue;
            if (transition == Transition.ALIGN && before != null && beforeView != null) {
                if (slideLines(layer, piece, view, before, beforeView, cardScale, ease)) continue;
                // Cannot slide (the text re-fitted): a slide still running lands first.
                settleLineSlide(piece.id);
            }
            if (before != null && beforeView != null && piece.id >= 0) {
                float fromY = Math.round(before.y * cardScale) + beforeView.getTranslationY() + oldLean;
                float toY = Math.round(piece.y * cardScale);
                float scale = before.size / Math.max(1f, piece.size);
                // Re-sizing about the text's own anchor, so centred or right-set text stays put.
                view.setPivotX(anchorX(piece) * cardScale);
                beforeView.setPivotX(anchorX(before) * cardScale);
                view.setTranslationY(fromY - toY);
                view.setScaleX(scale);
                view.setScaleY(scale);
                view.animate().translationY(0f).scaleX(1f).scaleY(1f).setDuration(moveMs)
                        .setInterpolator(ease).start();
                if (translationChange) {
                    // Its translation appears or leaves: the old rendering follows the move and
                    // dissolves into the new one.
                    view.setAlpha(0f);
                    view.animate().alpha(1f);
                    beforeView.animate().translationY(toY - Math.round(before.y * cardScale) - oldLean)
                            .scaleX(1f / scale).scaleY(1f / scale).alpha(0f)
                            .setDuration(moveMs).setInterpolator(ease).start();
                } else {
                    beforeView.setVisibility(View.INVISIBLE);
                }
            } else {
                boolean below = piece.id > oldLast;
                boolean above = piece.id < oldFirst;
                if (below || above) {
                    // Comes on from its side, as far as the block moves (at least a little).
                    float from = below ? Math.max(-shift, minTravel) : -Math.max(shift, minTravel);
                    view.setTranslationY(from);
                } else {
                    // Picked in between: it opens up in place.
                    view.setPivotX(anchorX(piece) * cardScale);
                    view.setScaleX(0.92f);
                    view.setScaleY(0.92f);
                }
                view.setAlpha(0f);
                view.animate().translationY(0f).scaleX(1f).scaleY(1f).setStartDelay(30)
                        .setDuration(moveMs).setInterpolator(ease).start();
                fadeTo(view, 1f, 380L, 90L);
            }
        }
        for (Map.Entry<Integer, ImageView> entry : oldViews.entrySet()) {
            if (newData.containsKey(entry.getKey()) && entry.getKey() >= 0) continue;
            ImageView gone = entry.getValue();
            boolean above = entry.getKey() < newFirst;
            boolean below = entry.getKey() > newLast;
            if (above || below) {
                // Carries on the way the block moves, fading out before it reaches the edge.
                float by = above ? Math.min(shift, -minTravel) : Math.max(shift, minTravel);
                gone.animate().translationYBy(by).setDuration(moveMs).setInterpolator(ease).start();
            } else {
                gone.animate().scaleX(0.92f).scaleY(0.92f).setDuration(moveMs).setInterpolator(ease).start();
            }
            fadeTo(gone, 0f, 280L, 0L);
        }
        if (old != null) {
            old.postDelayed(() -> {
                if (old.getParent() instanceof ViewGroup) ((ViewGroup) old.getParent()).removeView(old);
            }, 580);
        }
    }

    /**
     * Re-aligned (or moved) text: the same lines at the same size, so each wrapped line is cut
     * from the new image and slides on its own from where it was to where it goes - a centred
     * short line and a long one travel different distances, as they should. False when the
     * lines are not the same ones (the text was re-fitted), and the piece cross-fades instead.
     */
    private boolean slideLines(FrameLayout layer, Piece piece, ImageView view, Piece before,
                               ImageView beforeView, float cardScale, PathInterpolator ease) {
        int n = piece.lineLeft.length;
        if (n == 0 || n != before.lineLeft.length || Math.abs(piece.size - before.size) > 0.5f
                || Math.abs(beforeView.getScaleX() - 1f) > 0.01f) {
            return false;
        }
        Bitmap image = piece.bitmap;
        // Re-aligned again mid-slide: the lines still travelling start from where they are now,
        // not from where the last slide was taking them, and that slide's hand-over is
        // cancelled - left running, it revealed the old text under the new strips (the text
        // split in two).
        LineSlide previous = lineSlides.remove(piece.id);
        if (previous != null) previous.host.removeCallbacks(previous.reveal);
        List<View> strips = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            int left = Math.max(0, (int) Math.floor(piece.lineLeft[k]) - 6);
            int right = Math.min(image.getWidth(), (int) Math.ceil(piece.lineRight[k]) + 6);
            int top = Math.max(0, (int) Math.floor(piece.lineTop[k]));
            int bottom = Math.min(image.getHeight(), (int) Math.ceil(piece.lineBottom[k]) + 2);
            if (right <= left || bottom <= top) continue;
            ImageView strip = new ImageView(activity);
            strip.setImageBitmap(Bitmap.createBitmap(image, left, top, right - left, bottom - top));
            strip.setScaleType(ImageView.ScaleType.FIT_XY);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    Math.max(1, Math.round((right - left) * cardScale)),
                    Math.max(1, Math.round((bottom - top) * cardScale)));
            lp.leftMargin = Math.round((piece.x + left) * cardScale);
            lp.topMargin = Math.round((piece.y + top) * cardScale);
            layer.addView(strip, lp);
            View flying = previous != null && k < previous.strips.size() ? previous.strips.get(k) : null;
            if (flying != null && flying.getParent() instanceof View) {
                // Where that line is on the card this frame, in the new layer's terms.
                // (From its margins: a strip added this same frame has not been laid out yet.)
                View oldLayer = (View) flying.getParent();
                FrameLayout.LayoutParams at = (FrameLayout.LayoutParams) flying.getLayoutParams();
                strip.setTranslationX(oldLayer.getX() + at.leftMargin + flying.getTranslationX()
                        - layer.getX() - lp.leftMargin);
                strip.setTranslationY(oldLayer.getY() + at.topMargin + flying.getTranslationY()
                        - layer.getY() - lp.topMargin);
            } else {
                strip.setTranslationX((before.x + before.lineLeft[k] - piece.x - piece.lineLeft[k]) * cardScale
                        + beforeView.getTranslationX());
                strip.setTranslationY((before.y + before.lineTop[k] - piece.y - piece.lineTop[k]) * cardScale
                        + beforeView.getTranslationY());
            }
            // Line after line, a beat apart: the block ripples into its new shape.
            strip.animate().translationX(0f).translationY(0f).setStartDelay(28L * k).setDuration(440)
                    .setInterpolator(ease).start();
            strips.add(strip);
        }
        if (previous != null) removeStrips(previous.strips);
        if (strips.isEmpty()) return false;
        beforeView.setVisibility(View.INVISIBLE);
        view.setAlpha(0f);
        // The whole piece takes over once every line has arrived.
        LineSlide slide = new LineSlide(view, strips);
        slide.reveal = () -> {
            if (lineSlides.get(piece.id) == slide) lineSlides.remove(piece.id);
            view.setAlpha(1f);
            removeStrips(strips);
        };
        lineSlides.put(piece.id, slide);
        view.postDelayed(slide.reveal, 440L + 28L * (n - 1) + 20L);
        return true;
    }

    /** A lyric line's wrapped lines sliding to a new alignment, and the hand-over after. */
    private static final class LineSlide {
        final View host;
        final List<View> strips;
        Runnable reveal;

        LineSlide(View host, List<View> strips) {
            this.host = host;
            this.strips = strips;
        }
    }

    /** Slides in flight, by lyric line. */
    private final Map<Integer, LineSlide> lineSlides = new java.util.HashMap<>();

    private static void removeStrips(List<View> strips) {
        for (View strip : strips) {
            strip.animate().cancel();
            if (strip.getParent() instanceof ViewGroup) ((ViewGroup) strip.getParent()).removeView(strip);
        }
    }

    /**
     * Any other text change while lines are still sliding: they finish at once where they are
     * headed (the whole piece shown, the strips gone), so the next move starts from one copy.
     */
    private void settleLineSlides() {
        for (Integer id : new ArrayList<>(lineSlides.keySet())) settleLineSlide(id);
    }

    private void settleLineSlide(int id) {
        LineSlide slide = lineSlides.remove(id);
        if (slide == null) return;
        slide.host.removeCallbacks(slide.reveal);
        slide.reveal.run();
    }

    /** Alpha on its own clock, so a line can fade quicker than it moves. */
    private static void fadeTo(View view, float alpha, long duration, long delay) {
        android.animation.ObjectAnimator fade = android.animation.ObjectAnimator.ofFloat(view, View.ALPHA, alpha);
        fade.setDuration(duration);
        fade.setStartDelay(delay);
        fade.start();
    }

    /** Where a piece's text is anchored across its image: left, centre or right. */
    private static float anchorX(Piece piece) {
        int n = piece.lineLeft.length;
        if (n == 0) return 0f;
        float width = piece.bitmap.getWidth();
        boolean left = true;
        boolean right = true;
        for (int k = 0; k < n; k++) {
            if (piece.lineLeft[k] > 2f) left = false;
            if (piece.lineRight[k] < width - 3f) right = false;
        }
        if (left) return 0f;
        if (right) return width;
        return width / 2f;
    }

    /** The pieces as views, in the preview's scale of the card. */
    private FrameLayout buildTextLayer(List<Piece> pieces, Map<Integer, ImageView> views,
                                       Map<Integer, Piece> data) {
        FrameLayout layer = new FrameLayout(activity);
        layer.setClipChildren(false);
        float scale = cardHost.getWidth() / (float) W;
        for (Piece piece : pieces) {
            ImageView view = new ImageView(activity);
            view.setImageBitmap(piece.bitmap);
            view.setScaleType(ImageView.ScaleType.FIT_XY);
            // Placed by margins, not setX/setY: those write the translation, which the line
            // animations own (and reset to 0 - every line then landed at the top).
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    Math.max(1, Math.round(piece.bitmap.getWidth() * scale)),
                    Math.max(1, Math.round(piece.bitmap.getHeight() * scale)));
            lp.leftMargin = Math.round(piece.x * scale);
            lp.topMargin = Math.round(piece.y * scale);
            layer.addView(view, lp);
            view.setPivotX(0f);
            view.setPivotY(0f);
            views.put(piece.id, view);
            data.put(piece.id, piece);
        }
        return layer;
    }

    /**
     * The quote as the card sets it, split per lyric line: same box, size and positions as
     * {@link #drawQuoteText}, each line (with its translation) on its own transparent image.
     */
    private static List<Piece> quotePieces(Design design, TextStyle style, List<String> quotes,
                                           List<String> translations, boolean withCode,
                                           List<Integer> ids) {
        List<Piece> out = new ArrayList<>();
        if (quotes == null || quotes.isEmpty()) return out;
        TextBox box = lyricBox(design, style, withCode);
        Fitted f = layoutLyrics(quotes, translations, box, false);
        float top = quoteTop(design, style, box, f.height);
        int width = Math.max(1, (int) Math.ceil(box.width));
        if (f.main.size() != quotes.size()) {
            // Squeezed into one block (lastResort): a single piece.
            Bitmap bitmap = Bitmap.createBitmap(width, Math.max(1, (int) Math.ceil(f.height) + 4),
                    Bitmap.Config.ARGB_8888);
            drawFitted(new Canvas(bitmap), f, box.moved(0, 0, box.height), 0f);
            List<float[]> lines = new ArrayList<>();
            addLines(lines, f.main.get(0), 0f);
            out.add(new Piece(-1, bitmap, box.left, top, f.main.get(0).getPaint().getTextSize(), lines));
            return out;
        }
        float y = top;
        for (int i = 0; i < f.main.size(); i++) {
            StaticLayout main = f.main.get(i);
            StaticLayout sub = f.sub.get(i);
            float size = main.getPaint().getTextSize();
            float height = main.getHeight() + (sub == null ? 0f : size * 0.18f + sub.getHeight());
            Bitmap bitmap = Bitmap.createBitmap(width, Math.max(1, (int) Math.ceil(height) + 4),
                    Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            main.draw(canvas);
            if (sub != null) {
                canvas.save();
                canvas.translate(0f, main.getHeight() + size * 0.18f);
                sub.draw(canvas);
                canvas.restore();
            }
            int id = ids != null && i < ids.size() ? ids.get(i) : i;
            List<float[]> lines = new ArrayList<>();
            addLines(lines, main, 0f);
            if (sub != null) addLines(lines, sub, main.getHeight() + size * 0.18f);
            out.add(new Piece(id, bitmap, box.left, y, size, lines));
            y += height;
            if (i < f.main.size() - 1) y += size * 0.5f;
        }
        return out;
    }

    private void swapCard(Bitmap base, List<Piece> pieces, Transition transition, Bitmap code,
                          CodeArt codeArt, CodeSlot slot) {
        settleLineSlides();
        FrameLayout incoming = new FrameLayout(activity);
        ImageView baseView = new ImageView(activity);
        baseView.setImageBitmap(base);
        baseView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        Map<Integer, ImageView> views = new java.util.HashMap<>();
        Map<Integer, Piece> data = new java.util.HashMap<>();
        FrameLayout textLayer = buildTextLayer(pieces, views, data);
        incoming.addView(baseView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout outgoing = currentCard;
        if (code == PENDING_CODE) {
            // Rendered while the code was loading, and it has arrived since: use it.
            Bitmap real = cachedCode(track, slot.paper);
            if (real != null) {
                code = real;
                codeArt = codeArt(real, slot.paper);
            }
        }
        CodeView codeView = null;
        if (code != null) {
            // The code builds itself on the card: the logo pops in, then the bars rise and
            // sway like a playing waveform - for as long as the real code takes to arrive -
            // before settling into the scannable code.
            float scale = cardHost.getWidth() / (float) W;
            RectF frame = slot.frame(code);
            codeView = new CodeView(activity, codeArt, slot.paper);
            FrameLayout.LayoutParams codeLp = new FrameLayout.LayoutParams(
                    Math.max(1, Math.round(frame.width() * scale)), Math.max(1, Math.round(frame.height() * scale)));
            codeLp.leftMargin = Math.round(frame.left * scale);
            codeLp.topMargin = Math.round(frame.top * scale);
            incoming.addView(codeView, codeLp);
        }
        currentCode = code;
        currentCodeView = codeView;
        incoming.addView(textLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        cardHost.addView(incoming, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        currentCard = incoming;
        currentBase = baseView;
        currentTextLayer = textLayer;
        // The pressed line is still flying in word by word (or hovering, landed before the card):
        // its words land on this text and hand over to it.
        if (quoteFlying || (outgoing == null && pendingHandOff != null)) textLayer.setAlpha(0f);
        pieceViews = views;
        pieceData = data;
        PathInterpolator ease = new PathInterpolator(0.2f, 0.9f, 0.2f, 1f);
        float w = cardHost.getWidth() > 0 ? cardHost.getWidth() : dp(300);
        float h = cardHost.getHeight() > 0 ? cardHost.getHeight() : dp(380);
        float fromX = 0f, fromY = 0f, toX = 0f, toY = 0f;
        switch (transition) {
            case SLIDE_LEFT: fromX = w; toX = -w * 0.35f; break;
            case SLIDE_RIGHT: fromX = -w; toX = w * 0.35f; break;
            case SLIDE_UP: fromY = h * 0.18f; toY = -h * 0.12f; break;
            case SLIDE_DOWN: fromY = -h * 0.18f; toY = h * 0.12f; break;
            default: break;
        }
        incoming.setTranslationX(fromX);
        incoming.setTranslationY(fromY);
        incoming.setAlpha(transition == Transition.FADE || fromY != 0f ? 0f : 1f);
        if (outgoing == null) {
            incoming.setScaleX(0.94f);
            incoming.setScaleY(0.94f);
        }
        // On hardware layers: each card is drawn once and only composited while it moves.
        incoming.animate().translationX(0f).translationY(0f).alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(outgoing == null ? 420 : 360).setInterpolator(ease).withLayer().start();
        if (outgoing != null) {
            outgoing.animate().translationX(toX).translationY(toY)
                    .alpha(0f).scaleX(0.96f).scaleY(0.96f)
                    .setDuration(300).setInterpolator(ease).withLayer()
                    .withEndAction(() -> {
                        if (cardHost != null) cardHost.removeView(outgoing);
                    }).start();
        } else {
            // The first card: the host shows now that it has something in it, and the pressed
            // line's words leave the list to land on it.
            cardHost.animate().alpha(1f).setDuration(260).start();
            Runnable waiting = pendingHandOff;
            pendingHandOff = null;
            // Words already hovering where the text goes: they give way as the card fades in.
            if (waiting != null) cardHost.postDelayed(waiting, 140);
        }
        if (codeView != null && (!codePlayed || codeArt == null)) {
            // Once per opening (or per switching it on), after the words have landed; a card
            // still waiting for the code always dances until it comes.
            codePlayed = true;
            codeView.play(outgoing == null ? (quoteFlying ? 1150L : 650L) : 200L);
        }
    }

    /** Document indices of the lines collectQuotes() returns, in the same order. */
    private List<Integer> collectQuoteIds(List<Integer> lines) {
        List<Integer> out = new ArrayList<>();
        if (document == null) return out;
        for (int i : lines) {
            if (i < 0 || i >= document.appliedLines.size()) continue;
            AppliedLine line = document.appliedLines.get(i);
            if (line.dotLine || isBlank(line.text)) continue;
            out.add(i);
        }
        return out;
    }

    private List<String> collectQuotes(List<Integer> lines) {
        List<String> out = new ArrayList<>();
        if (document == null) return out;
        for (int i : lines) {
            if (i < 0 || i >= document.appliedLines.size()) continue;
            AppliedLine line = document.appliedLines.get(i);
            if (line.dotLine || isBlank(line.text)) continue;
            out.add(line.text);
        }
        return out;
    }

    private List<String> collectTranslations(List<Integer> lines) {
        List<String> out = new ArrayList<>();
        if (document == null) return out;
        for (int i : lines) {
            if (i < 0 || i >= document.appliedLines.size()) continue;
            AppliedLine line = document.appliedLines.get(i);
            if (line.dotLine || isBlank(line.text)) continue;
            out.add(showTranslation ? safe(line.translatedText) : "");
        }
        return out;
    }

    private static String webLink(SpotifyTrack track) {
        String link = spotifyUriToWebUrl(track == null ? "" : track.uri);
        return isBlank(link) ? "" : link;
    }

    // ---------------------------------------------------------------- card drawing

    /** Where the lyrics go on a design, and in which colours. */
    private static final class TextBox {
        final float left, top, width, height;
        final int color, subColor;
        final Layout.Alignment align;
        /** The lyric's typeface; null for the default bold. */
        final Typeface face;
        /** The largest the lyric is set; a caption (Polaroid) stays a caption. */
        final float maxSize;

        TextBox(float left, float top, float width, float height, int color, int subColor, boolean center) {
            this(left, top, width, height, color, subColor, center, null);
        }

        TextBox(float left, float top, float width, float height, int color, int subColor, boolean center,
                Typeface face) {
            this.left = left;
            this.top = top;
            this.width = width;
            this.height = height;
            this.color = color;
            this.subColor = subColor;
            this.align = center ? Layout.Alignment.ALIGN_CENTER : Layout.Alignment.ALIGN_NORMAL;
            this.face = face;
            this.maxSize = MAX_TEXT;
        }

        private TextBox(TextBox from, float left, float top, float height, Layout.Alignment align,
                        float maxSize) {
            this.left = left;
            this.top = top;
            this.width = from.width;
            this.height = height;
            this.color = from.color;
            this.subColor = from.subColor;
            this.align = align;
            this.face = from.face;
            this.maxSize = maxSize;
        }

        TextBox moved(float left, float top, float height) {
            return new TextBox(this, left, top, height, align, maxSize);
        }

        TextBox aligned(Layout.Alignment align) {
            return new TextBox(this, left, top, height, align, maxSize);
        }

        TextBox capped(float size) {
            return new TextBox(this, left, top, height, align, Math.max(MIN_TEXT, size));
        }
    }

    /** The user's lyric alignment and position on the card; AUTO keeps the design's own. */
    enum TextAlign { AUTO, START, CENTER, END }
    enum TextPos { AUTO, TOP, MIDDLE, BOTTOM }

    static final class TextStyle {
        final TextAlign align;
        final TextPos pos;

        TextStyle(TextAlign align, TextPos pos) {
            this.align = align;
            this.pos = pos;
        }
    }

    /** The box the lyric is set in: the design's, with the user's alignment. */
    private static TextBox lyricBox(Design design, TextStyle style, boolean withCode) {
        TextBox box = textBox(design, true);
        // Glass without the code: the lyric also takes the panel's footer.
        if (design == Design.GLASS && !withCode) box = box.moved(box.left, box.top, box.height + 170);
        switch (style.align) {
            case START:
                return box.aligned(Layout.Alignment.ALIGN_NORMAL);
            case CENTER:
                return box.aligned(Layout.Alignment.ALIGN_CENTER);
            case END:
                return box.aligned(Layout.Alignment.ALIGN_OPPOSITE);
            default:
                return box;
        }
    }

    /** Where the lyric block starts in its box: the user's position, else the design's own
     *  (centred, bottom-anchored or a little above centre). */
    private static float quoteTop(Design design, TextStyle style, TextBox box, float textHeight) {
        float free = Math.max(0f, box.height - textHeight);
        switch (style.pos) {
            case TOP:
                return box.top;
            case MIDDLE:
                return box.top + free / 2f;
            case BOTTOM:
                return box.top + free;
            default:
                break;
        }
        switch (design) {
            case MINIMAL:
            case SPOTLIGHT:
                return box.top + free / 2f;
            case POSTER:
                return box.top + free;
            default:
                return box.top + free * 0.45f;
        }
    }

    /** Designs printed on paper take the Spotify Code with dark bars. */
    private static boolean onPaper(Design design) {
        return design == Design.POLAROID || design == Design.TICKET;
    }

    private boolean fits(List<String> quotes, List<String> translations) {
        TextBox box = textBox(design, !quotes.isEmpty());
        return layoutLyrics(quotes, translations, box, true) != null;
    }

    private static TextBox textBox(Design design, boolean hasQuotes) {
        switch (design) {
            case POLAROID:
                // The print's caption: under the photo with room to breathe, never poster-sized.
                return new TextBox(POLAROID_IN, POLAROID_PHOTO_BOTTOM + 52, W - 2 * POLAROID_IN, 288,
                        Color.rgb(28, 28, 30), Color.rgb(112, 110, 116), false).capped(96);
            case MINIMAL:
                return new TextBox(90, 110, W - 180, 920, Color.WHITE, Color.argb(170, 255, 255, 255), false);
            case CLASSIC:
                return new TextBox(90, 250, W - 180, 740, Color.WHITE, Color.argb(180, 255, 255, 255), false);
            case POSTER:
                return new TextBox(90, 540, W - 180, 600, Color.WHITE, Color.argb(190, 255, 255, 255), false);
            case VINYL:
                return new TextBox(110, 640, W - 220, 470, Color.WHITE, Color.argb(180, 255, 255, 255), true);
            case TICKET:
                return new TextBox(170, 280, W - 340, 670, Color.rgb(30, 30, 34), Color.rgb(112, 108, 100),
                        false, Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            case SPOTLIGHT:
                return new TextBox(100, 530, W - 200, 580, Color.WHITE, Color.argb(180, 255, 255, 255), true);
            default:
                // Glass: under the header row and its rule, down to the code's footer.
                return new TextBox(GLASS_IN, GLASS_RULE_Y + 46, W - 2 * GLASS_IN, 560, Color.WHITE,
                        Color.argb(185, 255, 255, 255), false);
        }
    }

    // Glass: the frosted panel, its inner margin, and the rule under the header row.
    private static final int GLASS_PANEL = 80;
    private static final int GLASS_IN = 140;
    private static final int GLASS_RULE_Y = 392;
    private static final int GLASS_FOOTER_Y = 1102;
    private static final int CODE_W_GLASS = 460;
    // Polaroid: the paper, the photo's border on it, and the caption's footer row.
    private static final int POLAROID_PAPER_X = 100;
    private static final int POLAROID_PAPER_Y = 80;
    private static final int POLAROID_IN = 160;
    private static final int POLAROID_PHOTO_TOP = 150;
    private static final int POLAROID_PHOTO_BOTTOM = 750;
    private static final int POLAROID_FOOTER_Y = 1180;

    /** Where a design puts the Spotify Code: its right edge, vertical centre and width. */
    static final class CodeSlot {
        final float right, centreY, width;
        final boolean paper;

        CodeSlot(float right, float centreY, float width, boolean paper) {
            this.right = right;
            this.centreY = centreY;
            this.width = width;
            this.paper = paper;
        }

        RectF frame(Bitmap code) {
            float height = width * code.getHeight() / Math.max(1f, code.getWidth());
            return new RectF(right - width, centreY - height / 2f, right, centreY + height / 2f);
        }
    }

    private static CodeSlot codeSlot(Design design, boolean hasQuotes) {
        switch (design) {
            case POLAROID:
                return new CodeSlot(W - POLAROID_IN, hasQuotes ? POLAROID_FOOTER_Y : POLAROID_FOOTER_Y - 20,
                        CODE_W_POLAROID, true);
            case MINIMAL:
                return new CodeSlot(W - 90, hasQuotes ? H - 125 : H - 140, CODE_W, false);
            case CLASSIC:
                return new CodeSlot(W - 90, H - 175, CODE_W_CLASSIC, false);
            case POSTER:
                return new CodeSlot(W - 90, hasQuotes ? H - 120 : H - 190, CODE_W, false);
            case VINYL:
                return new CodeSlot(W - 110, hasQuotes ? H - 130 : H - 170, CODE_W_CLASSIC, false);
            case TICKET:
                // The stub, between the perforation and the paper's foot.
                return new CodeSlot(W - 170, H - 225, 360, true);
            case SPOTLIGHT:
                return new CodeSlot(W / 2f + 200, H - 120, 400, false);
            default:
                // Glass: centred in the panel's footer under the lyric; beside the title without.
                return hasQuotes
                        ? new CodeSlot(W / 2f + CODE_W_GLASS / 2f, GLASS_FOOTER_Y, CODE_W_GLASS, false)
                        : new CodeSlot(W - GLASS_IN, GLASS_FOOTER_Y, CODE_W_POLAROID, false);
        }
    }

    private static final class Fitted {
        final List<StaticLayout> main = new ArrayList<>();
        final List<StaticLayout> sub = new ArrayList<>();
        float height;
    }

    /**
     * Largest size (down to the floor, in steps of 2) at which the whole selection fits the box,
     * or null. Binary search: a card used to try up to 48 sizes per render, and every render
     * (card, neighbours, eight thumbnails) paid for it.
     */
    private static Fitted layoutLyrics(List<String> quotes, List<String> translations, TextBox box,
                                       boolean strict) {
        int steps = (int) ((box.maxSize - MIN_TEXT) / 2f);
        Fitted best = null;
        int lo = 0;
        int hi = steps;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            Fitted f = fittedAt(quotes, translations, box, box.maxSize - mid * 2f);
            if (f.height <= box.height) {
                best = f;
                hi = mid - 1;
            } else {
                lo = mid + 1;
            }
        }
        if (best != null || strict) return best;
        TextPaint paint = lyricPaint(box, MIN_TEXT);
        return lastResort(quotes, paint, box);
    }

    private static TextPaint lyricPaint(TextBox box, float size) {
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(box.color);
        paint.setTypeface(box.face != null ? box.face : Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        paint.setTextSize(size);
        return paint;
    }

    /** The selection laid out at one size (fresh paints: a layout keeps drawing with its paint). */
    private static Fitted fittedAt(List<String> quotes, List<String> translations, TextBox box, float size) {
        TextPaint paint = lyricPaint(box, size);
        TextPaint sub = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        sub.setColor(box.subColor);
        if (box.face != null) sub.setTypeface(Typeface.create(box.face, Typeface.NORMAL));
        sub.setTextSize(Math.max(26f, size * 0.52f));
        Fitted f = new Fitted();
        float gap = size * 0.5f;
        for (int i = 0; i < quotes.size(); i++) {
            StaticLayout l = layout(quotes.get(i), paint, (int) box.width, box.align);
            f.main.add(l);
            f.height += l.getHeight();
            String t = translations != null && i < translations.size() ? translations.get(i) : "";
            if (!isBlank(t)) {
                StaticLayout sl = layout(t, sub, (int) box.width, box.align);
                f.sub.add(sl);
                f.height += size * 0.18f + sl.getHeight();
            } else {
                f.sub.add(null);
            }
            if (i < quotes.size() - 1) f.height += gap;
        }
        return f;
    }

    /** A single oversized line: the floor size, ellipsized to the box. Never overflows. */
    private static Fitted lastResort(List<String> quotes, TextPaint paint, TextBox box) {
        paint.setTextSize(MIN_TEXT);
        String joined = TextUtils.join("\n", quotes);
        int maxLines = Math.max(1, (int) (box.height / (MIN_TEXT * 1.2f)));
        StaticLayout l = StaticLayout.Builder.obtain(joined, 0, joined.length(), paint, (int) box.width)
                .setLineSpacing(0f, 1.08f)
                .setMaxLines(maxLines)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build();
        Fitted f = new Fitted();
        f.main.add(l);
        f.sub.add(null);
        f.height = l.getHeight();
        return f;
    }

    private static StaticLayout layout(String text, TextPaint paint, int width, Layout.Alignment align) {
        return StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                .setAlignment(align)
                .setLineSpacing(0f, 1.08f)
                .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
                .build();
    }

    private static Bitmap renderCard(Design design, TextStyle style, Backdrop backdrop, Bitmap code,
                                     Bitmap art, Bitmap artist, Bitmap lyricsBg, List<String> quotes,
                                     List<String> translations,
                                     String title, String artistName) {
        return renderCard(design, style, backdrop, code, art, artist, lyricsBg, quotes, translations,
                title, artistName, true);
    }

    /** The whole card drawn at {@code scale} of its size - thumbnails without a full-size pass. */
    private static Bitmap renderCardScaled(Design design, TextStyle style, Backdrop backdrop,
                                           Bitmap art, Bitmap artist, Bitmap lyricsBg,
                                           List<String> quotes, List<String> translations,
                                           String title, String artistName, float scale) {
        Bitmap bitmap = Bitmap.createBitmap(Math.max(1, Math.round(W * scale)),
                Math.max(1, Math.round(H * scale)), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.scale(scale, scale);
        drawCard(canvas, design, style, backdrop, null, art, artist, lyricsBg, quotes, translations,
                title, artistName, true, false, true);
        return bitmap;
    }

    /**
     * The lyric text a design sets on the card, drawn on its own so the preview can animate it
     * separately from the card (see swapText). Same boxes and sizes as the full card.
     */
    private static void drawQuoteText(Canvas canvas, Design design, TextStyle style, List<String> quotes,
                                      List<String> translations, boolean withCode) {
        if (quotes == null || quotes.isEmpty()) return;
        TextBox box = lyricBox(design, style, withCode);
        Fitted f = layoutLyrics(quotes, translations, box, false);
        drawFitted(canvas, f, box, quoteTop(design, style, box, f.height));
    }

    private static Bitmap renderCard(Design design, TextStyle style, Backdrop backdrop, Bitmap code,
                                     Bitmap art, Bitmap artist, Bitmap lyricsBg, List<String> quotes,
                                     List<String> translations,
                                     String title, String artistName, boolean withQuoteText) {
        return renderCard(design, style, backdrop, code, art, artist, lyricsBg, quotes, translations,
                title, artistName, withQuoteText, true);
    }

    /**
     * {@code drawCode} false lays the card out for the code but leaves its slot empty: the
     * preview draws the code itself, animated (see {@link CodeView}).
     */
    private static Bitmap renderCard(Design design, TextStyle style, Backdrop backdrop, Bitmap code,
                                     Bitmap art, Bitmap artist, Bitmap lyricsBg, List<String> quotes,
                                     List<String> translations, String title, String artistName,
                                     boolean withQuoteText, boolean drawCode) {
        return renderCard(design, style, backdrop, code, art, artist, lyricsBg, quotes, translations,
                title, artistName, withQuoteText, drawCode, true);
    }

    /**
     * {@code rounded} cuts the card's corners away (transparent) - the card's shape on screen and
     * as a story sticker; a shared or saved image keeps them, square, since chat apps and the
     * gallery show cut corners as odd blank (or black) wedges.
     */
    private static Bitmap renderCard(Design design, TextStyle style, Backdrop backdrop, Bitmap code,
                                     Bitmap art, Bitmap artist, Bitmap lyricsBg, List<String> quotes,
                                     List<String> translations, String title, String artistName,
                                     boolean withQuoteText, boolean drawCode, boolean rounded) {
        Bitmap bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        drawCard(new Canvas(bitmap), design, style, backdrop, code, art, artist, lyricsBg, quotes,
                translations, title, artistName, withQuoteText, drawCode, rounded);
        return bitmap;
    }

    private static void drawCard(Canvas canvas, Design design, TextStyle style, Backdrop backdrop,
                                 Bitmap code, Bitmap art, Bitmap artist, Bitmap lyricsBg,
                                 List<String> quotes, List<String> translations,
                                 String title, String artistName, boolean withQuoteText,
                                 boolean drawCode, boolean rounded) {
        boolean withCode = code != null;
        Bitmap codeInk = drawCode ? code : null;
        if (rounded) {
            Path clip = new Path();
            clip.addRoundRect(new RectF(0, 0, W, H), 64, 64, Path.Direction.CW);
            canvas.clipPath(clip);
        }
        // Poster paints its own full-bleed ground; the rest sit on the chosen backdrop.
        if (design != Design.POSTER) {
            drawBackdrop(canvas, backdrop, art, artist, lyricsBg,
                    design == Design.MINIMAL || design == Design.SPOTLIGHT ? 0.55f : 0.72f);
        }

        boolean hasQuotes = quotes != null && !quotes.isEmpty();
        TextBox box = textBox(design, hasQuotes);
        switch (design) {
            case POLAROID: {
                // An instant print: even borders round the photo, the deep bottom margin holding
                // the caption and the song, on slightly warm paper that lifts off the backdrop.
                RectF paper = new RectF(POLAROID_PAPER_X, POLAROID_PAPER_Y, W - POLAROID_PAPER_X,
                        H - POLAROID_PAPER_Y);
                Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
                shadow.setColor(Color.argb(110, 0, 0, 0));
                shadow.setMaskFilter(new android.graphics.BlurMaskFilter(40, android.graphics.BlurMaskFilter.Blur.NORMAL));
                canvas.drawRoundRect(new RectF(paper.left + 6, paper.top + 22, paper.right - 6, paper.bottom + 24),
                        18, 18, shadow);
                Paint paperPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                paperPaint.setShader(new LinearGradient(0, paper.top, 0, paper.bottom,
                        Color.rgb(251, 250, 246), Color.rgb(241, 238, 231), Shader.TileMode.CLAMP));
                canvas.drawRoundRect(paper, 14, 14, paperPaint);
                RectF photo = new RectF(POLAROID_IN, POLAROID_PHOTO_TOP, W - POLAROID_IN, POLAROID_PHOTO_BOTTOM);
                Paint well = new Paint(Paint.ANTI_ALIAS_FLAG);
                well.setColor(Color.rgb(34, 34, 36));
                canvas.drawRect(photo, well);
                drawCover(canvas, art, photo, 4);
                // A faint gloss across the print, and its edge pressed into the paper.
                Paint gloss = new Paint(Paint.ANTI_ALIAS_FLAG);
                gloss.setShader(new LinearGradient(photo.left, photo.top, photo.left + photo.width() * 0.6f,
                        photo.top + photo.height() * 0.6f, Color.argb(34, 255, 255, 255), Color.TRANSPARENT,
                        Shader.TileMode.CLAMP));
                canvas.drawRect(photo, gloss);
                Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
                rim.setStyle(Paint.Style.STROKE);
                rim.setStrokeWidth(2f);
                rim.setColor(Color.argb(40, 0, 0, 0));
                canvas.drawRect(photo, rim);
                float footerWidth = W - 2 * POLAROID_IN - (withCode ? CODE_W_POLAROID + 36 : 0);
                if (hasQuotes) {
                    if (withQuoteText) drawQuoteText(canvas, design, style, quotes, translations, withCode);
                    drawTitleBlock(canvas, title, artistName, POLAROID_IN, POLAROID_FOOTER_Y, footerWidth,
                            Color.rgb(40, 40, 44), Color.rgb(122, 120, 126), 34, 28);
                } else {
                    // No quote: the caption is the song itself, set large in the print's margin.
                    drawTitleBlock(canvas, title, artistName, POLAROID_IN, POLAROID_PHOTO_BOTTOM + 150,
                            W - 2 * POLAROID_IN, Color.rgb(34, 34, 38), Color.rgb(110, 110, 116), 56, 36);
                }
                drawCodeAt(canvas, codeInk, codeSlot(design, hasQuotes));
                break;
            }
            case MINIMAL: {
                if (hasQuotes) {
                    if (withQuoteText) drawQuoteText(canvas, design, style, quotes, translations, withCode);
                    drawTitleBlock(canvas, title, artistName, 90, H - 125, W - 180 - (withCode ? CODE_W + 40 : 0),
                            Color.WHITE, Color.argb(170, 255, 255, 255), 34, 28);
                } else {
                    // No quote: the cover fills the card, the song name under it.
                    drawCover(canvas, art, new RectF(110, 110, W - 110, 110 + W - 220), 36);
                    drawTitleBlock(canvas, title, artistName, 110, H - 140, W - 220 - (withCode ? CODE_W + 30 : 0),
                            Color.WHITE, Color.argb(170, 255, 255, 255), 44, 32);
                }
                drawCodeAt(canvas, codeInk, codeSlot(design, hasQuotes));
                break;
            }
            case CLASSIC: {
                Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);
                mark.setColor(Color.argb(60, 255, 255, 255));
                mark.setTextSize(300);
                mark.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
                Paint line = new Paint();
                line.setColor(Color.argb(50, 255, 255, 255));
                canvas.drawRect(90, H - 290, W - 90, H - 288, line);
                if (hasQuotes) {
                    canvas.drawText("“", 70, 330, mark);
                    if (withQuoteText) drawQuoteText(canvas, design, style, quotes, translations, withCode);
                    RectF thumb = new RectF(90, H - 250, 90 + 150, H - 100);
                    drawCover(canvas, art, thumb, 20);
                    drawTitleBlock(canvas, title, artistName, 90 + 180, H - 175,
                            W - 90 - 180 - 90 - (withCode ? CODE_W_CLASSIC + 40 : 0),
                            Color.WHITE, Color.argb(180, 255, 255, 255), 40, 32);
                } else {
                    // No quote: a large cover above the rule; no second thumbnail of the same art.
                    float side = H - 290 - 90 - 90;
                    drawCover(canvas, art, new RectF((W - side) / 2f, 90, (W + side) / 2f, 90 + side), 32);
                    drawTitleBlock(canvas, title, artistName, 90, H - 175,
                            W - 180 - (withCode ? CODE_W_CLASSIC + 40 : 0),
                            Color.WHITE, Color.argb(180, 255, 255, 255), 44, 32);
                }
                drawCodeAt(canvas, codeInk, codeSlot(design, hasQuotes));
                break;
            }
            case POSTER: {
                // Poster: the artwork (or the artist photo) full-bleed across the top, melting into
                // a solid colour taken from it; the lyric sits on the join, anchored to the title.
                Bitmap hero = backdrop == Backdrop.ARTIST && artist != null ? artist : art;
                int ground = extractGradient(art != null ? art : artist)[1];
                Paint fill = new Paint();
                fill.setColor(ground);
                canvas.drawRect(0, 0, W, H, fill);
                if (hero != null) {
                    canvas.drawBitmap(hero, centreCrop(hero, 1f), new RectF(0, 0, W, W),
                            new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG));
                }
                Paint melt = new Paint();
                melt.setShader(new LinearGradient(0, 0, 0, W,
                        new int[]{Color.argb(80, 0, 0, 0), Color.TRANSPARENT, withAlpha(ground, 160),
                                ground, ground},
                        new float[]{0f, 0.28f, 0.55f, 0.88f, 1f}, Shader.TileMode.CLAMP));
                canvas.drawRect(0, 0, W, W + 2, melt);
                float rowY = hasQuotes ? H - 120 : H - 190;
                if (hasQuotes) {
                    if (withQuoteText) drawQuoteText(canvas, design, style, quotes, translations, withCode);
                    drawTitleBlock(canvas, title, artistName, 90, rowY, W - 180 - (withCode ? CODE_W + 40 : 0),
                            Color.WHITE, Color.argb(180, 255, 255, 255), 36, 29);
                } else {
                    drawTitleBlock(canvas, title, artistName, 90, rowY, W - 180 - (withCode ? CODE_W + 40 : 0),
                            Color.WHITE, Color.argb(180, 255, 255, 255), 62, 38);
                }
                drawCodeAt(canvas, codeInk, codeSlot(design, hasQuotes));
                break;
            }
            case VINYL: {
                // Vinyl: a record with the artwork as its label, the lyric set centred beneath it.
                float cx = W / 2f;
                drawRecord(canvas, art, cx, hasQuotes ? 330 : 560, hasQuotes ? 250 : 400);
                if (hasQuotes && withQuoteText) drawQuoteText(canvas, design, style, quotes, translations, withCode);
                float rowY = hasQuotes ? H - 130 : H - 170;
                float titleSize = hasQuotes ? 36 : 50;
                float artistSize = hasQuotes ? 29 : 34;
                if (withCode) {
                    drawTitleBlock(canvas, title, artistName, 110, rowY, W - 220 - CODE_W_CLASSIC - 40,
                            Color.WHITE, Color.argb(180, 255, 255, 255), titleSize, artistSize);
                    drawCodeAt(canvas, codeInk, codeSlot(design, hasQuotes));
                } else {
                    drawTitleBlock(canvas, title, artistName, 110, rowY, W - 220,
                            Color.WHITE, Color.argb(180, 255, 255, 255), titleSize, artistSize, true);
                }
                break;
            }
            case TICKET: {
                // Ticket: a paper stub with notches at the perforation, the lyric typed on it.
                RectF paper = new RectF(110, 110, W - 110, H - 110);
                float perforation = H - 340;
                Path shape = new Path();
                shape.addRoundRect(paper, 24, 24, Path.Direction.CW);
                Path notches = new Path();
                notches.addCircle(paper.left, perforation, 34, Path.Direction.CW);
                notches.addCircle(paper.right, perforation, 34, Path.Direction.CW);
                shape.op(notches, Path.Op.DIFFERENCE);
                Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
                shadow.setColor(Color.argb(90, 0, 0, 0));
                shadow.setMaskFilter(new android.graphics.BlurMaskFilter(36, android.graphics.BlurMaskFilter.Blur.NORMAL));
                canvas.save();
                canvas.translate(0, 16);
                canvas.drawPath(shape, shadow);
                canvas.restore();
                Paint paperPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                paperPaint.setColor(Color.rgb(250, 247, 240));
                canvas.drawPath(shape, paperPaint);
                Paint dash = new Paint(Paint.ANTI_ALIAS_FLAG);
                dash.setStyle(Paint.Style.STROKE);
                dash.setStrokeWidth(3);
                dash.setColor(Color.argb(90, 60, 56, 50));
                dash.setPathEffect(new android.graphics.DashPathEffect(new float[]{14, 12}, 0));
                canvas.drawLine(paper.left + 50, perforation, paper.right - 50, perforation, dash);
                dash.setStrokeWidth(2);
                canvas.drawLine(170, 236, W - 170, 236, dash);
                TextPaint head = new TextPaint(Paint.ANTI_ALIAS_FLAG);
                head.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
                head.setTextSize(30);
                head.setLetterSpacing(0.16f);
                head.setColor(Color.rgb(70, 66, 60));
                canvas.drawText("♪ ADMIT ONE", 170, 200, head);
                if (hasQuotes) {
                    if (withQuoteText) drawQuoteText(canvas, design, style, quotes, translations, withCode);
                } else {
                    drawCover(canvas, art, new RectF((W - 600) / 2f, 290, (W + 600) / 2f, 890), 12);
                }
                float stubY = (perforation + paper.bottom) / 2f;
                int ink = Color.rgb(30, 30, 34);
                int faded = Color.rgb(112, 108, 100);
                if (withCode) {
                    drawTitleBlock(canvas, title, artistName, 170, stubY, W - 340 - 360 - 30,
                            ink, faded, 36, 28);
                    drawCodeAt(canvas, codeInk, codeSlot(design, hasQuotes));
                } else {
                    drawCover(canvas, art, new RectF(170, stubY - 75, 320, stubY + 75), 10);
                    drawTitleBlock(canvas, title, artistName, 350, stubY, W - 170 - 350,
                            ink, faded, 38, 29);
                }
                break;
            }
            case SPOTLIGHT: {
                // Spotlight: everything centred under a round cover, like a stage.
                float cx = W / 2f;
                if (hasQuotes) {
                    drawRoundCover(canvas, art, cx, 230, 105);
                    drawTitleBlock(canvas, title, artistName, 120, 420, W - 240,
                            Color.WHITE, Color.argb(180, 255, 255, 255), 34, 27, true);
                    Paint rule = new Paint();
                    rule.setColor(Color.argb(90, 255, 255, 255));
                    canvas.drawRect(cx - 36, 490, cx + 36, 493, rule);
                    if (withQuoteText) drawQuoteText(canvas, design, style, quotes, translations, withCode);
                } else {
                    drawRoundCover(canvas, art, cx, 540, 330);
                    drawTitleBlock(canvas, title, artistName, 100, 1000, W - 200,
                            Color.WHITE, Color.argb(180, 255, 255, 255), 54, 36, true);
                }
                drawCodeAt(canvas, codeInk, codeSlot(design, hasQuotes));
                break;
            }
            default: {
                // Glass: a frosted pane over the backdrop, lit from above - a header row (cover,
                // song) over a hairline rule, the lyric, and the code in a footer of its own.
                RectF panel = new RectF(GLASS_PANEL, 150, W - GLASS_PANEL, H - 150);
                Paint glass = new Paint(Paint.ANTI_ALIAS_FLAG);
                glass.setShader(new LinearGradient(0, panel.top, 0, panel.bottom,
                        Color.argb(64, 255, 255, 255), Color.argb(20, 255, 255, 255), Shader.TileMode.CLAMP));
                canvas.drawRoundRect(panel, 56, 56, glass);
                Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
                edge.setStyle(Paint.Style.STROKE);
                edge.setStrokeWidth(2.5f);
                edge.setShader(new LinearGradient(0, panel.top, 0, panel.bottom,
                        Color.argb(130, 255, 255, 255), Color.argb(26, 255, 255, 255), Shader.TileMode.CLAMP));
                canvas.drawRoundRect(panel, 56, 56, edge);
                Paint rule = new Paint();
                rule.setColor(Color.argb(38, 255, 255, 255));
                if (hasQuotes) {
                    RectF thumb = new RectF(GLASS_IN, 205, GLASS_IN + 140, 345);
                    drawCover(canvas, art, thumb, 26);
                    Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
                    ring.setStyle(Paint.Style.STROKE);
                    ring.setStrokeWidth(2f);
                    ring.setColor(Color.argb(60, 255, 255, 255));
                    canvas.drawRoundRect(thumb, 26, 26, ring);
                    float titleLeft = thumb.right + 32;
                    drawTitleBlock(canvas, title, artistName, titleLeft, thumb.centerY(), W - GLASS_IN - titleLeft,
                            Color.WHITE, Color.argb(185, 255, 255, 255), 38, 30);
                    canvas.drawRect(GLASS_IN, GLASS_RULE_Y, W - GLASS_IN, GLASS_RULE_Y + 2, rule);
                    if (withQuoteText) drawQuoteText(canvas, design, style, quotes, translations, withCode);
                    if (withCode) canvas.drawRect(GLASS_IN, GLASS_FOOTER_Y - 82, W - GLASS_IN, GLASS_FOOTER_Y - 80, rule);
                } else {
                    // No quote: the cover fills the pane, the song name beneath it.
                    drawCover(canvas, art, new RectF(GLASS_IN, 210, W - GLASS_IN, 210 + W - 2 * GLASS_IN), 32);
                    drawTitleBlock(canvas, title, artistName, GLASS_IN, GLASS_FOOTER_Y,
                            W - 2 * GLASS_IN - (withCode ? CODE_W_POLAROID + 30 : 0),
                            Color.WHITE, Color.argb(185, 255, 255, 255), 44, 32);
                }
                drawCodeAt(canvas, codeInk, codeSlot(design, hasQuotes));
                break;
            }
        }
        drawBrandMark(canvas, design);
    }

    /**
     * A small "SpicyEx" in the top-right corner, kept clear of everything the designs put at the
     * bottom (title, Spotify Code): in the paper's margin for Polaroid, above the frosted panel for
     * Glass, in the free corner for Minimal and Classic. Sized from the card, not the screen.
     */
    private static void drawBrandMark(Canvas canvas, Design design) {
        TextPaint mark = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        mark.setTypeface(Typeface.DEFAULT_BOLD);
        mark.setTextSize(W * 0.024f);
        mark.setLetterSpacing(0.04f);
        mark.setTextAlign(Paint.Align.RIGHT);
        float right;
        float baseline;
        switch (design) {
            case POLAROID:
                // In the print's top border, over the photo's right edge.
                mark.setColor(Color.argb(120, 40, 40, 44));
                right = W - POLAROID_IN;
                baseline = POLAROID_PHOTO_TOP - 24;
                break;
            case GLASS:
                mark.setColor(Color.argb(130, 255, 255, 255));
                right = W - GLASS_PANEL - 20;
                baseline = 118;
                break;
            case CLASSIC:
                mark.setColor(Color.argb(130, 255, 255, 255));
                right = W - 90;
                baseline = 110;
                break;
            case TICKET:
                // Across from the ticket's header, in its ink.
                mark.setColor(Color.argb(130, 60, 56, 50));
                right = W - 170;
                baseline = 200;
                break;
            case POSTER:
                // Over the artwork: a soft shadow keeps it readable on a light cover.
                mark.setColor(Color.argb(190, 255, 255, 255));
                mark.setShadowLayer(8, 0, 2, Color.argb(120, 0, 0, 0));
                right = W - 90;
                baseline = 82;
                break;
            default:
                mark.setColor(Color.argb(130, 255, 255, 255));
                right = W - 90;
                baseline = 82;
                break;
        }
        canvas.drawText("SpicyEx", right, baseline, mark);
    }

    private static void drawFitted(Canvas canvas, Fitted f, TextBox box, float top) {
        float y = top;
        for (int i = 0; i < f.main.size(); i++) {
            StaticLayout l = f.main.get(i);
            canvas.save();
            canvas.translate(box.left, y);
            l.draw(canvas);
            canvas.restore();
            y += l.getHeight();
            StaticLayout sl = f.sub.get(i);
            if (sl != null) {
                float size = l.getPaint().getTextSize();
                y += size * 0.18f;
                canvas.save();
                canvas.translate(box.left, y);
                sl.draw(canvas);
                canvas.restore();
                y += sl.getHeight();
            }
            if (i < f.main.size() - 1) y += l.getPaint().getTextSize() * 0.5f;
        }
    }

    /**
     * Title (up to two lines, then ellipsized) over the artist (one line), the whole block
     * vertically centred on {@code centreY} so a wrapped title grows both ways around the anchor.
     */
    private static void drawTitleBlock(Canvas canvas, String title, String artist, float left,
                                       float centreY, float maxWidth, int color, int subColor,
                                       float titleSize, float artistSize) {
        drawTitleBlock(canvas, title, artist, left, centreY, maxWidth, color, subColor, titleSize,
                artistSize, false);
    }

    /** As above; {@code center} centres both lines within {@code maxWidth} from {@code left}. */
    private static void drawTitleBlock(Canvas canvas, String title, String artist, float left,
                                       float centreY, float maxWidth, int color, int subColor,
                                       float titleSize, float artistSize, boolean center) {
        Layout.Alignment align = center ? Layout.Alignment.ALIGN_CENTER : Layout.Alignment.ALIGN_NORMAL;
        int width = Math.max(10, Math.round(maxWidth));
        TextPaint t = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        t.setColor(color);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextSize(titleSize);
        TextPaint a = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        a.setColor(subColor);
        a.setTextSize(artistSize);
        String ti = title == null ? "" : title;
        StaticLayout titleLayout = StaticLayout.Builder.obtain(ti, 0, ti.length(), t, width)
                .setAlignment(align)
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setLineSpacing(0f, 1.04f)
                .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
                .build();
        String ar = artist == null ? "" : artist;
        StaticLayout artistLayout = StaticLayout.Builder.obtain(ar, 0, ar.length(), a, width)
                .setAlignment(align)
                .setMaxLines(1)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build();
        float gap = artistSize * 0.25f;
        float height = titleLayout.getHeight() + gap + artistLayout.getHeight();
        canvas.save();
        canvas.translate(left, centreY - height / 2f);
        titleLayout.draw(canvas);
        canvas.translate(0, titleLayout.getHeight() + gap);
        artistLayout.draw(canvas);
        canvas.restore();
    }

    /**
     * The track's Spotify Code. Fetched as white bars on black and screen-blended, so on a dark
     * card the black drops out and only the bars remain; on paper, black bars on white multiplied
     * in for the same effect the other way round.
     */
    private static void drawCodeAt(Canvas canvas, Bitmap code, CodeSlot slot) {
        if (code != null) drawSpotifyCode(canvas, code, slot.right, slot.centreY, slot.width, slot.paper);
    }

    private static void drawSpotifyCode(Canvas canvas, Bitmap code, float right, float centreY,
                                        float width, boolean onPaper) {
        if (code == null || code.getWidth() <= 0) return;
        float height = width * code.getHeight() / code.getWidth();
        float left = right - width;
        float top = centreY - height / 2f;
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        if (Build.VERSION.SDK_INT >= 29) {
            paint.setBlendMode(onPaper ? android.graphics.BlendMode.MULTIPLY
                    : android.graphics.BlendMode.SCREEN);
        }
        if (!onPaper) paint.setAlpha(230);
        canvas.drawBitmap(code, null, new RectF(left, top, left + width, top + height), paint);
    }

    private static void drawCover(Canvas canvas, Bitmap art, RectF frame, float radius) {
        if (art == null) return;
        Path clip = new Path();
        clip.addRoundRect(frame, radius, radius, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clip);
        canvas.drawBitmap(art, centreCrop(art, frame.width() / frame.height()), frame,
                new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
        canvas.restore();
    }

    /** The artwork in a circle, lifted off the card by a soft shadow and a thin light ring. */
    private static void drawRoundCover(Canvas canvas, Bitmap art, float cx, float cy, float r) {
        Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadow.setColor(Color.argb(110, 0, 0, 0));
        shadow.setMaskFilter(new android.graphics.BlurMaskFilter(r * 0.18f, android.graphics.BlurMaskFilter.Blur.NORMAL));
        canvas.drawCircle(cx, cy + r * 0.06f, r, shadow);
        if (art != null) {
            Path clip = new Path();
            clip.addCircle(cx, cy, r, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clip);
            canvas.drawBitmap(art, centreCrop(art, 1f), new RectF(cx - r, cy - r, cx + r, cy + r),
                    new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
            canvas.restore();
        } else {
            Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            fill.setColor(Color.argb(60, 255, 255, 255));
            canvas.drawCircle(cx, cy, r, fill);
        }
        Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Math.max(3f, r * 0.02f));
        ring.setColor(Color.argb(90, 255, 255, 255));
        canvas.drawCircle(cx, cy, r + ring.getStrokeWidth() * 2.5f, ring);
    }

    /** A black record with fine grooves and a light sheen, the artwork as its centre label. */
    private static void drawRecord(Canvas canvas, Bitmap art, float cx, float cy, float r) {
        Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadow.setColor(Color.argb(130, 0, 0, 0));
        shadow.setMaskFilter(new android.graphics.BlurMaskFilter(r * 0.12f, android.graphics.BlurMaskFilter.Blur.NORMAL));
        canvas.drawCircle(cx, cy + r * 0.05f, r, shadow);
        Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
        disc.setColor(Color.rgb(16, 16, 18));
        canvas.drawCircle(cx, cy, r, disc);
        Paint groove = new Paint(Paint.ANTI_ALIAS_FLAG);
        groove.setStyle(Paint.Style.STROKE);
        groove.setStrokeWidth(1.6f);
        groove.setColor(Color.argb(24, 255, 255, 255));
        for (float g = r * 0.44f; g < r * 0.97f; g += r * 0.034f) canvas.drawCircle(cx, cy, g, groove);
        Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
        int light = Color.argb(46, 255, 255, 255);
        sheen.setShader(new android.graphics.SweepGradient(cx, cy,
                new int[]{Color.TRANSPARENT, light, Color.TRANSPARENT, Color.TRANSPARENT, light,
                        Color.TRANSPARENT, Color.TRANSPARENT},
                new float[]{0.02f, 0.1f, 0.2f, 0.52f, 0.6f, 0.7f, 1f}));
        canvas.drawCircle(cx, cy, r, sheen);
        float label = r * 0.38f;
        if (art != null) {
            Path clip = new Path();
            clip.addCircle(cx, cy, label, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clip);
            canvas.drawBitmap(art, centreCrop(art, 1f), new RectF(cx - label, cy - label, cx + label, cy + label),
                    new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
            canvas.restore();
        } else {
            Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            fill.setColor(Color.rgb(200, 64, 60));
            canvas.drawCircle(cx, cy, label, fill);
        }
        Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(4);
        rim.setColor(Color.argb(70, 0, 0, 0));
        canvas.drawCircle(cx, cy, label, rim);
        Paint hole = new Paint(Paint.ANTI_ALIAS_FLAG);
        hole.setColor(Color.rgb(12, 12, 14));
        canvas.drawCircle(cx, cy, Math.max(6f, r * 0.03f), hole);
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private static android.graphics.Rect centreCrop(Bitmap bitmap, float aspect) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        if (w / (float) h > aspect) {
            int cw = Math.round(h * aspect);
            return new android.graphics.Rect((w - cw) / 2, 0, (w + cw) / 2, h);
        }
        int ch = Math.round(w / aspect);
        return new android.graphics.Rect(0, (h - ch) / 2, w, (h + ch) / 2);
    }

    private static void drawBackdrop(Canvas canvas, Backdrop backdrop, Bitmap art, Bitmap artist,
                                     Bitmap lyricsBg, float dim) {
        RectF full = new RectF(0, 0, W, H);
        if (backdrop == Backdrop.COLOR && lyricsBg != null) {
            // The lyrics screen's own animated background, as it looked when the sheet opened;
            // it is already dimmed the way the user set it, so only a light vignette goes on top.
            canvas.drawBitmap(lyricsBg, null, full, new Paint(Paint.FILTER_BITMAP_FLAG));
            Paint vignette = new Paint();
            vignette.setShader(new RadialGradient(W / 2f, H / 2f, H * 0.75f,
                    Color.TRANSPARENT, Color.argb(90, 0, 0, 0), Shader.TileMode.CLAMP));
            canvas.drawRect(full, vignette);
            return;
        }
        if (backdrop == Backdrop.COLOR || art == null && artist == null) {
            int[] gradient = extractGradient(art);
            Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
            bg.setShader(new LinearGradient(0, 0, W, H, gradient[0], gradient[1], Shader.TileMode.CLAMP));
            canvas.drawRect(full, bg);
        } else if (backdrop == Backdrop.ARTIST && artist != null) {
            // The photo itself, sharp (it used to be shrunk and re-enlarged, which read as a
            // blown-up thumbnail); darkened toward the bottom so the text stays legible.
            canvas.drawBitmap(artist, centreCrop(artist, W / (float) H), full,
                    new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG));
            Paint shade = new Paint();
            shade.setShader(new LinearGradient(0, 0, 0, H,
                    Color.argb(90, 0, 0, 0), Color.argb(215, 0, 0, 0), Shader.TileMode.CLAMP));
            canvas.drawRect(full, shade);
            return;
        } else {
            // The lyrics screen's look: artwork dissolved into soft colour fields.
            Bitmap blurred = cachedBlur(art != null ? art : artist);
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
            ColorMatrix saturate = new ColorMatrix();
            saturate.setSaturation(1.5f);
            p.setColorFilter(new ColorMatrixColorFilter(saturate));
            canvas.drawBitmap(blurred, centreCrop(blurred, W / (float) H), full, p);
        }
        Paint darken = new Paint();
        darken.setColor(Color.argb(Math.round(255 * (1f - dim) * 0.9f + 40), 0, 0, 0));
        canvas.drawRect(full, darken);
        Paint vignette = new Paint();
        vignette.setShader(new RadialGradient(W / 2f, H / 2f, H * 0.75f,
                Color.TRANSPARENT, Color.argb(120, 0, 0, 0), Shader.TileMode.CLAMP));
        canvas.drawRect(full, vignette);
    }

    private static Bitmap blurSource;
    private static Bitmap blurResult;

    /** The backdrop blur of the last image asked for; the same artwork is blurred once. */
    private static synchronized Bitmap cachedBlur(Bitmap source) {
        if (source != blurSource || blurResult == null) {
            blurResult = softBlur(source, 14);
            blurSource = source;
        }
        return blurResult;
    }

    /** Cheap, smooth blur: shrink to {@code tiny} px then scale back up twice (bilinear). */
    private static Bitmap softBlur(Bitmap source, int tiny) {
        int w = source.getWidth();
        int h = source.getHeight();
        float aspect = w / (float) h;
        int sw = aspect >= 1f ? tiny : Math.max(2, Math.round(tiny * aspect));
        int sh = aspect >= 1f ? Math.max(2, Math.round(tiny / aspect)) : tiny;
        Bitmap small = Bitmap.createScaledBitmap(source, sw, sh, true);
        Bitmap mid = Bitmap.createScaledBitmap(small, sw * 4, sh * 4, true);
        return Bitmap.createScaledBitmap(mid, Math.min(1080, sw * 16), Math.min(1350, sh * 16), true);
    }

    private static int[] extractGradient(Bitmap art) {
        if (art == null || art.getWidth() <= 0 || art.getHeight() <= 0) {
            return new int[]{Color.rgb(60, 60, 66), Color.rgb(14, 14, 16)};
        }
        Bitmap small = Bitmap.createScaledBitmap(art, 20, 20, true);
        long r = 0, g = 0, b = 0;
        int[] pixels = new int[400];
        small.getPixels(pixels, 0, 20, 0, 0, 20, 20);
        for (int pixel : pixels) {
            r += Color.red(pixel);
            g += Color.green(pixel);
            b += Color.blue(pixel);
        }
        float[] hsv = new float[3];
        Color.RGBToHSV((int) (r / 400), (int) (g / 400), (int) (b / 400), hsv);
        float[] top = hsv.clone();
        top[1] = Math.min(1f, top[1] * 1.3f + 0.1f);
        top[2] = Math.max(top[2], 0.5f);
        float[] bottom = hsv.clone();
        bottom[2] = Math.min(bottom[2] * 0.28f, 0.16f);
        return new int[]{Color.HSVToColor(top), Color.HSVToColor(bottom)};
    }

    // ---------------------------------------------------------------- Spotify Code

    private static Bitmap cachedCode(SpotifyTrack track, boolean paper) {
        synchronized (SPOTIFY_CODES) {
            return SPOTIFY_CODES.get(safe(track == null ? "" : track.uri) + (paper ? "|paper" : "|dark"));
        }
    }

    /**
     * A Spotify Code taken apart for the preview's animation: the code as ink on transparent
     * (white on the dark designs, at the card's screen-blend strength; black on paper, as the
     * card multiplies it), the logo's box, and each bar's column and extent.
     */
    static final class CodeArt {
        final Bitmap ink;
        /** In code pixels; null when the image could not be read as logo + bars. */
        final android.graphics.Rect logo;
        final float[] barLeft, barRight, barTop, barBottom;
        final float midY, tallest;
        final int color;

        CodeArt(Bitmap ink, android.graphics.Rect logo, float[] barLeft, float[] barRight,
                float[] barTop, float[] barBottom, int color) {
            this.ink = ink;
            this.logo = logo;
            this.barLeft = barLeft;
            this.barRight = barRight;
            this.barTop = barTop;
            this.barBottom = barBottom;
            this.color = color;
            this.midY = logo == null ? ink.getHeight() / 2f : logo.exactCenterY();
            float max = 0f;
            for (int i = 0; i < barTop.length; i++) max = Math.max(max, barBottom[i] - barTop[i]);
            this.tallest = max;
        }
    }

    private static final Map<Bitmap, CodeArt> CODE_ART = new java.util.WeakHashMap<>();

    private static CodeArt codeArt(Bitmap code, boolean paper) {
        synchronized (CODE_ART) {
            CodeArt known = CODE_ART.get(code);
            if (known != null) return known;
        }
        int w = code.getWidth();
        int h = code.getHeight();
        int[] px = new int[w * h];
        code.getPixels(px, 0, w, 0, 0, w, h);
        int rgb = paper ? 0x000000 : 0xFFFFFF;
        int[] colTop = new int[w];
        int[] colBottom = new int[w];
        java.util.Arrays.fill(colTop, -1);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = px[y * w + x];
                int lum = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000;
                int ink = paper ? 255 - lum : lum;
                px[y * w + x] = ((paper ? ink : ink * 230 / 255) << 24) | rgb;
                if (ink > 128) {
                    if (colTop[x] < 0) colTop[x] = y;
                    colBottom[x] = y + 1;
                }
            }
        }
        Bitmap inkBitmap = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        // Runs of inked columns: the first is the logo, the rest are the bars.
        List<int[]> runs = new ArrayList<>();
        for (int x = 0; x < w; x++) {
            if (colTop[x] < 0) continue;
            int start = x;
            int top = colTop[x];
            int bottom = colBottom[x];
            while (x + 1 < w && colTop[x + 1] >= 0) {
                x++;
                top = Math.min(top, colTop[x]);
                bottom = Math.max(bottom, colBottom[x]);
            }
            runs.add(new int[]{start, x + 1, top, bottom});
        }
        int color = paper ? Color.BLACK : Color.argb(230, 255, 255, 255);
        CodeArt art;
        if (runs.size() < 4) {
            art = new CodeArt(inkBitmap, null, new float[0], new float[0], new float[0], new float[0], color);
        } else {
            int[] logo = runs.get(0);
            int n = runs.size() - 1;
            float[] l = new float[n], r = new float[n], t = new float[n], b = new float[n];
            for (int i = 0; i < n; i++) {
                int[] run = runs.get(i + 1);
                l[i] = run[0];
                r[i] = run[1];
                t[i] = run[2];
                b[i] = run[3];
            }
            art = new CodeArt(inkBitmap, new android.graphics.Rect(logo[0], logo[2], logo[1], logo[3]),
                    l, r, t, b, color);
        }
        synchronized (CODE_ART) {
            CODE_ART.put(code, art);
        }
        return art;
    }

    /**
     * The code building itself on the preview card, like a track starting to play: the Spotify
     * logo fades up, then a play-head sweeps left to right and every bar it passes kicks up like
     * a level meter and springs back down. While the code is still downloading the sweep keeps
     * going round over low resting bars; the first sweep after it arrives lands each bar on its
     * real height, and the scannable code is left exactly as shared.
     */
    static final class CodeView extends View {
        private static final long LOGO_MS = 420L;
        /** The first sweep starts as the logo settles; later ones follow at this period. */
        private static final long FIRST_SWEEP = 300L;
        private static final long SWEEP_PERIOD = 1350L;
        /** Time for the play-head to cross from the first bar to the last. */
        private static final long SWEEP_SPAN = 640L;
        /** A bar's kick up to its peak, then a damped spring down to where it rests. */
        private static final long RISE_MS = 110L;
        private static final float SPRING_DECAY_MS = 140f;
        private static final float SPRING_PERIOD_MS = 75f;
        private static final long RING_OUT_MS = 900L;
        // The stand-in's geometry, in the code's own 400x100 units: the logo, then 23 bars.
        private static final int STAND_IN_BARS = 23;
        private final int color;
        private CodeArt art;
        private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint clear = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final Path arc = new Path();
        private final android.view.animation.DecelerateInterpolator soft =
                new android.view.animation.DecelerateInterpolator(1.8f);
        private android.animation.TimeAnimator animator;
        private boolean playing;
        /** Time into the animation. */
        private float elapsed;
        /** When the real code was there: 0 from the start, -1 while it is still coming. */
        private float artAt = -1f;

        CodeView(Context context, CodeArt art, boolean paper) {
            super(context);
            this.art = art;
            this.color = paper ? Color.BLACK : Color.argb(230, 255, 255, 255);
            barPaint.setColor(color);
            clear.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR));
            clear.setStyle(Paint.Style.STROKE);
            clear.setStrokeCap(Paint.Cap.ROUND);
        }

        /** The real code has arrived: the next sweep lands on it. */
        void setArt(CodeArt art) {
            if (art == null) return;
            this.art = art;
            if (playing) {
                artAt = elapsed;
            } else {
                invalidate();
            }
        }

        void play(long delay) {
            if (animator != null) animator.cancel();
            playing = true;
            elapsed = 0f;
            artAt = art != null ? 0f : -1f;
            invalidate();
            animator = new android.animation.TimeAnimator();
            animator.setTimeListener((animation, totalTime, deltaTime) -> {
                elapsed = Math.max(0f, totalTime - delay);
                int last = finalSweep();
                if (last >= 0 && elapsed >= hit(last, STAND_IN_BARS - 1) + RISE_MS + RING_OUT_MS) {
                    playing = false;
                    animation.cancel();
                }
                invalidate();
            });
            animator.start();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (animator != null) animator.cancel();
        }

        /** The sweep that lands on the real code: the first to start once it is here; -1 before. */
        private int finalSweep() {
            if (artAt < 0f) return -1;
            if (artAt <= FIRST_SWEEP) return 0;
            return (int) Math.ceil((artAt - FIRST_SWEEP) / SWEEP_PERIOD);
        }

        /** When sweep {@code k} reaches bar {@code i}. */
        private static float hit(int k, int i) {
            float along = Math.min(1f, i / (float) (STAND_IN_BARS - 1));
            return FIRST_SWEEP + k * SWEEP_PERIOD + along * SWEEP_SPAN;
        }

        private static float smooth(float edge0, float edge1, float x) {
            float t = Math.max(0f, Math.min(1f, (x - edge0) / (edge1 - edge0)));
            return t * t * (3f - 2f * t);
        }

        /** A fixed, varied peak per bar: a waveform's shape, not a random flicker. */
        private static float peakShare(int i) {
            double n = Math.sin(i * 12.9898 + 4.1) * 43758.5453;
            float r = (float) (n - Math.floor(n));
            return 0.55f + 0.45f * r;
        }

        /** Kick from {@code from} to {@code peak}, then a damped spring settling on {@code rest}. */
        private static float kick(float tau, float from, float peak, float rest) {
            if (tau < RISE_MS) {
                float t = tau / RISE_MS;
                return from + (peak - from) * (1f - (1f - t) * (1f - t));
            }
            float u = tau - RISE_MS;
            return rest + (peak - rest) * (float) (Math.exp(-u / SPRING_DECAY_MS) * Math.cos(u / SPRING_PERIOD_MS));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (getWidth() <= 0 || getHeight() <= 0) return;
            boolean real = art != null && art.logo != null;
            if (!playing) {
                if (art == null) return;
                bitmapPaint.setAlpha(255);
                rect.set(0, 0, getWidth(), getHeight());
                canvas.drawBitmap(art.ink, null, rect, bitmapPaint);
                return;
            }
            if (art != null && !real) {
                // An image that could not be read as bars: it simply fades in.
                bitmapPaint.setAlpha(Math.round(255 * smooth(0f, 400f, elapsed - Math.max(0f, artAt))));
                rect.set(0, 0, getWidth(), getHeight());
                canvas.drawBitmap(art.ink, null, rect, bitmapPaint);
                return;
            }
            float unit = getWidth() / 400f;
            float sx = real ? getWidth() / (float) art.ink.getWidth() : 0f;
            float sy = real ? getHeight() / (float) art.ink.getHeight() : 0f;
            float standInMid = 50f * unit;

            // The logo fades up from a little smaller; the stand-in gives way to the real mark.
            float lt = Math.min(1f, elapsed / LOGO_MS);
            if (lt > 0f) {
                float scale = 0.72f + 0.28f * soft.getInterpolation(lt);
                int alpha = Math.round(255 * smooth(0f, 0.6f, lt));
                float cx = real ? art.logo.exactCenterX() * sx : 50f * unit;
                float cy = real ? art.logo.exactCenterY() * sy : standInMid;
                canvas.save();
                canvas.scale(scale, scale, cx, cy);
                if (real) {
                    bitmapPaint.setAlpha(alpha);
                    rect.set(art.logo.left * sx, art.logo.top * sy, art.logo.right * sx, art.logo.bottom * sy);
                    canvas.drawBitmap(art.ink, art.logo, rect, bitmapPaint);
                } else {
                    drawStandInLogo(canvas, cx, cy, 31f * unit, alpha);
                }
                canvas.restore();
            }

            // The bars, each driven by the latest sweep to have reached it.
            int last = finalSweep();
            float loud = real ? art.tallest * sy : 60f * unit;
            float resting = loud * 0.2f;
            int realBars = real ? art.barTop.length : 0;
            int bars = Math.max(STAND_IN_BARS, realBars);
            int baseAlpha = Color.alpha(color);
            for (int i = 0; i < bars; i++) {
                int k = (int) Math.floor((elapsed - hit(0, i)) / SWEEP_PERIOD);
                if (elapsed < hit(0, i)) continue;
                if (last >= 0) k = Math.min(k, last);
                float tau = elapsed - hit(k, i);
                boolean landing = k == last;
                float standInLeft = (100f + Math.min(i, STAND_IN_BARS - 1) * 12.83f) * unit;
                float standInWidth = 6f * unit;
                float peak = Math.min(getHeight(), loud * peakShare(i) * 1.1f);
                float from = k == 0 ? 0f : resting;
                float left, right, height, centre;
                if (landing && i < realBars) {
                    float top = art.barTop[i] * sy;
                    float bottom = art.barBottom[i] * sy;
                    float realLeft = art.barLeft[i] * sx;
                    float realRight = art.barRight[i] * sx;
                    // Bars that rested as the stand-in (the code came late) glide to their place.
                    float glide = k == 0 ? 1f : smooth(0f, RISE_MS + 260f, tau);
                    left = standInLeft + (realLeft - standInLeft) * glide;
                    right = left + (standInWidth + (realRight - realLeft - standInWidth) * glide);
                    float target = bottom - top;
                    height = kick(tau, from, Math.max(peak, target * 1.15f), target);
                    centre = standInMid + ((top + bottom) / 2f - standInMid) * glide;
                } else if (i < STAND_IN_BARS) {
                    // The stand-in, resting low between sweeps; one the real code lacks bows out.
                    left = standInLeft;
                    right = left + standInWidth;
                    height = kick(tau, from, peak, landing ? 0f : resting);
                    centre = standInMid;
                    if (landing && tau > RISE_MS + 300f) continue;
                } else {
                    continue;
                }
                float width = right - left;
                height = Math.max(width, height);
                rect.set(left, centre - height / 2f, right, centre + height / 2f);
                float appear = k == 0 ? Math.min(1f, tau / 90f) : 1f;
                barPaint.setAlpha(Math.round(baseAlpha * appear));
                canvas.drawRoundRect(rect, width / 2f, width / 2f, barPaint);
            }
        }

        /** Spotify's mark before the real code is here: a disc with its three arcs cut out. */
        private void drawStandInLogo(Canvas canvas, float cx, float cy, float r, int alpha) {
            int layer = canvas.saveLayer(cx - r - 2, cy - r - 2, cx + r + 2, cy + r + 2, null);
            barPaint.setAlpha(Math.round(Color.alpha(color) * alpha / 255f));
            canvas.drawCircle(cx, cy, r, barPaint);
            float[] ys = {-0.3f, 0.02f, 0.3f};
            float[] widths = {1.18f, 0.98f, 0.78f};
            float[] strokes = {0.17f, 0.14f, 0.12f};
            for (int k = 0; k < 3; k++) {
                float y = cy + ys[k] * r;
                float half = widths[k] * r / 2f;
                arc.reset();
                arc.moveTo(cx - half, y + 0.1f * r);
                arc.quadTo(cx, y - 0.16f * r, cx + half, y + 0.02f * r);
                clear.setStrokeWidth(strokes[k] * r);
                canvas.drawPath(arc, clear);
            }
            canvas.restoreToCount(layer);
        }
    }

    /**
     * Stand-in for a Spotify Code still downloading: the real one's 4:1 shape, fully
     * transparent. The card reserves the code's place with it and the code view dances in that
     * place until the real code arrives.
     */
    private static final Bitmap PENDING_CODE = Bitmap.createBitmap(4, 1, Bitmap.Config.ARGB_8888);
    /** Network work off the render thread: a slow download never holds up a card. */
    private static final ExecutorService NETWORK = Executors.newSingleThreadExecutor();

    /** The code a design is laid out with: the real one, the stand-in while it loads, or none. */
    private Bitmap layoutCode(Design d) {
        if (!spotifyCode) return null;
        Bitmap real = cachedCode(track, onPaper(d));
        if (real != null) return real;
        return codeFailed ? null : PENDING_CODE;
    }

    /** Both variants from Spotify's own scannables endpoint, then re-render. */
    private void fetchSpotifyCode() {
        SpotifyTrack t = track;
        String uri = safe(t == null ? "" : t.uri);
        if (uri.isEmpty() || (cachedCode(t, false) != null && cachedCode(t, true) != null)) return;
        NETWORK.execute(() -> {
            boolean ok = true;
            for (boolean paper : new boolean[]{false, true}) {
                if (cachedCode(t, paper) != null) continue;
                String url = "https://scannables.scdn.co/uri/plain/png/"
                        + (paper ? "FFFFFF/black" : "000000/white") + "/640/" + uri;
                try (Response response = NativeRuntime.HTTP.newCall(
                        new Request.Builder().url(url).get().build()).execute()) {
                    Bitmap image = response.isSuccessful() && response.body() != null
                            ? BitmapFactory.decodeStream(response.body().byteStream()) : null;
                    if (image == null) {
                        ok = false;
                        continue;
                    }
                    synchronized (SPOTIFY_CODES) {
                        SPOTIFY_CODES.put(uri + (paper ? "|paper" : "|dark"), image);
                        while (SPOTIFY_CODES.size() > 8) {
                            SPOTIFY_CODES.remove(SPOTIFY_CODES.keySet().iterator().next());
                        }
                    }
                } catch (Throwable error) {
                    ok = false;
                    XpLog.log(TAG + " spotify code failed: " + error.getClass().getSimpleName());
                }
            }
            boolean fetched = ok;
            // Taken apart here, off the main thread, for the code view to settle into.
            CodeArt darkArt = null;
            CodeArt paperArt = null;
            try {
                Bitmap dark = cachedCode(t, false);
                Bitmap paper = cachedCode(t, true);
                if (dark != null) darkArt = codeArt(dark, false);
                if (paper != null) paperArt = codeArt(paper, true);
            } catch (Throwable error) {
                XpLog.log(TAG + " spotify code parse failed: " + error);
            }
            CodeArt darkReady = darkArt;
            CodeArt paperReady = paperArt;
            main.post(() -> {
                if (track != t || overlay == null) return;
                if (!fetched) {
                    Toast.makeText(activity, s("code_unavailable", "Spotify Code unavailable"),
                            Toast.LENGTH_SHORT).show();
                }
                if (!spotifyCode) return;
                boolean paper = onPaper(design);
                Bitmap real = cachedCode(t, paper);
                CodeArt art = paper ? paperReady : darkReady;
                if (real != null && art != null && currentCode == PENDING_CODE && currentCodeView != null) {
                    // The stand-in is dancing in the code's place: it settles into the real code
                    // where it is; the card itself does not change.
                    currentCode = real;
                    currentCodeView.setArt(art);
                    return;
                }
                if (real == null) codeFailed = true;
                render(Transition.FADE);
            });
        });
    }

    // ---------------------------------------------------------------- artist photo

    private static Bitmap cachedArtist(SpotifyTrack track) {
        synchronized (ARTIST_IMAGES) {
            return ARTIST_IMAGES.get(trackIdFromUri(track == null ? "" : track.uri));
        }
    }

    /**
     * The track's first artist, then their photo. The artist comes from Spotify's own track
     * metadata ({@code artist_uri}, read with the player state); the photo from Spotify's public
     * oEmbed endpoint, which needs no token. The Web API route (with Spotify's captured token)
     * stays as a fallback when the metadata had no artist. Every step logs, so a missing photo
     * says why.
     */
    private void fetchArtistImage() {
        SpotifyTrack t = track;
        String trackId = trackIdFromUri(t == null ? "" : t.uri);
        if (trackId.isEmpty()) return;
        String trackUri = safe(t.uri);
        String metaArtist = trackUri.equals(com.eza.spicyex.References.lastTrackUri)
                ? com.eza.spicyex.References.lastArtistUri : "";
        NETWORK.execute(() -> {
            Bitmap image = null;
            try {
                String artistId = metaArtist.startsWith("spotify:artist:")
                        ? metaArtist.substring("spotify:artist:".length()) : null;
                if (artistId == null) {
                    XpLog.log(TAG + " artist: no artist_uri in metadata, trying Web API");
                    SpotifyTokenState.Authorized auth = SpotifyTokenStore.authorization(System.currentTimeMillis());
                    if (auth == null) {
                        XpLog.log(TAG + " artist: no Web API token either");
                    } else {
                        org.json.JSONObject trackJson = getJson(
                                "https://api.spotify.com/v1/tracks/" + trackId, auth.token());
                        artistId = trackJson == null ? null
                                : trackJson.getJSONArray("artists").getJSONObject(0).optString("id", null);
                    }
                }
                if (artistId != null) {
                    org.json.JSONObject embed = getJson("https://open.spotify.com/oembed?url="
                            + Uri.encode("https://open.spotify.com/artist/" + artistId), null);
                    String url = embed == null ? null : embed.optString("thumbnail_url", null);
                    // oEmbed hands out a small rendition; ask the CDN for the largest one.
                    if (url != null) image = downloadLargest(url);
                    // Still small (an artist without its own large photo, or an image kind the
                    // size codes don't cover): the Web API lists every size - take the biggest.
                    if (image == null || image.getWidth() < 600) {
                        Bitmap viaApi = artistImageFromWebApi(artistId);
                        if (viaApi != null && (image == null || viaApi.getWidth() > image.getWidth())) {
                            image = viaApi;
                        }
                    }
                    XpLog.log(TAG + " artist: id=" + artistId + " image="
                            + (image == null ? "none" : image.getWidth() + "px"));
                }
            } catch (Throwable error) {
                XpLog.log(TAG + " artist image failed: " + error);
            }
            Bitmap result = image;
            if (result != null) {
                synchronized (ARTIST_IMAGES) {
                    ARTIST_IMAGES.put(trackId, result);
                    while (ARTIST_IMAGES.size() > 4) {
                        ARTIST_IMAGES.remove(ARTIST_IMAGES.keySet().iterator().next());
                    }
                }
            }
            main.post(() -> {
                if (track != t || overlay == null) return;
                if (result == null) {
                    Toast.makeText(activity, s("artist_unavailable", "Artist photo unavailable"),
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                artistImage = result;
                if (backdrop == Backdrop.ARTIST) {
                    render(Transition.FADE);
                    renderThumbs();
                }
            });
        });
    }

    /**
     * Spotify CDN image ids start with a size code. oEmbed returns the 320px artist rendition, but
     * some artists come back as the 160px one, or - without a photo of their own - as a 300px or
     * 64px album cover. Each is swapped for the largest code of its kind; the original is kept
     * when the larger one does not exist.
     */
    private static Bitmap downloadLargest(String url) {
        String[][] upgrades = {
                {"ab67616100005174", "ab6761610000e5eb"},
                {"ab6761610000f178", "ab6761610000e5eb"},
                {"ab67616d00001e02", "ab67616d0000b273"},
                {"ab67616d00004851", "ab67616d0000b273"},
        };
        for (String[] pair : upgrades) {
            if (url.contains(pair[0])) {
                Bitmap big = downloadBitmap(url.replace(pair[0], pair[1]));
                if (big != null) return big;
            }
        }
        return downloadBitmap(url);
    }

    private static Bitmap downloadBitmap(String url) {
        try (Response response = NativeRuntime.HTTP.newCall(
                new Request.Builder().url(url).get().build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) return null;
            return BitmapFactory.decodeStream(response.body().byteStream());
        } catch (Throwable error) {
            return null;
        }
    }

    /** The artist's largest image from the Web API, with Spotify's own captured token. */
    private static Bitmap artistImageFromWebApi(String artistId) {
        try {
            SpotifyTokenState.Authorized auth = SpotifyTokenStore.authorization(System.currentTimeMillis());
            if (auth == null) return null;
            org.json.JSONObject artist = getJson("https://api.spotify.com/v1/artists/" + artistId, auth.token());
            org.json.JSONArray images = artist == null ? null : artist.optJSONArray("images");
            if (images == null || images.length() == 0) return null;
            String best = null;
            int bestWidth = -1;
            for (int i = 0; i < images.length(); i++) {
                org.json.JSONObject img = images.getJSONObject(i);
                int w = img.optInt("width", 0);
                if (w > bestWidth) {
                    bestWidth = w;
                    best = img.optString("url", null);
                }
            }
            return best == null ? null : downloadLargest(best);
        } catch (Throwable error) {
            return null;
        }
    }

    private static org.json.JSONObject getJson(String url, String token) throws Exception {
        Request.Builder builder = new Request.Builder().url(url).get();
        if (token != null) builder.header("Authorization", "Bearer " + token);
        try (Response response = NativeRuntime.HTTP.newCall(builder.build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                XpLog.log(TAG + " http " + response.code() + " " + url.replaceAll("\\?.*", ""));
                return null;
            }
            return new org.json.JSONObject(response.body().string());
        }
    }

    // ---------------------------------------------------------------- share / save

    /** The shared card, drawn once however often it is shared or saved. */
    private static final class CardRecipe {
        interface Maker {
            Bitmap make(boolean rounded) throws Exception;
        }

        private final Maker make;
        private Bitmap square;
        private Bitmap rounded;

        CardRecipe(Maker make) {
            this.make = make;
        }

        /** The image shared, saved and sent to chats: square corners, the backdrop to the edge. */
        synchronized Bitmap get() throws Exception {
            if (square == null) square = make.make(false);
            return square;
        }

        /** A story sticker: the card's own rounded shape, floating on the story's background. */
        synchronized Bitmap sticker() throws Exception {
            if (rounded == null) rounded = make.make(true);
            return rounded;
        }
    }

    private void saveOnly() {
        CardRecipe recipe = currentRecipe;
        if (recipe == null) return;
        RENDER.execute(() -> {
            Uri uri = null;
            try {
                uri = saveToGallery(recipe.get());
            } catch (Throwable error) {
                XpLog.log(TAG + " save failed: " + error);
            }
            Uri saved = uri;
            main.post(() -> Toast.makeText(activity, saved != null ? s("saved", "Saved to Pictures/SpicyEx")
                    : s("save_failed", "Could not save the card"), Toast.LENGTH_SHORT).show());
        });
    }

    /** The system share sheet with every app ({@code target} null), or straight to one app. */
    private void shareCard(android.content.ComponentName target) {
        CardRecipe recipe = currentRecipe;
        SpotifyTrack t = track;
        if (t == null) return;
        List<String> quotes = collectQuotes(selection());
        RENDER.execute(() -> {
            try {
                Bitmap card = recipe == null ? null : recipe.get();
                // Behind Spotify's own share provider, so a share leaves nothing in the gallery.
                Uri cardUri = card == null ? null : shareableUri(card);
                if (card != null && cardUri == null) cardUri = saveToGallery(card);
                String link = webLink(t);
                if (isBlank(link)) link = safe(t.uri);
                String quote = TextUtils.join("\n", quotes);
                String text = isBlank(quote)
                        ? safe(t.title) + " - " + safe(t.artist)
                        : "\"" + quote + "\"\n" + safe(t.title) + " - " + safe(t.artist);
                Intent send = new Intent(Intent.ACTION_SEND);
                if (cardUri != null) {
                    send.setType("image/png");
                    send.putExtra(Intent.EXTRA_STREAM, cardUri);
                    // The clip carries the read grant (through the chooser too).
                    send.setClipData(android.content.ClipData.newRawUri("", cardUri));
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } else {
                    send.setType("text/plain");
                }
                send.putExtra(Intent.EXTRA_TEXT, text + "\n" + link);
                Intent launch;
                if (target != null) {
                    send.setComponent(target);
                    if (cardUri != null) {
                        activity.grantUriPermission(target.getPackageName(), cardUri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    }
                    launch = send;
                } else {
                    launch = Intent.createChooser(send, s("chooser", "Share lyric"));
                }
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                main.post(() -> {
                    try {
                        activity.startActivity(launch);
                        dismiss();
                    } catch (Throwable error) {
                        XpLog.log(TAG + " share failed: " + error);
                        Toast.makeText(activity, s("story_failed", "Could not open the app"),
                                Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Throwable error) {
                XpLog.log(TAG + " share failed: " + error);
                main.post(() -> Toast.makeText(activity, s("story_failed", "Could not open the app"),
                        Toast.LENGTH_SHORT).show());
            }
        });
    }

    /**
     * Apps shared to directly, in this order when installed - chats first, as in Spotify's own
     * sheet. Instagram is here for its Direct messages (its story has its own button).
     */
    private static final String[] DIRECT_APPS = {
            "com.kakao.talk", "com.instagram.android", "com.whatsapp", "org.telegram.messenger",
            "jp.naver.line.android", "com.facebook.orca", "com.discord", "com.twitter.android",
            "com.instagram.barcelona", "com.snapchat.android", "com.google.android.apps.messaging",
            "com.samsung.android.messaging",
    };
    private static final int MAX_DIRECT = 6;

    /** One installed app's share activity, with its icon and name. */
    private static final class DirectTarget {
        final android.content.ComponentName component;
        final android.graphics.drawable.Drawable icon;
        final String label;

        DirectTarget(android.content.ComponentName component, android.graphics.drawable.Drawable icon,
                     String label) {
            this.component = component;
            this.icon = icon;
            this.label = label;
        }
    }

    /** Off the main thread: the package query and icon loads are too slow for opening. */
    private List<DirectTarget> findDirectTargets() {
        List<DirectTarget> out = new ArrayList<>();
        android.content.pm.PackageManager pm = activity.getPackageManager();
        Intent probe = new Intent(Intent.ACTION_SEND);
        probe.setType("image/png");
        List<android.content.pm.ResolveInfo> all;
        try {
            all = pm.queryIntentActivities(probe, 0);
        } catch (Throwable error) {
            return out;
        }
        for (String pkg : DIRECT_APPS) {
            if (out.size() >= MAX_DIRECT) break;
            android.content.pm.ResolveInfo pick = null;
            for (android.content.pm.ResolveInfo info : all) {
                if (info.activityInfo == null || !pkg.equals(info.activityInfo.packageName)) continue;
                String name = info.activityInfo.name == null ? "" : info.activityInfo.name.toLowerCase(java.util.Locale.ROOT);
                if (pkg.equals("com.instagram.android")) {
                    // Direct messages only: the feed composer and the story are not a chat.
                    if (name.contains("direct")) pick = info;
                    continue;
                }
                pick = info;
                break;
            }
            if (pick == null) continue;
            try {
                String label = pkg.equals("com.instagram.android")
                        ? String.valueOf(pick.loadLabel(pm))
                        : String.valueOf(pick.activityInfo.applicationInfo.loadLabel(pm));
                out.add(new DirectTarget(new android.content.ComponentName(pick.activityInfo.packageName,
                        pick.activityInfo.name), pick.loadIcon(pm), label));
            } catch (Throwable ignored) {
                // An app that will not describe itself is left out.
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- stories

    /**
     * Story targets, sent the way Spotify's own share sheet sends them (read from its
     * InstagramStories / FacebookStories destinations): the card goes in as a movable sticker
     * over a two-colour background, with the song's link as the story's attribution and, for
     * Instagram, the track as the music sticker's entity. Running inside Spotify, the module
     * signs the request with Spotify's own Facebook App ID - the one those apps check the
     * attribution link against.
     */
    enum StoryTarget {
        INSTAGRAM("com.instagram.android", "com.instagram.share.ADD_TO_STORY"),
        FACEBOOK("com.facebook.katana", "com.facebook.stories.ADD_TO_STORY");

        final String pkg;
        final String action;

        StoryTarget(String pkg, String action) {
            this.pkg = pkg;
            this.action = action;
        }
    }

    private Intent storyIntent(StoryTarget target) {
        Intent intent = new Intent(target.action);
        intent.setPackage(target.pkg);
        intent.setType("image/png");
        return intent;
    }

    private boolean canShareTo(StoryTarget target) {
        try {
            return activity.getPackageManager().resolveActivity(storyIntent(target), 0) != null;
        } catch (Throwable error) {
            return false;
        }
    }

    private void shareToStory(StoryTarget target) {
        CardRecipe recipe = currentRecipe;
        SpotifyTrack t = track;
        Bitmap art = artwork;
        if (recipe == null || t == null) return;
        RENDER.execute(() -> {
            try {
                Bitmap card = recipe.sticker();
                Uri sticker = shareableUri(card);
                if (sticker == null) sticker = saveToGallery(card);
                if (sticker == null) throw new IllegalStateException("no uri for the card");
                int[] colors = extractGradient(art);
                Intent intent = storyIntent(target);
                intent.putExtra("interactive_asset_uri", sticker);
                intent.putExtra("top_background_color", hexColor(colors[0]));
                intent.putExtra("bottom_background_color", hexColor(colors[1]));
                String link = webLink(t);
                if (!isBlank(link)) intent.putExtra("content_url", link);
                String appId = facebookAppId();
                if (target == StoryTarget.INSTAGRAM) {
                    intent.putExtra("source_application", appId);
                    String uri = safe(t.uri);
                    if (uri.startsWith("spotify:track:")) {
                        intent.putExtra("com.instagram.sharedSticker.entityURI", uri);
                    }
                } else {
                    intent.putExtra("com.facebook.platform.extra.APPLICATION_ID", appId);
                }
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                activity.grantUriPermission(target.pkg, sticker, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                main.post(() -> {
                    try {
                        activity.startActivity(intent);
                        dismiss();
                    } catch (Throwable error) {
                        XpLog.log(TAG + " story share failed: " + error);
                        Toast.makeText(activity, s("story_failed", "Could not open the app"),
                                Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Throwable error) {
                XpLog.log(TAG + " story share failed: " + error);
                main.post(() -> Toast.makeText(activity, s("story_failed", "Could not open the app"),
                        Toast.LENGTH_SHORT).show());
            }
        });
    }

    private static String hexColor(int color) {
        return String.format(java.util.Locale.ROOT, "#%06X", color & 0xFFFFFF);
    }

    /** Spotify's own Facebook App ID, from its manifest - as its share destinations read it. */
    private String facebookAppId() {
        try {
            android.content.pm.ApplicationInfo info = activity.getPackageManager().getApplicationInfo(
                    activity.getPackageName(), android.content.pm.PackageManager.GET_META_DATA);
            Object id = info.metaData == null ? null : info.metaData.get("com.facebook.sdk.ApplicationId");
            return id == null ? "" : String.valueOf(id);
        } catch (Throwable error) {
            return "";
        }
    }

    /**
     * The card as a file behind Spotify's own share FileProvider ({@code <package>.share}, cache
     * path {@code shareablesdir/}), so a story share leaves nothing in the gallery. Null when the
     * provider is not there (another Spotify build), and the caller falls back to the gallery.
     */
    private Uri shareableUri(Bitmap card) {
        String authority = activity.getPackageName() + ".share";
        if (activity.getPackageManager().resolveContentProvider(authority, 0) == null) return null;
        java.io.File dir = new java.io.File(activity.getCacheDir(), "shareablesdir");
        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        java.io.File[] old = dir.listFiles((d, name) -> name.startsWith("spicyex_card_"));
        if (old != null) for (java.io.File f : old) f.delete();
        String name = "spicyex_card_" + System.currentTimeMillis() + ".png";
        java.io.File file = new java.io.File(dir, name);
        try (OutputStream out = new java.io.FileOutputStream(file)) {
            card.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (Throwable error) {
            XpLog.log(TAG + " shareable write failed: " + error);
            return null;
        }
        // androidx FileProvider's path form: content://<authority>/<path name>/<file>.
        return new Uri.Builder().scheme("content").authority(authority)
                .appendPath("shareables-cache").appendPath(name).build();
    }

    private void copyLink() {
        String link = webLink(track);
        if (isBlank(link)) return;
        android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Spotify", link));
        if (Build.VERSION.SDK_INT < 33) {
            Toast.makeText(activity, s("link_copied", "Link copied"), Toast.LENGTH_SHORT).show();
        }
    }

    private Uri saveToGallery(Bitmap card) {
        Context context = activity.getApplicationContext();
        String name = "spicyex_lyric_" + System.currentTimeMillis() + ".png";
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SpicyEx");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
        }
        Uri collection = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        Uri item = context.getContentResolver().insert(collection, values);
        if (item == null) return null;
        try (OutputStream out = context.getContentResolver().openOutputStream(item)) {
            if (out == null) return null;
            card.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (Throwable error) {
            XpLog.log(TAG + " write failed: " + error);
            return null;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            context.getContentResolver().update(item, values, null, null);
        }
        return item;
    }

    private static <E extends Enum<E>> E enumOr(Class<E> type, String name, E fallback) {
        if (name == null) return fallback;
        try {
            return Enum.valueOf(type, name);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
