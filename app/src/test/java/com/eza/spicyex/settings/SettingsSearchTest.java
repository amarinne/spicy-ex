package com.eza.spicyex.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class SettingsSearchTest {

    /** Names as the strings files have them: English, Korean, Japanese, Russian, Chinese. */
    private static SettingsSearch.Entry entry(String id, String section, String[] names, String... extra) {
        return new SettingsSearch.Entry(id, Arrays.asList(names), section, section, Arrays.asList(extra));
    }

    private static final List<SettingsSearch.Entry> ENTRIES = Arrays.asList(
            entry("blur", "lyrics_screen", new String[]{"가사 흐림 효과", "Line blur", "行ぼかし", "Размытие строк", "歌词模糊"}),
            entry("font", "lyrics_screen", new String[]{"가사 글꼴", "Lyrics font", "歌詞フォント", "Шрифт текста", "歌词字体"}),
            entry("status", "general", new String[]{"상단바 숨기기 (세로)", "Hide status bar (portrait)"}),
            entry("target", "translation", new String[]{"번역할 언어", "Target language", "翻訳先の言語", "Язык перевода", "目标语言"},
                    "Spanish", "스페인어"),
            entry("translate", "translation", new String[]{"가사 번역", "Translate lyrics", "歌詞を翻訳", "Переводить текст", "翻译歌词"}),
            entry("cache", "lyrics_sources", new String[]{"캐시 크기 제한", "Cache size limit"}, "128 MB"),
            entry("like", "gestures", new String[]{"두 번 탭해서 좋아요", "Double-tap to like"},
                    "heart, add to liked songs", "하트, 좋아요 추가"),
            entry("seek", "gestures", new String[]{"가사 탭해서 이동", "Tap lyric to seek"}, "Double tap", "두 번 탭"),
            entry("ads", "ads", new String[]{"광고 음악 스타일", "Ad music style"}, "Lofi", "Cafe jazz"),
            entry("offset", "lyrics", new String[]{"싱크 조절", "Sync offset"}),
            entry("mini", "general", new String[]{"미니 플레이어에 가사 아이콘 표시", "Show lyrics icon on mini player"},
                    "home screen, bottom player bar, lyrics button outside the lyrics screen",
                    "홈화면, 하단 플레이어, 가사 버튼"),
            entry("cardtap", "gestures", new String[]{"카드를 눌러 가사 열기", "Tap card to open lyrics"}));

    /** A few synonym groups, as the strings files would give them (all languages merged). */
    private static final List<List<String>> CONCEPTS = Arrays.asList(
            Arrays.asList("blur", "블러", "흐림", "ぼかし", "размытие", "模糊"),
            Arrays.asList("font", "글꼴", "폰트", "フォント", "шрифт", "字体"),
            Arrays.asList("status bar", "notification bar", "상단바", "상태바", "알림바"),
            Arrays.asList("like", "heart", "좋아요", "하트", "いいね", "喜欢"),
            Arrays.asList("double", "두번", "두 번", "이중", "더블", "双击"),
            Arrays.asList("sync", "delay", "싱크", "딜레이", "지연"),
            Arrays.asList("ad", "ads", "commercial", "광고"),
            Arrays.asList("home", "home screen", "mini player", "홈", "홈화면", "미니플레이어", "플레이어"),
            Arrays.asList("lyrics", "가사", "歌詞", "текст", "歌词"),
            Arrays.asList("cache", "storage", "캐시", "용량"));

    private static final SettingsSearch SEARCH = new SettingsSearch(ENTRIES, CONCEPTS);

    private static String top(String query) {
        List<SettingsSearch.Result> results = SEARCH.search(query, 5, 0);
        return results.isEmpty() ? "" : (String) results.get(0).entry.target;
    }

    @Test
    public void namesInEveryLanguage() {
        assertEquals("blur", top("blur"));
        assertEquals("font", top("글꼴"));
        assertEquals("font", top("フォント"));
        assertEquals("target", top("target lang"));
        assertEquals("cache", top("캐시"));
    }

    @Test
    public void typosStillFind() {
        assertEquals("blur", top("bluur"));
        assertEquals("cache", top("캐쉬"));            // ㅟ for ㅣ: one letter off
        assertEquals("font", top("lyrcs font"));
        assertEquals("translate", top("tranlsate"));    // transposed letters
    }

    @Test
    public void wordEndingsInAnyLanguage() {
        assertEquals("blur", top("블러를"));           // Korean particle
        assertEquals("font", top("fonts"));            // English plural
        assertEquals("blur", top("размытия"));         // Russian case ending
    }

    @Test
    public void chineseAndJapaneseWithoutSpaces() {
        assertEquals("blur", top("歌词模糊"));
        assertEquals("target", top("翻訳先"));
        assertEquals("translate", top("翻译歌词"));
    }

    @Test
    public void compoundsTypedWithoutSpaces() {
        assertEquals("like", top("이중탭하트"));
        assertEquals("like", top("더블탭좋아요"));
        assertEquals("like", top("doubletapheart"));
    }

    @Test
    public void everydayWordsForWhatTheyDoNotKnowTheNameOf() {
        assertEquals("mini", top("홈화면 가사"));
        assertEquals("mini", top("home screen lyrics"));
        assertEquals("blur", top("블러"));              // the setting says 흐림 / blur
        assertEquals("font", top("폰트"));              // the setting says 글꼴 / font
        assertEquals("status", top("notification bar"));
        assertEquals("like", top("하트"));
        assertEquals("offset", top("delay"));
        assertEquals("ads", top("commercial"));
    }

    @Test
    public void koreanKeyboardAndInitials() {
        assertEquals("blur", top("qmffj"));             // 블러 typed on an English layout
        assertEquals("blur", top("ㄱㅅㅎㄹ"));           // initials of 가사 흐림
    }

    @Test
    public void relatedResultsAreMarkedAndFollowMatches() {
        List<SettingsSearch.Result> results = SEARCH.search("좋아요", 1, 3);
        assertEquals("like", results.get(0).entry.target);
        assertFalse(results.get(0).related);
        boolean seekRelated = false;
        for (SettingsSearch.Result r : results) {
            if ("seek".equals(r.entry.target)) seekRelated = r.related;
        }
        assertTrue(seekRelated);
    }

    @Test
    public void nothingForNothing() {
        assertTrue(SEARCH.search("  ", 5, 3).isEmpty());
        assertTrue(SEARCH.search("zzqx", 5, 3).isEmpty());
        assertTrue(SEARCH.search("shadow", 5, 3).isEmpty()); // "ad" inside a word
    }

    @Test
    public void termsSplitOnCommasOfEveryScript() {
        assertEquals(Arrays.asList("a", "b", "c", "d"), SettingsSearch.splitTerms("a, b，c、d"));
        assertEquals(Arrays.asList("x", "y"), SettingsSearch.mergeTerms(new ArrayList<>(Arrays.asList("x, y", "y"))));
    }
}
