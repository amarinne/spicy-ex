package com.eza.spicyex.lyrics;

import android.content.Context;
import android.net.Uri;

import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.lyrics.catalog.CatalogAdapters;
import com.eza.spicyex.lyrics.catalog.CatalogSource;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Request;
import okhttp3.Response;

import static com.eza.spicyex.lyrics.LyricUtils.safe;

/**
 * NetEase Cloud Music fallback adapter: title search, ranked hits, word-level YRC over the
 * signed eapi transport first, plain line-level LRC fallback. Karaoke rewriting applies here
 * (search-based) and nowhere on exact-ID paths: a rewritten search stores
 * {@code KARAOKE_SUBSTITUTION}, a verbatim one {@code STRONG_SEARCH}.
 *
 * <p>Not in the default fallback order yet: Slice 6/7 wire explicit "check other sources" and
 * refresh paths onto this. Every terminal outcome persists a candidate or a source state.
 */
public final class NeteaseAdapter {
    public static final int ADAPTER_REVISION = 1;

    private final okhttp3.OkHttpClient http;
    private final LyricsRepository.Parser parser;

    public NeteaseAdapter(okhttp3.OkHttpClient http, LyricsRepository.Parser parser) {
        this.http = http;
        this.parser = parser;
    }

    public void fetch(Context context, SpotifyTrack track, int generation,
                      LyricsRepository.ResultCallback callback) {
        fetch(context, track, generation, false, callback);
    }

