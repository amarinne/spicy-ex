# Readings and transliteration

## Offline coverage

Spicy EX supports offline, deterministic readings for these languages and scripts. Japanese requires
one language-model download in Settings. The other offline engines listed below are bundled.

| Language or script | Output | Example |
|---|---|---|
| Japanese | Furigana, romaji, or both | `君` → `きみ` · `kimi` |
| Mandarin | Pinyin | `中国` → `zhōng guó` |
| Cantonese | Jyutping | `香港` → `hoeng1 gong2` |
| Korean | Revised Romanization or pronunciation | `그대` → `geudae` |
| Cyrillic | Latin-letter reading | `Привет` → `Privet` |
| Greek | Latin-letter reading | `Αγάπη` → `Agapi` |

Optional AI romanization can cover Thai, Arabic, Hindi, Tamil, and other languages.

<details>
<summary>Japanese romaji</summary>

Romaji is [Hepburn-based](https://en.wikipedia.org/wiki/Hepburn_romanization). Long vowels use spellings such as `ou` and `uu` instead of macrons.

| Particle | Romaji |
|---|---|
| は | `ha → wa` |
| へ | `he → e` |
| を | `wo → wo` |

</details>

## eza式

eza式 (eza shiki) is my personal customized system for Mandarin, Cantonese, and Korean romanization, based on Vietnamese orthography.

<details>
<summary>Mandarin</summary>

| Pinyin | eza式 |
|---|---|
| `d` · `t` | `t` · `th` |
| `g` · `k` | `k` · `kh` |
| `c` · `j` · `q` · `x` | `ts` · `z` · `ch` · `sh` |
| `zi` · `ci` · `si` | `zư` · `tsư` · `sư` |
| `zhi` · `chi` · `shi` · `ri` | `zhư` · `chư` · `shư` · `rư` |
| `e` · `en` · `eng` · `ei` | `ơ` · `ân` · `âng` · `êi` |
| `ü` · `ju` · `qu` · `xu` | `yu` · `zyu` · `chyu` · `shyu` |
| `bo` · `po` · `mo` · `fo` | `buo` · `puo` · `muo` · `fuo` |
| `iu` · `ui` · `un` | `iou` · `uêi` · `uân` |
| `ian` | `ien` |
| `wei` · `wen` · `weng` | `wêi` · `wuân` · `wuâng` |
| `ueng` | `uâng` |

#### Tones

| Tone | Pinyin | eza式 |
|---|---|---|
| 1 | `ā` | `a` |
| 2 | `á` | `á` |
| 3 | `ǎ` | `ả` |
| 4 | `à` | `à` |
| Neutral | `a` | `ạ` |

</details>

<details>
<summary>Cantonese</summary>

| Jyutping | eza式 |
|---|---|
| `d` · `t` | `t` · `th` |
| `g` · `k` | `k` · `kh` |
| `c` · `j` | `ts` · `y` |
| `gw` · `kw` · `kwaa` | `qu` · `khw` · `khoa` |
| `-k` | `-c` |
| `aa` · `a` · `aai` · `ai` | `a` · `ă` · `ai` · `ay` |
| `aau` | `ao` |
| `aam` · `am` | `am` · `ăm` |
| `aan` · `an` | `an` · `ăn` |
| `aang` · `ang` | `ang` · `ăng` |
| `aap` · `ap` | `ap` · `ăp` |
| `aat` · `at` | `at` · `ăt` |
| `aak` · `ak` | `ac` · `ăc` |
| `ek` · `ik` · `ok` · `uk` | `ec` · `ic` · `oc` · `uc` |
| `oe` · `oeng` · `oet` · `oek` | `ơo` · `ơong` · `ơot` · `ơoc` |
| `eoi` · `eon` · `eot` | `ơi` · `ơn` · `ơt` |

#### Tones

| Jyutping tone | eza式 |
|---|---|
| `1` | `ã` |
| `2` | `á` |
| `3` | `a` |
| `4` | `à` |
| `5` | `ả` |
| `6` | `ạ` |

</details>

<details>
<summary>Korean</summary>

Korean eza式 romanizes the G2P result with the vowel spellings below.

The pass applies final-consonant neutralization, liaison, ㅎ aspiration and deletion, palatalization, nasalization, ㄴ/ㄹ assimilation, tensification, and `ㅢ`/`져`/`쪄`/`쳐` reductions.

[Standard Revised Romanization](https://www.korean.go.kr/front_eng/roman/roman_01.do) also reflects sound changes.
It keeps `ㅢ → ui` even in possessive `의` and does not mark induced tensification.

| Revised Romanization | eza式 |
|---|---|
| `ㅐ ae` · `ㅔ e` | `ê` · `ê` |
| `ㅒ yae` · `ㅖ ye` | `yê` · `yê` |
| `ㅓ eo` · `ㅗ o` | `o` · `ô` |
| `ㅕ yeo` · `ㅛ yo` | `yo` · `yô` |
| `ㅙ wae` · `ㅚ oe` · `ㅞ we` | `wê` · `wê` · `wê` |
| `ㅡ eu` · `ㅢ ui` | `ư` · `ưi` |
| `ㅢ ui after a consonant` | `i` |
| 나의 `naui` (possessive `의`) | `naê` |
| `hanguk` · `gangyeo` | `han-guk` · `gang-yo` |
| 국밥 `gukbap` | `gukppap` |
| 학생 `haksaeng` | `hakssêng` |

</details>
