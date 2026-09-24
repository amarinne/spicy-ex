package com.eza.spicyex.hooks;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

import com.eza.spicyex.xposed.XpLog;

/**
 * Soft instrumental music played in place of an ad. Synthesised on the fly - nothing is bundled or
 * downloaded, so there is no licence to worry about and no APK weight. See {@link Synth} for the
 * music itself and {@link Theme} for the styles it can play in.
 *
 * <p>Starting and ending belong to the player rather than its caller. {@link #fadeIn()} ramps the
 * gain up. {@link #fadeOutAndStop()} does not just fade: it asks the band for an ending - a
 * dominant-to-tonic cadence that rings out - and closes once that has played, so an ad break
 * ends on a resolved chord instead of being cut. A fade in during that ending turns it around and
 * the piece carries on, so back-to-back ads never restart the music.
 */
final class AdMusicPlayer {
    private static final String TAG = "[SpicyAdMusic]";
    private static final int SAMPLE_RATE = 44100;
    private static final int BLOCK_FRAMES = 1024;
    private static final float FADE_IN_SEC = 1.6f;
    private static final float FADE_OUT_SEC = 0.7f;
    /** Longest the ending may take before the player fades regardless. */
    // Short: the song is already playing under the ending, and a long cadence over it clashed.
    private static final float OUTRO_MAX_SEC = 2f;
    private static final float MASTER = 1.3f;

    private static final Object OWN_LOCK = new Object();
    /** Every track a player of ours is writing - the ad player and a settings preview can overlap. */
    private static final java.util.Set<AudioTrack> OWN_TRACKS =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    private volatile boolean running;
    private volatile float targetGain;
    private volatile boolean stopWhenSilent;
    private volatile String theme = Theme.RANDOM;
    private Thread thread;

    /** True for a track one of these players writes to, which the ad mute and analysis ignore. */
    static boolean isOwnTrack(AudioTrack track) {
        synchronized (OWN_LOCK) {
            return track != null && OWN_TRACKS.contains(track);
        }
    }

    /** Style for the next piece (a piece already playing keeps its own). */
    void setTheme(String value) {
        theme = value == null ? Theme.RANDOM : value;
    }

    synchronized void fadeIn() {
        stopWhenSilent = false;
        targetGain = 1f;
        if (running) return;
        running = true;
        thread = new Thread(this::render, "SpicyAdMusic");
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
    }

    /** Fades to silence and keeps the stream open, for a pause during the ad. */
    void hold() {
        targetGain = 0f;
    }

    /** Plays the ending, then stops. */
    void fadeOutAndStop() {
        stopWhenSilent = true;
    }

    boolean isPlaying() {
        return running && !stopWhenSilent;
    }