    public void fetch(Context context, SpotifyTrack track, int generation,
                      boolean karaokeOriginalLyrics,
                      LyricsRepository.ResultCallback callback) {
        SpotifyTrack searchTrack = KaraokeTitles.forLyricsSearch(track, karaokeOriginalLyrics);
        boolean substituted = searchTrack != track;
        String query = (safe(searchTrack == null ? null : searchTrack.title) + " "
                + safe(searchTrack == null ? null : searchTrack.artist)).trim();
        String url = "https://music.163.com/api/search/get?s=" + Uri.encode(query)
                + "&type=1&offset=0&limit=20";
        Request request = new Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", "Mozilla/5.0")
                .header("Referer", "https://music.163.com/")
                .build();
        SpotifyTrack queryTrack = searchTrack;
        http.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                fail(context, track, callback, "NetEase search failed: " + safe(e.getMessage()));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (Response ignored = response) {
                    if (!response.isSuccessful() || response.body() == null) {
                        fail(context, track, callback,
                                "NetEase search HTTP " + response.code());
                        return;
                    }
                    List<NeteaseSongRanker.Candidate> songs =
                            rankSongs(response.body().string(), queryTrack);
                    if (songs.isEmpty()) {
                        fail(context, track, callback, "NetEase empty");
                        return;
                    }
                    fetchLyric(context, track, queryTrack, substituted, generation, songs,
                            callback);
                } catch (Throwable t) {
                    fail(context, track, callback,
                            "NetEase search parse failed: " + safe(t.getMessage()));
                }
            }
        });
    }

    private void fetchLyric(Context context, SpotifyTrack track, SpotifyTrack queryTrack,
                            boolean substituted, int generation,
                            List<NeteaseSongRanker.Candidate> songs,
                            LyricsRepository.ResultCallback callback) {
        List<Long> ids = new ArrayList<>();
        for (NeteaseSongRanker.Candidate candidate : songs) {
            if (!candidate.supportsWordLyrics()) continue;
            if (!ids.contains(candidate.id)) ids.add(candidate.id);
            if (ids.size() >= NeteaseSongRanker.MAX_WORD_LYRIC_ATTEMPTS) break;
        }
        String lineId = String.valueOf(songs.get(0).id);
        Runnable lineFallback = () -> fetchLyricById(context, track, queryTrack, substituted,
                generation, lineId, callback);
        if (ids.isEmpty()) {
            lineFallback.run();
            return;
        }
        tryWordChain(context, track, queryTrack, substituted, generation, ids, 0, callback,
                lineFallback);
    }

    private void tryWordChain(Context context, SpotifyTrack track, SpotifyTrack queryTrack,
                              boolean substituted, int generation, List<Long> ids, int index,
                              LyricsRepository.ResultCallback callback, Runnable lineFallback) {
        if (index >= ids.size()) {
            lineFallback.run();
            return;
        }
        fetchWordLyric(context, track, queryTrack, substituted, generation, ids.get(index),
                callback, () -> tryWordChain(context, track, queryTrack, substituted, generation,
                        ids, index + 1, callback, lineFallback));
    }

    private void fetchWordLyric(Context context, SpotifyTrack track, SpotifyTrack queryTrack,
                                boolean substituted, int generation, long songId,
                                LyricsRepository.ResultCallback callback, Runnable fallback) {
        String apiPath = "/api/song/lyric/v1";
        String payload = "{\"id\":\"" + songId + "\",\"cp\":\"false\",\"lv\":\"0\",\"kv\":\"0\","
                + "\"tv\":\"0\",\"rv\":\"0\",\"yv\":\"0\",\"ytv\":\"0\",\"yrv\":\"0\","
                + "\"csrf_token\":\"\",\"header\":" + NeteaseEapi.headerJson() + "}";
        String params = NeteaseEapi.params(apiPath, payload);
        if (params == null) {
            fallback.run();
            return;
        }
        Request request = new Request.Builder()
                .url("https://interface3.music.163.com/eapi/song/lyric/v1")
                .post(new okhttp3.FormBody.Builder().add("params", params).build())
                .header("User-Agent", NeteaseEapi.USER_AGENT)
                .header("Referer", "https://music.163.com/")
                .header("Cookie", NeteaseEapi.cookieHeader())
                .build();
        http.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                fallback.run();
            }

            @Override public void onResponse(Call call, Response response) throws IOException {
                try (Response ignored = response) {
                    if (!response.isSuccessful() || response.body() == null) {
                        fallback.run();
                        return;
                    }
                    String raw = response.body().string();
                    LyricsDocument doc =
                            parser.parseNeteaseWordLyrics(context, queryTrack, raw);
                    if (doc == null || doc.lines.isEmpty()) {
                        fallback.run();
                        return;
                    }
                    doc.generation = generation;
                    succeed(context, track, doc, substituted, String.valueOf(songId), raw,
                            callback);
                } catch (Throwable t) {
                    fallback.run();
                }
            }
        });
    }

    private void fetchLyricById(Context context, SpotifyTrack track, SpotifyTrack queryTrack,
                                boolean substituted, int generation, String songId,
                                LyricsRepository.ResultCallback callback) {
        String url = "https://music.163.com/api/song/lyric?id=" + Uri.encode(songId)
                + "&lv=1&kv=1&tv=1";
        Request request = new Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", "Mozilla/5.0")
                .header("Referer", "https://music.163.com/")
                .build();
        http.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                fail(context, track, callback, "NetEase lyric failed: " + safe(e.getMessage()));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (Response ignored = response) {
                    if (!response.isSuccessful() || response.body() == null) {
                        fail(context, track, callback,
                                "NetEase lyric HTTP " + response.code());
                        return;
                    }
                    String raw = response.body().string();
                    LyricsDocument doc =
                            parser.parseNeteaseLyrics(context, queryTrack, raw);
                    doc.generation = generation;
                    if (doc.lines.isEmpty()) {
                        fail(context, track, callback, "NetEase empty");
                        return;
                    }
                    succeed(context, track, doc, substituted, songId, raw, callback);
                } catch (Throwable t) {
                    fail(context, track, callback,
                            "NetEase parse failed: " + safe(t.getMessage()));
                }
            }
        });
    }

    private void succeed(Context context, SpotifyTrack track, LyricsDocument doc,
                         boolean substituted, String providerItemId, String raw,
                         LyricsRepository.ResultCallback callback) {
        doc.selectedSource = "NetEase";
        doc.selectionMode = "strict";
        doc.selectionOverride = "NetEase";
        CatalogSource.MatchMethod method = substituted
                ? CatalogSource.MatchMethod.KARAOKE_SUBSTITUTION
                : CatalogSource.MatchMethod.STRONG_SEARCH;
        CatalogAdapters.recordSuccess(context, CatalogSource.SourceId.NETEASE, track, doc, method,
                providerItemId, raw, ADAPTER_REVISION);
        callback.onSuccess(doc);
    }

    private void fail(Context context, SpotifyTrack track,
                      LyricsRepository.ResultCallback callback, String error) {
        CatalogAdapters.recordError(context, CatalogSource.SourceId.NETEASE, track, error);
        callback.onError(error);
    }

    static List<NeteaseSongRanker.Candidate> rankSongs(String body, SpotifyTrack track) {
        try {
            JsonElement root = JsonParser.parseString(body);
            if (!root.isJsonObject()) return Collections.emptyList();
            JsonObject result = Json.optObject(root.getAsJsonObject(), "result");
            JsonArray songs = result == null ? null : Json.optArray(result, "songs");
            return NeteaseSongRanker.rank(songs,
                    track == null ? null : track.title,
                    track == null ? null : track.artist,
                    track == null ? null : track.album,
                    track == null ? 0 : track.duration);
        } catch (Throwable ignored) {
            return Collections.emptyList();
        }
    }
}
