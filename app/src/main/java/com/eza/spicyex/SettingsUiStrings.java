package com.eza.spicyex;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.LocaleList;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Resource-backed text resolver for the in-Spotify settings panel. */
public final class SettingsUiStrings {
    private static final String MODULE_PACKAGE = "com.eza.spicyex";

    private final Resources moduleResources;
    private final Resources englishResources;
    private final Resources resources;
    private final Map<String, Integer> ids = new HashMap<>();

    public SettingsUiStrings(Context hostContext, String language) {
        moduleResources = localizedModuleResources(hostContext, "system");
        englishResources = localizedModuleResources(hostContext, "en");
        resources = localizedModuleResources(hostContext, language);
    }

    public String appName() {
        return get("app_name", "Spicy EX");
    }

    public String section(Settings.Section section) {
        return get(SettingsUiResourceNames.section(section), section == null ? "" : section.label);
    }

    public String setting(Settings.Setting<?> setting) {
        String name = SettingsUiResourceNames.setting(setting);
        String fallback = setting == null ? "" : setting.label;
        if (setting == Settings.UI_LANGUAGE) return stringFrom(englishResources, name, fallback);
        return get(name, fallback);
    }

    public String option(Settings.StringSetting setting, String value) {
        if (setting == Settings.UI_LANGUAGE && !"system".equalsIgnoreCase(value)) {
            String localeName = localeName(value);
            if (!localeName.isEmpty()) return localeName;
        }
        String specific = SettingsUiResourceNames.option(setting == null ? "" : setting.key, value);
        String generic = SettingsUiResourceNames.option(value);
        String fallback = fallbackOptionLabel(value);
        if (has(specific)) return get(specific, fallback);
        return get(generic, fallback);
    }

    public String get(String name, String fallback) {
        int id = id(name);
        if (id == 0 || resources == null) return fallback == null ? "" : fallback;
        try {
            return resources.getString(id);
        } catch (Throwable ignored) {
            return fallback == null ? "" : fallback;
        }
    }

    public String resource(int id, String fallback) {
        if (id == 0 || resources == null) return fallback == null ? "" : fallback;
        try {
            return resources.getString(id);
        } catch (Throwable ignored) {
            return fallback == null ? "" : fallback;
        }
    }

    public String format(String name, String fallback, Object... args) {
        int id = id(name);
        if (id != 0 && resources != null) {
            try {
                return resources.getString(id, args);
            } catch (Throwable ignored) {
            }
        }
        try {
            return String.format(Locale.ROOT, fallback == null ? "" : fallback, args);
        } catch (Throwable ignored) {
            return fallback == null ? "" : fallback;
        }
    }

    public boolean has(String name) {
        return id(name) != 0;
    }

    public List<String> availableUiLanguages() {
        LinkedHashSet<String> languages = new LinkedHashSet<>();
        languages.add("system");
        languages.add("en");
        if (moduleResources != null) {
            try {
                for (String candidate : moduleResources.getAssets().getLocales()) {
                    Resources localized = localizedResources(moduleResources, candidate);
                    String code = stringFrom(localized, "settings_locale_code");
                    if (!code.isEmpty() && sameLocale(candidate, code)) languages.add(code);
                }
            } catch (Throwable ignored) {
            }
        }
        return new ArrayList<>(languages);
    }

    private int id(String name) {
        if (resources == null || name == null || name.isEmpty()) return 0;
        Integer cached = ids.get(name);
        if (cached != null) return cached;
        int value;
        try {
            value = resources.getIdentifier(name, "string", MODULE_PACKAGE);
        } catch (Throwable ignored) {
            value = 0;
        }
        ids.put(name, value);
        return value;
    }

    private static Resources localizedModuleResources(Context hostContext, String language) {
        if (hostContext == null) return References.modResources;
        try {
            Context moduleContext = MODULE_PACKAGE.equals(hostContext.getPackageName())
                    ? hostContext
                    : hostContext.createPackageContext(MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY);
            if (language == null || language.isEmpty() || "system".equalsIgnoreCase(language)) {
                return moduleContext.getResources();
            }
            Configuration configuration = new Configuration(moduleContext.getResources().getConfiguration());
            configuration.setLocales(new LocaleList(Locale.forLanguageTag(language)));
            return moduleContext.createConfigurationContext(configuration).getResources();
        } catch (Throwable ignored) {
        }

        Resources moduleResources = References.modResources;
        if (moduleResources == null) return hostContext.getResources();
        if (language == null || language.isEmpty() || "system".equalsIgnoreCase(language)) return moduleResources;
        try {
            Configuration configuration = new Configuration(moduleResources.getConfiguration());
            configuration.setLocales(new LocaleList(Locale.forLanguageTag(language)));
            return new Resources(moduleResources.getAssets(), moduleResources.getDisplayMetrics(), configuration);
        } catch (Throwable ignored) {
            return moduleResources;
        }
    }

    private String localeName(String language) {
        return stringFrom(localizedResources(moduleResources, language), "settings_locale_name");
    }

    private static Resources localizedResources(Resources base, String language) {
        if (base == null || language == null || language.isEmpty()) return base;
        try {
            Configuration configuration = new Configuration(base.getConfiguration());
            configuration.setLocales(new LocaleList(Locale.forLanguageTag(normalizeLocaleTag(language))));
            return new Resources(base.getAssets(), base.getDisplayMetrics(), configuration);
        } catch (Throwable ignored) {
            return base;
        }
    }

    private static String stringFrom(Resources resources, String name) {
        return stringFrom(resources, name, "");
    }

    private static String stringFrom(Resources resources, String name, String fallback) {
        if (resources == null) return fallback;
        try {
            int id = resources.getIdentifier(name, "string", MODULE_PACKAGE);
            return id == 0 ? fallback : resources.getString(id).trim();
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static boolean sameLocale(String candidate, String declared) {
        Locale left = Locale.forLanguageTag(normalizeLocaleTag(candidate));
        Locale right = Locale.forLanguageTag(normalizeLocaleTag(declared));
        if (!left.getLanguage().equalsIgnoreCase(right.getLanguage())) return false;
        String rightCountry = right.getCountry();
        return rightCountry.isEmpty() || rightCountry.equalsIgnoreCase(left.getCountry());
    }

    private static String normalizeLocaleTag(String value) {
        return value == null ? "" : value.replace('_', '-').replace("-r", "-");
    }

    private static String fallbackOptionLabel(String value) {
        if (value == null || value.isEmpty()) return "";
        switch (value) {
            case "system": return "System default";
            case "wordTranslit": return "Word-by-word transliteration";
            case "rrStandard": return "Standard Korean RR";
            case "rrPronunciation": return "Follow pronunciation (RR)";
            case "vnPronunciation": return "Follow pronunciation (VN)";
            case "furigana_romaji": return "Furigana + romaji";
            case "google_unofficial": return "Automatic (provider + Google)";
            case "provider": return "Lyrics provider only";
            case "en": return "English (en)";
            case "zh-CN": return "Simplified Chinese (zh-CN)";
            case "zh-TW": return "Traditional Chinese (zh-TW)";
            default:
                String spaced = value.replace('_', ' ').replace('-', ' ').trim();
                if (spaced.isEmpty()) return value;
                return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
        }
    }
}