    private void render() {
        AudioTrack track = null;
        try {
            int minBytes = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build())
                    .setBufferSizeInBytes(Math.max(minBytes, BLOCK_FRAMES * 4 * 4))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            synchronized (OWN_LOCK) {
                OWN_TRACKS.add(track);
            }
            track.play();
            Synth synth = new Synth(Theme.resolve(theme, new java.util.Random()));
            short[] block = new short[BLOCK_FRAMES * 2];
            float gain = 0f;
            float fadeInStep = 1f / (FADE_IN_SEC * SAMPLE_RATE);
            float fadeOutStep = 1f / (FADE_OUT_SEC * SAMPLE_RATE);
            long outroSamples = 0;
            while (running) {
                boolean ending = stopWhenSilent;
                if (ending && !synth.inOutro()) synth.beginOutro();
                if (!ending && synth.inOutro()) synth.cancelOutro();
                float target = targetGain;
                if (ending) {
                    outroSamples += BLOCK_FRAMES;
                    boolean over = synth.outroDone() || outroSamples > OUTRO_MAX_SEC * SAMPLE_RATE;
                    target = over ? 0f : Math.max(target, 0f);
                    if (over) targetGain = 0f;
                } else {
                    outroSamples = 0;
                }
                for (int i = 0; i < BLOCK_FRAMES; i++) {
                    if (gain < target) gain = Math.min(target, gain + fadeInStep);
                    else if (gain > target) gain = Math.max(target, gain - fadeOutStep);
                    // Equal-power shaped fade: a linear gain ramp sounds like it lingers at the top.
                    float g = MASTER * gain * gain * (3f - 2f * gain);
                    synth.next();
                    block[2 * i] = toPcm(synth.left * g);
                    block[2 * i + 1] = toPcm(synth.right * g);
                }
                track.write(block, 0, block.length);
                if (stopWhenSilent && gain <= 0f) break;
            }
        } catch (Throwable t) {
            XpLog.log(TAG + " render failed: " + t);
        } finally {
            synchronized (OWN_LOCK) {
                OWN_TRACKS.remove(track);
            }
            if (track != null) {
                try {
                    track.stop();
                } catch (Throwable ignored) {
                }
                track.release();
            }
            synchronized (this) {
                running = false;
                thread = null;
                // A fade in that arrived while this loop was finishing.
                if (!stopWhenSilent && targetGain > 0f) {
                    running = true;
                    thread = new Thread(this::render, "SpicyAdMusic");
                    thread.start();
                }
            }
        }
    }

    /** Transparent below 0.8, then rounds off: loud enough to stand in for a track, never clips. */
    private static short toPcm(float value) {
        float a = Math.abs(value);
        if (a > 0.8f) {
            float over = (a - 0.8f) / 0.2f;
            a = 0.8f + 0.2f * over / (1f + over);
            value = Math.signum(value) * a;
        }
        return (short) Math.round(Math.max(-1f, Math.min(1f, value)) * 32767f);
    }

    /** A style: tempo, harmony, and which instruments and rhythms the band uses. */
    static final class Theme {
        static final String LOFI = "Lofi";
        static final String CAFE_JAZZ = "Cafe jazz";
        static final String BOSSA_NOVA = "Bossa nova";
        static final String AMBIENT = "Ambient";
        static final String RANDOM = "Random";

        enum Drums { LOFI, JAZZ, BOSSA, NONE }
        enum Keys { ELECTRIC_PIANO, PIANO, GUITAR }
        enum Bass { ROOTS, WALKING, BOSSA, SUB }

        final String name;
        final float bpmMin;
        final float bpmMax;
        final float swing;
        final Drums drums;
        final Keys keys;
        final Bass bass;
        /** Four-bar progressions: {semitones above the key root, chord quality index}. */
        final int[][][] progressions;
        final int barsPerChord;
        final float padLevel;
        final float leadLevel;
        final float reverbMix;
        final float wow;
        final float crackle;
        final boolean fluteLead;

        Theme(String name, float bpmMin, float bpmMax, float swing, Drums drums, Keys keys,
              Bass bass, int[][][] progressions, int barsPerChord, float padLevel, float leadLevel,
              float reverbMix, float wow, float crackle, boolean fluteLead) {
            this.name = name;
            this.bpmMin = bpmMin;
            this.bpmMax = bpmMax;
            this.swing = swing;
            this.drums = drums;
            this.keys = keys;
            this.bass = bass;
            this.progressions = progressions;
            this.barsPerChord = barsPerChord;
            this.padLevel = padLevel;
            this.leadLevel = leadLevel;
            this.reverbMix = reverbMix;
            this.wow = wow;
            this.crackle = crackle;
            this.fluteLead = fluteLead;
        }

        // Chord quality indices, see Synth.QUALITIES.
        private static final int MAJ9 = 0, MIN9 = 1, DOM13 = 2, MIN7 = 3, SUS11 = 4, MAJ7 = 5,
                DOM7 = 6, MIN7B5 = 7, DOM7B9 = 8, MAJ69 = 9;

        static final Theme LOFI_THEME = new Theme(LOFI, 74, 88, 0.16f, Drums.LOFI,
                Keys.ELECTRIC_PIANO, Bass.ROOTS, new int[][][]{
                {{2, MIN9}, {7, DOM13}, {0, MAJ9}, {9, MIN9}},
                {{5, MAJ7}, {4, MIN7}, {2, MIN9}, {7, SUS11}},
                {{0, MAJ9}, {9, MIN9}, {5, MAJ9}, {7, DOM13}},
                {{9, MIN9}, {5, MAJ7}, {0, MAJ9}, {7, SUS11}},
                {{5, MAJ9}, {7, DOM13}, {4, MIN7}, {9, MIN9}},
        }, 1, 0.05f, 0.11f, 0.3f, 0.0011f, 0.02f, false);

        static final Theme CAFE_JAZZ_THEME = new Theme(CAFE_JAZZ, 104, 124, 0.3f, Drums.JAZZ,
                Keys.PIANO, Bass.WALKING, new int[][][]{
                {{2, MIN9}, {7, DOM13}, {0, MAJ69}, {9, DOM7B9}},   // ii-V-I-VI
                {{4, MIN7}, {9, DOM7}, {2, MIN9}, {7, DOM13}},      // iii-VI-ii-V
                {{0, MAJ9}, {9, DOM7B9}, {2, MIN9}, {7, DOM7}},     // I-VI-ii-V
                {{11, MIN7B5}, {4, DOM7B9}, {9, MIN9}, {2, DOM13}}, // minor ii-V into vi
                {{5, MAJ9}, {10, DOM13}, {0, MAJ69}, {9, DOM7B9}},  // IV-bVII-I
        }, 1, 0.025f, 0.12f, 0.24f, 0f, 0f, false);

        static final Theme BOSSA_THEME = new Theme(BOSSA_NOVA, 124, 138, 0f, Drums.BOSSA,
                Keys.GUITAR, Bass.BOSSA, new int[][][]{
                {{0, MAJ7}, {2, DOM7}, {2, MIN7}, {7, DOM7}},       // I-II7-ii-V
                {{0, MAJ9}, {3, DOM13}, {2, MIN9}, {7, DOM7B9}},
                {{2, MIN9}, {7, DOM13}, {0, MAJ7}, {0, MAJ69}},
                {{5, MAJ7}, {5, MIN7}, {0, MAJ9}, {9, DOM7B9}},
        }, 1, 0.03f, 0.1f, 0.26f, 0f, 0f, true);

        static final Theme AMBIENT_THEME = new Theme(AMBIENT, 60, 70, 0f, Drums.NONE,
                Keys.ELECTRIC_PIANO, Bass.SUB, new int[][][]{
                {{0, MAJ9}, {9, MIN9}, {5, MAJ9}, {7, SUS11}},
                {{5, MAJ9}, {0, MAJ9}, {9, MIN9}, {4, MIN7}},
                {{0, MAJ69}, {5, MAJ9}, {2, MIN9}, {7, SUS11}},
        }, 2, 0.09f, 0.08f, 0.5f, 0.0008f, 0f, false);

        static Theme resolve(String value, java.util.Random random) {
            if (LOFI.equals(value)) return LOFI_THEME;
            if (CAFE_JAZZ.equals(value)) return CAFE_JAZZ_THEME;
            if (BOSSA_NOVA.equals(value)) return BOSSA_THEME;
            if (AMBIENT.equals(value)) return AMBIENT_THEME;
            Theme[] all = {LOFI_THEME, CAFE_JAZZ_THEME, BOSSA_THEME, AMBIENT_THEME};
            return all[random.nextInt(all.length)];
        }
    }

    /**
     * A generative band, composed fresh for each piece.
     *
     * <p>Composition: key and tempo are drawn from the theme's range; two progressions form an
     * A and a B section of eight bars each, played A-B-A-B' with the melody redrawn per section,
     * so a piece develops instead of looping one four-bar cell. Chords are voiced rootless and
     * placed by voice leading (the inversion that moves least from the last chord). The melody is
     * a one-bar motif - rhythm on the theme's (swung or straight) grid, pitches by a walk that
     * lands chord tones on strong beats - stated, restated on the next chord, varied, then
     * answered by a rest. Bass and drums follow the theme: roots and fifths with approach notes
     * (lo-fi), walking quarters under ride and brushes (jazz), the bossa bass figure under rim
     * clicks and shaker (bossa nova), or a held sub under pads with no drums (ambient).
     *
     * <p>{@link #beginOutro()} ends it properly: on the next beat the band plays the dominant,
     * then resolves to the tonic with an upward roll, a last bass note and a soft cymbal, and lets
     * it ring; {@link #outroDone()} turns true once the tail has died away.
     *
     * <p>Sound: FM electric piano (tremolo) or brighter FM piano or Karplus-Strong nylon guitar;
     * PolyBLEP saw pad through a breathing state-variable low-pass; vibraphone or flute lead;
     * synthesised kit; Freeverb-style reverb; light tape wow; a kick-driven pump. Envelopes are
     * per-sample multipliers and sines come from a table, so it costs a few percent of one core.
     */
    private static final class Synth {
        private static final int TABLE = 4096;
        private static final float[] SIN = new float[TABLE];
        static {
            for (int i = 0; i < TABLE; i++) SIN[i] = (float) Math.sin(i * 2d * Math.PI / TABLE);
        }

        /** Table sine of a phase in cycles; the mask wraps any phase, negative ones included. */
        private static float sin(float phase01) {
            return SIN[(int) (phase01 * TABLE) & (TABLE - 1)];
        }

        private static final float DT = 1f / SAMPLE_RATE;

        private static float decay(float ratePerSecond) {
            return (float) Math.exp(-ratePerSecond * DT);
        }

        private static final int[][] QUALITIES = {
                {4, 7, 11, 14},  // maj9
                {3, 7, 10, 14},  // m9
                {4, 10, 14, 21}, // 13
                {3, 7, 10, 15},  // m7
                {5, 10, 14, 17}, // 11 (sus)
                {4, 7, 11, 16},  // maj7
                {4, 7, 10, 16},  // 7
                {3, 6, 10, 15},  // m7b5
                {4, 10, 13, 19}, // 7b9
                {4, 9, 14, 16},  // 6/9
        };
        private static final int[] SCALE = {0, 2, 4, 5, 7, 9, 11};
        private static final int DOMINANT = 7;

        private static final float PIANO_AMP_DECAY = decay(1.1f);
        private static final float PIANO_INDEX_DECAY = decay(5f);
        private static final float BRIGHT_AMP_DECAY = decay(1.9f);
        private static final float BRIGHT_INDEX_DECAY = decay(3f);
        private static final float LEAD_AMP_DECAY = decay(2.2f);
        private static final float LEAD_INDEX_DECAY = decay(9f);
        private static final float FLUTE_DECAY = decay(1.2f);
        private static final float KICK_AMP_DECAY = decay(8.5f);
        private static final float KICK_SWEEP_DECAY = decay(30f);
        private static final float KICK_CLICK_DECAY = decay(400f);
        private static final float SNARE_DECAY = decay(18f);
        private static final float BRUSH_DECAY = decay(7f);
        private static final float SNARE_BODY_DECAY = decay(35f);
        private static final float RIM_DECAY = decay(60f);
        private static final float RIDE_DECAY = decay(5.5f);

        private final Theme theme;
        private final java.util.Random random = new java.util.Random();
        private final int samplesPerBeat;
        private final int samplesPerBar;
        private int keyRoot;
        private final int[][] sectionA;
        private final int[][] sectionB;

        private final int[] voicing = new int[4];
        private int[] motifSteps = new int[0];
        private int[] motifPitch = new int[0];

        // Keys voices (EP / piano FM).
        private static final int KEY_VOICES = 14;
        private final float[] kFreq = new float[KEY_VOICES];
        private final float[] kCar = new float[KEY_VOICES];
        private final float[] kMod = new float[KEY_VOICES];
        private final float[] kAge = new float[KEY_VOICES];
        private final float[] kVel = new float[KEY_VOICES];
        private final float[] kPan = new float[KEY_VOICES];
        private final float[] kAmp = new float[KEY_VOICES];
        private final float[] kIndex = new float[KEY_VOICES];
        private int kNext;

        // Karplus-Strong guitar strings.
        private static final int STRINGS = 8;
        private final float[][] ks = new float[STRINGS][];
        private final int[] ksLen = new int[STRINGS];
        private final int[] ksPos = new int[STRINGS];
        private final float[] ksAmp = new float[STRINGS];
        private final float[] ksPan = new float[STRINGS];
        private int ksNext;

        // Lead.
        private static final int LEAD_VOICES = 3;
        private final float[] lFreq = new float[LEAD_VOICES];
        private final float[] lPhase = new float[LEAD_VOICES];
        private final float[] lMod = new float[LEAD_VOICES];
        private final float[] lAge = new float[LEAD_VOICES];
        private final float[] lVel = new float[LEAD_VOICES];
        private final float[] lAmp = new float[LEAD_VOICES];
        private final float[] lIndex = new float[LEAD_VOICES];
        private int lNext;

        // Pad.
        private final float[] padPhase = new float[8];
        private final float[] padFreq = new float[8];
        private final float[] padTarget = new float[8];
        private float padLevel;
        private float padFc;
        private float svfLowL, svfBandL, svfLowR, svfBandR;

        // Bass.
        private float bassFreq, bassTarget, bassPhase, bassAge = 99f, bassVel, bassAmp, bassDecay;

        // Drums.
        private float kickPhase, kickAge = 99f, kickVel, kickAmp, kickSweep, kickClick;
        private float snareAge = 99f, snareVel, snareAmp, snareBody, snareToneP, snareDecay;
        private float snareLow, snareBand;
        private float hatAge = 99f, hatVel, hatAmp, hatDecay, hatLow;
        private float rideAge = 99f, rideVel, rideAmp, ridePhase;
        private float rimAge = 99f, rimVel, rimAmp, rimPhase;

        private final Reverb reverb = new Reverb();
        private float wowPhase, tremPhase, crackle, toneL, toneR;
        private int noiseState = 0x2545F491;

        // Events for the current bar.
        private static final int EV_CHORD = 0, EV_LEAD = 1, EV_BASS = 2, EV_KICK = 3,
                EV_SNARE = 4, EV_HAT = 5, EV_RIDE = 6, EV_RIM = 7, EV_ROLL = 8, EV_OUTRO_END = 9;
        private final int[] evAt = new int[128];
        private final int[] evType = new int[128];
        private final float[] evArg = new float[128];
        private final float[] evVel = new float[128];
        private int evCount, evIndex;
        private long sample;
        private long barStart;
        private int barLength;
        private int bar;

        // Ending.
        private boolean outro;
        private boolean outroFinalHit;
        private long outroDoneAt = Long.MAX_VALUE;

        float left;
        float right;

        Synth(Theme theme) {
            this.theme = theme;
            float bpm = theme.bpmMin + random.nextFloat() * (theme.bpmMax - theme.bpmMin);
            samplesPerBeat = Math.round(SAMPLE_RATE * 60f / bpm);
            samplesPerBar = samplesPerBeat * 4;
            int[] keys = {50, 53, 55, 57, 48, 51, 52};
            keyRoot = keys[random.nextInt(keys.length)];
            int a = random.nextInt(theme.progressions.length);
            int b = (a + 1 + random.nextInt(theme.progressions.length - 1)) % theme.progressions.length;
            sectionA = theme.progressions[a];
            sectionB = theme.progressions[b];
            for (int v = 0; v < KEY_VOICES; v++) kAmp[v] = 0f;
            int[] first = chordTones(chordAt(0));
            for (int i = 0; i < 4; i++) voicing[i] = 60 + Math.floorMod(first[i], 12);
            java.util.Arrays.sort(voicing);
            newMotif();
            bassFreq = bassTarget = midi(keyRoot - 12);
            hatDecay = decay(70f);
            barLength = samplesPerBar;
        }

        boolean inOutro() {
            return outro;
        }

        void beginOutro() {
            outro = true;
            outroFinalHit = false;
            outroDoneAt = Long.MAX_VALUE;
            // Replan from the next beat: the rest of this bar becomes the cadence.
            long intoBar = sample - barStart;
            int nextBeat = (int) (((intoBar / samplesPerBeat) + 1) * samplesPerBeat);
            evCount = evIndex = 0;
            planOutro(nextBeat);
        }

        void cancelOutro() {
            outro = false;
            outroDoneAt = Long.MAX_VALUE;
            // Pick the groove back up at the next bar.
            evCount = evIndex = 0;
            barLength = (int) Math.max(samplesPerBeat, sample - barStart + samplesPerBeat);
        }

        boolean outroDone() {
            return sample >= outroDoneAt;
        }

        /** {root offset above key, quality} of the chord playing in bar {@code n}. */
        private int[] chordAt(int n) {
            int chordIndex = n / theme.barsPerChord;
            int section = (chordIndex / 8) % 4;
            int[][] prog = section == 1 || section == 3 ? sectionB : sectionA;
            return prog[chordIndex % 4];
        }

        private int[] chordTones(int[] chord) {
            int root = keyRoot + chord[0];
            int[] q = QUALITIES[chord[1]];
            int[] out = new int[4];
            for (int i = 0; i < 4; i++) out[i] = root + q[i];
            return out;
        }

        private void voiceLead(int[] chord) {
            int[] tones = chordTones(chord);
            int[] best = null;
            int bestCost = Integer.MAX_VALUE;
            for (int mask = 0; mask < 81; mask++) {
                int[] c = new int[4];
                int m = mask;
                for (int i = 0; i < 4; i++) {
                    int shift = (m % 3) - 1;
                    m /= 3;
                    int p = tones[i];
                    while (p > 64) p -= 12;
                    while (p < 52) p += 12;
                    c[i] = p + 12 * shift;
                }
                java.util.Arrays.sort(c);
                if (c[0] < 50 || c[3] > 78) continue;
                int cost = 0;
                for (int i = 0; i < 4; i++) cost += Math.abs(c[i] - voicing[i]);
                cost += Math.abs((c[0] + c[3]) / 2 - 63);
                if (cost < bestCost) {
                    bestCost = cost;
                    best = c;
                }
            }
            if (best != null) System.arraycopy(best, 0, voicing, 0, 4);
        }

        private void newMotif() {
            int notes = theme.drums == Theme.Drums.NONE ? 2 + random.nextInt(2) : 3 + random.nextInt(3);
            java.util.TreeSet<Integer> onsets = new java.util.TreeSet<>();
            onsets.add(random.nextBoolean() ? 0 : 1);
            while (onsets.size() < notes) onsets.add(random.nextInt(7));
            motifSteps = new int[onsets.size()];
            motifPitch = new int[onsets.size()];
            int i = 0;
            int step = random.nextInt(3);
            for (int onset : onsets) {
                motifSteps[i] = onset;
                motifPitch[i] = step;
                step = Math.max(-2, Math.min(6, step + random.nextInt(5) - 2));
                i++;
            }
        }

        private int leadPitch(int[] chord, int step, boolean strong) {
            int[] tones = chordTones(chord);
            int pitch = keyRoot + 12 + SCALE[Math.floorMod(step, SCALE.length)]
                    + 12 * Math.floorDiv(step, SCALE.length);
            if (strong) {
                int best = pitch;
                int bestD = Integer.MAX_VALUE;
                for (int tone : tones) {
                    for (int o = -24; o <= 24; o += 12) {
                        int c = tone + o;
                        int d = Math.abs(c - pitch);
                        if (d < bestD) {
                            bestD = d;
                            best = c;
                        }
                    }
                }
                pitch = best;
            }
            while (pitch > 86) pitch -= 12;
            while (pitch < 67) pitch += 12;
            return pitch;
        }

        private int eighth(int k) {
            return Math.round((k * 0.5f + ((k & 1) == 1 ? theme.swing * 0.5f : 0f)) * samplesPerBeat);
        }

        private int sixteenth(int k) {
            return Math.round(k * 0.25f * samplesPerBeat);
        }

        private int humanise(int at) {
            return Math.max(0, at + Math.round((random.nextFloat() - 0.5f) * 0.012f * SAMPLE_RATE));
        }

        private void add(int at, int type, float arg, float vel) {
            if (evCount >= evAt.length) return;
            int i = evCount++;
            while (i > 0 && evAt[i - 1] > at) {
                evAt[i] = evAt[i - 1];
                evType[i] = evType[i - 1];
                evArg[i] = evArg[i - 1];
                evVel[i] = evVel[i - 1];
                i--;
            }
            evAt[i] = at;
            evType[i] = type;
            evArg[i] = arg;
            evVel[i] = vel;
        }

        private void planBar(int n) {
            evCount = evIndex = 0;
            barLength = samplesPerBar;
            boolean intro = n < 2;
            boolean fill = n % 8 == 7;
            int[] chord = chordAt(n);
            int[] next = chordAt(n + 1);
            boolean chordStart = n % theme.barsPerChord == 0;
            if (n > 0 && n % 8 == 0) newMotif();
            if (chordStart) voiceLead(chord);
            for (int i = 0; i < 4; i++) {
                float f = midi(voicing[i] - 12);
                padTarget[2 * i] = f * 1.003f;
                padTarget[2 * i + 1] = f * 0.997f;
            }

            planComping(n, chordStart, fill);
            if (!intro) {
                planBass(chord, next, n);
                planDrums(fill);
            }
            if (!intro && n % 4 != 3 && (theme.drums != Theme.Drums.NONE || n % 2 == 0)) {
                for (int i = 0; i < motifSteps.length; i++) {
                    int step = motifPitch[i];
                    if (n % 4 == 2 && i == motifSteps.length - 1) step += random.nextBoolean() ? 2 : -2;
                    boolean strong = motifSteps[i] % 2 == 0;
                    add(humanise(eighth(motifSteps[i])), EV_LEAD, leadPitch(chord, step, strong),
                            (strong ? 0.8f : 0.6f) + 0.1f * random.nextFloat());
                }
            }
        }

        private void planComping(int n, boolean chordStart, boolean fill) {
            switch (theme.keys) {
                case GUITAR:
                    // Bossa guitar: thumb on the beat, fingers on the syncopations.
                    int[] hits = {0, 3, 6, 10, 12};
                    for (int h : hits) add(humanise(sixteenth(h)), EV_CHORD, h == 0 || h == 12 ? 1 : 0,
                            h == 0 ? 0.9f : 0.6f);
                    break;
                case PIANO:
                    // Jazz comping: short, off the beat, varied per bar.
                    add(humanise(eighth(random.nextBoolean() ? 1 : 0)), EV_CHORD, 0, 0.7f);
                    add(humanise(eighth(3 + random.nextInt(2))), EV_CHORD, 0, 0.6f);
                    if (random.nextFloat() < 0.5f) add(humanise(eighth(7)), EV_CHORD, 0, 0.5f);
                    break;
                default:
                    if (theme.drums == Theme.Drums.NONE) {
                        if (chordStart) add(0, EV_CHORD, 0, 0.7f);
                        // Sparse single notes drifting over the held chord.
                        for (int k = 1; k < 8; k++) {
                            if (random.nextFloat() < 0.28f) add(humanise(eighth(k)), EV_LEAD, -1, 0.45f);
                        }
                    } else {
                        add(humanise(0), EV_CHORD, 0, 0.95f);
                        add(humanise(eighth(3)), EV_CHORD, 0, 0.62f);
                        if (!fill && random.nextFloat() < 0.45f) add(humanise(eighth(6)), EV_CHORD, 0, 0.45f);
                    }
                    break;
            }
        }

        private void planBass(int[] chord, int[] next, int n) {
            int root = keyRoot - 12 + chord[0];
            int nextRoot = keyRoot - 12 + next[0];
            switch (theme.bass) {
                case WALKING: {
                    int[] q = QUALITIES[chord[1]];
                    int third = root + (q[0] == 3 ? 3 : 4);
                    int fifth = root + 7;
                    add(humanise(0), EV_BASS, root, 0.9f);
                    add(humanise(samplesPerBeat), EV_BASS, random.nextBoolean() ? third : fifth, 0.75f);
                    add(humanise(samplesPerBeat * 2), EV_BASS, random.nextBoolean() ? fifth : root + 12, 0.8f);
                    add(humanise(samplesPerBeat * 3), EV_BASS, nextRoot + (nextRoot > root ? -1 : 1), 0.75f);
                    break;
                }
                case BOSSA:
                    add(0, EV_BASS, root, 0.9f);
                    add(sixteenth(6), EV_BASS, root + 7, 0.7f);
                    add(sixteenth(8), EV_BASS, root + 7, 0.75f);
                    add(sixteenth(14), EV_BASS, root, 0.65f);
                    break;
                case SUB:
                    if (n % theme.barsPerChord == 0) add(0, EV_BASS, root, 0.7f);
                    break;
                default:
                    add(humanise(0), EV_BASS, root, 0.95f);
                    add(humanise(eighth(3)), EV_BASS, root + (random.nextBoolean() ? 7 : 12), 0.6f);
                    add(humanise(eighth(5)), EV_BASS, root + 7, 0.7f);
                    add(humanise(eighth(7)), EV_BASS, nextRoot + (nextRoot > root ? -1 : 1), 0.65f);
                    break;
            }
        }

        private void planDrums(boolean fill) {
            switch (theme.drums) {
                case JAZZ:
                    // Ride: ding, ding-da, ding, ding-da; feathered kick; brushes on 2 and 4.
                    for (int beat = 0; beat < 4; beat++) {
                        add(humanise(samplesPerBeat * beat), EV_RIDE, 0, beat % 2 == 0 ? 0.75f : 0.9f);
                        if (beat % 2 == 1) add(humanise(eighth(beat * 2 + 1)), EV_RIDE, 0, 0.5f);
                        add(humanise(samplesPerBeat * beat), EV_KICK, 0, 0.25f);
                    }
                    add(humanise(samplesPerBeat), EV_SNARE, 1, 0.55f);
                    add(humanise(samplesPerBeat * 3), EV_SNARE, 1, 0.55f);
                    if (fill) add(humanise(eighth(7)), EV_SNARE, 0, 0.5f);
                    break;
                case BOSSA:
                    int[] rim = {0, 3, 6, 10, 12};
                    for (int r : rim) add(humanise(sixteenth(r)), EV_RIM, 0, 0.7f);
                    for (int k = 0; k < 16; k++) add(sixteenth(k), EV_HAT, 0, k % 2 == 0 ? 0.35f : 0.22f);
                    add(0, EV_KICK, 0, 0.6f);
                    add(sixteenth(8), EV_KICK, 0, 0.55f);
                    add(sixteenth(6), EV_KICK, 0, 0.35f);
                    break;
                case NONE:
                    break;
                default:
                    add(humanise(0), EV_KICK, 0, 1f);
                    add(humanise(eighth(5)), EV_KICK, 0, 0.85f);
                    if (random.nextFloat() < 0.35f) add(humanise(eighth(3)), EV_KICK, 0, 0.55f);
                    add(humanise(samplesPerBeat), EV_SNARE, 0, 1f);
                    add(humanise(samplesPerBeat * 3), EV_SNARE, 0, 1f);
                    if (random.nextFloat() < 0.4f) add(humanise(eighth(3)), EV_SNARE, 0, 0.16f);
                    for (int k = 0; k < 8; k++) {
                        boolean open = k == 7 && random.nextFloat() < 0.25f;
                        add(humanise(eighth(k)), EV_HAT, open ? 1 : 0,
                                ((k & 1) == 0 ? 0.55f : 0.32f) + 0.1f * random.nextFloat());
                    }
                    if (fill) {
                        for (int k = 12; k < 16; k++) {
                            add(humanise(sixteenth(k)), EV_SNARE, 0, 0.3f + 0.12f * (k - 12));
                        }
                    }
                    break;
            }
        }

        /** V for one beat, then I rolled upward with a last bass note and cymbal, left to ring. */
        private void planOutro(int from) {
            barLength = Integer.MAX_VALUE;
            int[] dominant = {DOMINANT, 2};
            int[] tonic = {0, theme == Theme.CAFE_JAZZ_THEME ? 9 : 0};
            voiceLead(dominant);
            add(from, EV_CHORD, 2, 0.75f);
            add(from, EV_BASS, keyRoot - 12 + DOMINANT, 0.8f);
            int resolve = from + samplesPerBeat * (theme.drums == Theme.Drums.NONE ? 2 : 1);
            add(resolve, EV_ROLL, 0, 0.9f);
            add(resolve, EV_BASS, keyRoot - 12, 0.9f);
            if (theme.drums != Theme.Drums.NONE) {
                add(resolve, EV_KICK, 0, 0.7f);
                add(resolve, EV_HAT, 2, 0.5f);
            }
            add(resolve + samplesPerBeat / 2, EV_LEAD, keyRoot + 24, 0.55f);
            add(resolve, EV_OUTRO_END, 0, 0);
            pendingTonic = tonic;
        }

        private int[] pendingTonic;

        private void triggerKeys(float vel, int style) {
            if (theme.keys == Theme.Keys.GUITAR) {
                // Thumb (style 1) plucks the lowest note, fingers the upper three.
                int from = style == 1 ? 0 : 1;
                int to = style == 1 ? 1 : 4;
                for (int n = from; n < to; n++) pluck(midi(voicing[n] - (style == 1 ? 12 : 0)), vel, 0.3f + 0.13f * n);
                return;
            }
            for (int n = 0; n < 4; n++) strike(voicing[n], vel, -n * 0.008f, 0.3f + 0.4f * n / 3f);
        }

        private void strike(int note, float vel, float delay, float pan) {
            int v = kNext;
            kNext = (kNext + 1) % KEY_VOICES;
            kFreq[v] = midi(note) * (1f + (random.nextFloat() - 0.5f) * 0.002f);
            kCar[v] = 0f;
            kMod[v] = 0f;
            kAge[v] = delay;
            kVel[v] = vel * (0.85f + 0.15f * random.nextFloat());
            kPan[v] = pan;
            kAmp[v] = 1f;
            kIndex[v] = 1f;
        }

        private void pluck(float freq, float vel, float pan) {
            int s = ksNext;
            ksNext = (ksNext + 1) % STRINGS;
            int len = Math.max(8, Math.round(SAMPLE_RATE / freq));
            if (ks[s] == null || ks[s].length < len) ks[s] = new float[Math.max(len, 1024)];
            ksLen[s] = len;
            ksPos[s] = 0;
            // A soft, low-passed burst: nylon rather than steel.
            float prev = 0f;
            for (int i = 0; i < len; i++) {
                prev += 0.5f * (noise() - prev);
                ks[s][i] = prev;
            }
            ksAmp[s] = vel * 0.5f;
            ksPan[s] = pan;
        }

        void next() {
            long intoBar = sample - barStart;
            if (!outro && intoBar >= barLength) {
                barStart = sample;
                intoBar = 0;
                planBar(bar++);
            } else if (sample == 0) {
                planBar(bar++);
            }
            while (evIndex < evCount && evAt[evIndex] <= intoBar) {
                fire(evType[evIndex], evArg[evIndex], evVel[evIndex]);
                evIndex++;
            }

            wowPhase += 0.45f * DT;
            if (wowPhase >= 1f) wowPhase -= 1f;
            float wow = 1f + theme.wow * (sin(wowPhase) + 0.3f * sin(wowPhase * 7f));
            tremPhase += 4.2f * DT;
            if (tremPhase >= 1f) tremPhase -= 1f;
            float trem = 0.5f + 0.5f * sin(tremPhase);

            float kick = 0f;
            float pump = 1f;
            if (kickAmp > 0.001f) {
                kickPhase += (46f + 90f * kickSweep) * DT;
                kick = (sin(kickPhase) * kickAmp + kickClick * 0.2f) * 0.6f * kickVel;
                pump = 1f - (theme.drums == Theme.Drums.LOFI ? 0.28f : 0.12f) * kickAmp * kickVel;
                kickAmp *= KICK_AMP_DECAY;
                kickSweep *= KICK_SWEEP_DECAY;
                kickClick *= KICK_CLICK_DECAY;
            }

            // Keys.
            boolean bright = theme.keys == Theme.Keys.PIANO;
            float keysL = 0f, keysR = 0f;
            for (int v = 0; v < KEY_VOICES; v++) {
                if (kAmp[v] < 0.002f) continue;
                float age = kAge[v];
                kAge[v] = age + DT;
                if (age < 0f) continue;
                float f = kFreq[v] * wow;
                kCar[v] += f * DT;
                kMod[v] += f * (bright ? 2f : 1f) * DT;
                float index = (bright ? 0.8f : 1.1f) * kIndex[v] + (bright ? 0.35f : 0.2f);
                float env = kAmp[v] * Math.min(1f, age * 350f);
                float out = sin(kCar[v] + index * sin(kMod[v])) * env * kVel[v] * 0.13f;
                kAmp[v] *= bright ? BRIGHT_AMP_DECAY : PIANO_AMP_DECAY;
                kIndex[v] *= bright ? BRIGHT_INDEX_DECAY : PIANO_INDEX_DECAY;
                float pan = bright ? kPan[v] : kPan[v] * (0.8f + 0.2f * trem);
                keysL += out * (1f - pan);
                keysR += out * pan;
            }
            // Guitar strings.
            for (int s = 0; s < STRINGS; s++) {
                if (ksAmp[s] < 0.002f || ks[s] == null) continue;
                float[] buf = ks[s];
                int len = ksLen[s];
                int p = ksPos[s];
                int q = p + 1 == len ? 0 : p + 1;
                float out = buf[p];
                buf[p] = 0.4985f * (buf[p] + buf[q]);
                ksPos[s] = q;
                float o = out * ksAmp[s];
                ksAmp[s] *= 0.99997f;
                keysL += o * (1f - ksPan[s]);
                keysR += o * ksPan[s];
            }

            // Lead.
            float lead = 0f;
            for (int v = 0; v < LEAD_VOICES; v++) {
                if (lAmp[v] < 0.002f) continue;
                float age = lAge[v];
                lAge[v] = age + DT;
                float f = lFreq[v] * wow;
                if (theme.fluteLead) {
                    float vib = 1f + 0.004f * sin(age * 5.2f) * Math.min(1f, age * 2f);
                    lPhase[v] += f * vib * DT;
                    float breath = Math.min(1f, age * 18f) * lAmp[v];
                    lead += (sin(lPhase[v]) + 0.18f * sin(lPhase[v] * 2f)) * breath * lVel[v] * 0.1f;
                    lAmp[v] *= FLUTE_DECAY;
                } else {
                    lPhase[v] += f * DT;
                    lMod[v] += f * 4f * DT;
                    float env = lAmp[v] * Math.min(1f, age * 500f);
                    lead += sin(lPhase[v] + 0.9f * lIndex[v] * sin(lMod[v])) * env * lVel[v] * 0.11f;
                    lAmp[v] *= LEAD_AMP_DECAY;
                    lIndex[v] *= LEAD_INDEX_DECAY;
                }
            }
            lead *= theme.leadLevel / 0.11f;

            // Pad.
            float padL = 0f, padR = 0f;
            for (int i = 0; i < 8; i++) {
                padFreq[i] += (padTarget[i] - padFreq[i]) * 0.0006f;
                float inc = padFreq[i] * wow * DT;
                padPhase[i] += inc;
                if (padPhase[i] >= 1f) padPhase[i] -= 1f;
                float saw = 2f * padPhase[i] - 1f - polyBlep(padPhase[i], inc);
                if ((i & 1) == 0) padL += saw; else padR += saw;
            }
            float padAim = outroFinalHit ? 0f : (bar <= 2 ? 0.7f : 1f) * theme.padLevel;
            padLevel += (padAim - padLevel) * 0.0002f;
            if ((sample & 31) == 0) {
                float cutoff = 700f + 450f * sin(sample * DT * 0.07f);
                padFc = (float) (2 * Math.sin(Math.PI * cutoff / SAMPLE_RATE));
            }
            svfLowL += padFc * svfBandL;
            svfBandL += padFc * (padL - svfLowL - 0.9f * svfBandL);
            svfLowR += padFc * svfBandR;
            svfBandR += padFc * (padR - svfLowR - 0.9f * svfBandR);
            float pL = svfLowL * padLevel;
            float pR = svfLowR * padLevel;

            // Bass.
            float bass = 0f;
            if (bassAmp > 0.001f) {
                bassFreq += (bassTarget - bassFreq) * 0.003f;
                bassPhase += bassFreq * wow * DT;
                if (bassPhase >= 1f) bassPhase -= 1f;
                float b = sin(bassPhase);
                float body = theme.bass == Theme.Bass.SUB ? b : b + 0.25f * b * b * b + 0.12f * sin(bassPhase * 2f);
                bass = body * bassAmp * Math.min(1f, bassAge * 250f) * 0.32f * bassVel;
                bassAmp *= bassDecay;
                bassAge += DT;
            }

            float noise = noise();
            float snare = 0f;
            if (snareAmp > 0.002f) {
                snareLow += 0.2f * (noise - snareLow);
                snareBand += 0.45f * ((noise - snareLow) - snareBand);
                snareToneP += 185f * DT;
                if (snareToneP >= 1f) snareToneP -= 1f;
                snare = (snareBand * snareAmp + sin(snareToneP) * snareBody * 0.3f) * 0.2f * snareVel;
                snareAmp *= snareDecay;
                snareBody *= SNARE_BODY_DECAY;
            }
            float hat = 0f;
            if (hatAmp > 0.002f) {
                hatLow += 0.62f * (noise - hatLow);
                hat = (noise - hatLow) * hatAmp * hatVel * 0.06f;
                hatAmp *= hatDecay;
            }
            float ride = 0f;
            if (rideAmp > 0.002f) {
                ridePhase += 5100f * DT;
                if (ridePhase >= 1f) ridePhase -= 1f;
                ride = ((noise - hatLow) * 0.5f + sin(ridePhase) * 0.35f) * rideAmp * rideVel * 0.05f;
                rideAmp *= RIDE_DECAY;
            }
            float rim = 0f;
            if (rimAmp > 0.002f) {
                rimPhase += 1700f * DT;
                if (rimPhase >= 1f) rimPhase -= 1f;
                rim = (sin(rimPhase) + noise * 0.3f) * rimAmp * rimVel * 0.09f;
                rimAmp *= RIM_DECAY;
            }
            if (theme.crackle > 0f && (noiseState & 0x3FFF) == 7) crackle = noise() * theme.crackle;
            crackle *= 0.9f;

            float sendL = keysL * 0.5f + lead * 0.6f + pL * 0.4f;
            float sendR = keysR * 0.5f + lead * 0.6f + pR * 0.4f;
            reverb.process(sendL, sendR);

            float tonalL = (keysL + lead * 0.8f + pL + bass) * pump;
            float tonalR = (keysR + lead * 1.1f + pR + bass) * pump;
            float drums = kick + snare + rim;
            float mixL = tonalL + reverb.left * theme.reverbMix + drums + hat * 0.7f + ride * 1.1f + crackle;
            float mixR = tonalR + reverb.right * theme.reverbMix + drums + hat * 1.2f + ride * 0.8f + crackle;
            toneL += 0.5f * (mixL - toneL);
            toneR += 0.5f * (mixR - toneR);
            left = toneL / (1f + 0.2f * Math.abs(toneL));
            right = toneR / (1f + 0.2f * Math.abs(toneR));
            sample++;
        }

        private void fire(int type, float arg, float vel) {
            switch (type) {
                case EV_CHORD:
                    if (arg == 2) {
                        // Outro dominant: already voiced by planOutro.
                        for (int n = 0; n < 4; n++) strikeOrPluck(voicing[n], vel, -n * 0.01f, 0.3f + 0.13f * n);
                    } else {
                        triggerKeys(vel, (int) arg);
                    }
                    break;
                case EV_ROLL:
                    // The resolution, rolled upward and left to ring.
                    if (pendingTonic != null) voiceLead(pendingTonic);
                    outroFinalHit = true;
                    for (int n = 0; n < 4; n++) strikeOrPluck(voicing[n], vel, n * 0.07f, 0.25f + 0.17f * n);
                    strikeOrPluck(voicing[3] + 12, vel * 0.6f, 0.3f, 0.7f);
                    break;
                case EV_LEAD: {
                    int note = (int) arg;
                    if (note < 0) {
                        // Ambient: a random chord tone, an octave up.
                        note = voicing[random.nextInt(4)] + 12;
                    }
                    int v = lNext;
                    lNext = (lNext + 1) % LEAD_VOICES;
                    lFreq[v] = midi(note);
                    lPhase[v] = 0f;
                    lMod[v] = 0f;
                    lAge[v] = 0f;
                    lVel[v] = vel;
                    lAmp[v] = 1f;
                    lIndex[v] = 1f;
                    break;
                }
                case EV_BASS:
                    bassTarget = midi((int) arg);
                    if (theme.bass == Theme.Bass.WALKING) bassFreq = bassTarget;
                    bassAge = 0f;
                    bassVel = vel;
                    bassAmp = 1f;
                    bassDecay = decay(theme.bass == Theme.Bass.SUB ? 0.35f
                            : theme.bass == Theme.Bass.WALKING ? 3.2f : 1.4f);
                    break;
                case EV_KICK:
                    kickPhase = 0f;
                    kickVel = vel;
                    kickAmp = 1f;
                    kickSweep = 1f;
                    kickClick = 1f;
                    break;
                case EV_SNARE:
                    snareVel = vel;
                    snareAmp = 1f;
                    snareBody = arg == 1 ? 0.3f : 1f;
                    snareDecay = arg == 1 ? BRUSH_DECAY : SNARE_DECAY;
                    break;
                case EV_HAT:
                    hatVel = vel;
                    hatAmp = 1f;
                    hatDecay = decay(arg == 2 ? 2.5f : arg == 1 ? 9f : 70f);
                    break;
                case EV_RIDE:
                    rideVel = vel;
                    rideAmp = 1f;
                    break;
                case EV_RIM:
                    rimVel = vel;
                    rimAmp = 1f;
                    break;
                case EV_OUTRO_END:
                    // Let the chord ring for a couple of seconds.
                    outroDoneAt = sample + (long) (2.6f * SAMPLE_RATE);
                    break;
                default:
                    break;
            }
        }

        private void strikeOrPluck(int note, float vel, float delay, float pan) {
            if (theme.keys == Theme.Keys.GUITAR) pluck(midi(note), vel, pan);
            else strike(note, vel, delay, pan);
        }

        private float noise() {
            int x = noiseState;
            x ^= x << 13;
            x ^= x >>> 17;
            x ^= x << 5;
            noiseState = x;
            return x * (1f / 2147483648f);
        }

        /** Band-limits the saw's reset, removing the aliasing a naive saw has. */
        private static float polyBlep(float t, float dt) {
            if (dt <= 0f) return 0f;
            if (t < dt) {
                float x = t / dt;
                return x + x - x * x - 1f;
            }
            if (t > 1f - dt) {
                float x = (t - 1f) / dt;
                return x * x + x + x + 1f;
            }
            return 0f;
        }

        private static float midi(int note) {
            return (float) (440d * Math.pow(2d, (note - 69) / 12d));
        }
    }

    /** Freeverb-style stereo reverb: parallel damped combs into series all-passes, per side. */
    private static final class Reverb {
        private static final int[] COMBS = {1116, 1188, 1277, 1356};
        private static final int[] ALLPASSES = {556, 441};
        private static final int SPREAD = 23;
        private static final float FEEDBACK = 0.84f;
        private static final float DAMP = 0.3f;
        private final float[][] combL = new float[COMBS.length][];
        private final float[][] combR = new float[COMBS.length][];
        private final int[] combIndexL = new int[COMBS.length];
        private final int[] combIndexR = new int[COMBS.length];
        private final float[] combStoreL = new float[COMBS.length];
        private final float[] combStoreR = new float[COMBS.length];
        private final float[][] apL = new float[ALLPASSES.length][];
        private final float[][] apR = new float[ALLPASSES.length][];
        private final int[] apIndexL = new int[ALLPASSES.length];
        private final int[] apIndexR = new int[ALLPASSES.length];
        float left;
        float right;

        Reverb() {
            for (int i = 0; i < COMBS.length; i++) {
                combL[i] = new float[COMBS[i]];
                combR[i] = new float[COMBS[i] + SPREAD];
            }
            for (int i = 0; i < ALLPASSES.length; i++) {
                apL[i] = new float[ALLPASSES[i]];
                apR[i] = new float[ALLPASSES[i] + SPREAD];
            }
        }

        void process(float inL, float inR) {
            float input = (inL + inR) * 0.12f;
            float outL = 0f;
            float outR = 0f;
            for (int i = 0; i < COMBS.length; i++) {
                float[] bl = combL[i];
                float y = bl[combIndexL[i]];
                combStoreL[i] = y * (1f - DAMP) + combStoreL[i] * DAMP;
                bl[combIndexL[i]] = input + combStoreL[i] * FEEDBACK;
                if (++combIndexL[i] == bl.length) combIndexL[i] = 0;
                outL += y;
                float[] br = combR[i];
                float z = br[combIndexR[i]];
                combStoreR[i] = z * (1f - DAMP) + combStoreR[i] * DAMP;
                br[combIndexR[i]] = input + combStoreR[i] * FEEDBACK;
                if (++combIndexR[i] == br.length) combIndexR[i] = 0;
                outR += z;
            }
            for (int i = 0; i < ALLPASSES.length; i++) {
                float[] bl = apL[i];
                float bufL = bl[apIndexL[i]];
                float yL = -outL + bufL;
                bl[apIndexL[i]] = outL + bufL * 0.5f;
                if (++apIndexL[i] == bl.length) apIndexL[i] = 0;
                outL = yL;
                float[] br = apR[i];
                float bufR = br[apIndexR[i]];
                float yR = -outR + bufR;
                br[apIndexR[i]] = outR + bufR * 0.5f;
                if (++apIndexR[i] == br.length) apIndexR[i] = 0;
                outR = yR;
            }
            left = outL;
            right = outR;
        }
    }
}
