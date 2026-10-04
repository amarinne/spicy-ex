package com.eza.spicyex.auto;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.FieldData;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.UsingFieldData;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.io.File;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Experimental APK discovery. A match proves structure, not visibility or hook safety. */
public final class AndroidAutoResolver {
    private AndroidAutoResolver() {}
    private static String cachedIdentity;
    private static ClassLoader cachedLoader;
    private static Results cachedResults;

    public static final class Results {
        public Method weatherRenderer, legacyWeatherRenderer;
        public Method[] weatherCallers = new Method[0];
        public Constructor<?> weatherViewModelConstructor;
        public Class<?> weatherViewModel;
        public Method dashboardDispatcher;
        public Method dashboardLayout, dashboardCardsRead;
        public Object navigationSuggestionCard;
        public Method mediaComposeEntry;
        public Method mediaArtwork;
        public Method mediaArtworkContent, mediaArtworkModifier, graphicsLayer;
        public Constructor<?> floatStateConstructor;
        public Method floatStateRead, floatStateWrite;
        public Method mediaContentSlot;
        public Method mediaTextContainer;
        public Object mediaTextAlignment, zeroMediaPadding;
        public Method fillContentModifier, columnWeightModifier, mediaWrapHeightModifier;
        public Method currentScope;
        public Method invalidateScope;
        public Method markScopeUsed;
        public Method androidViewEmitter;
        public Method weatherSizeModifier;
        public Field weatherSizeValue, weatherObserverCallback;
        public Method weatherModifierAll;
        public Object androidViewNoOp;
        public Object emptyModifier;
        public Method mediaModelDump;
        public Method mediaObservableRead;
        public Method[] mediaModelGetters = new Method[0];
        public Field[] weatherCandidateFields = new Field[0];
        public Method metadataApply;
        public Method mediaPlaybackUpdate;
        public Method mediaIdentityWriter;
        public Field mediaIdentity;
        public Method fullMediaDispatcher;
        public Method[] fullMediaContent = new Method[0];
        public Method[] fullMediaArtwork = new Method[0];
        public Method fullMediaProgress, mediaThumbnail;
        public Method mediaBadgeContent, mediaBadgeListRead;
        public Class<?> mediaBadgeTextScope;
        public Method fullBadgePainter, fullBadgeDrawable, carIconRead, iconResourceId, iconResourcePackage;
        public Field fullBadgeIcon;
        /** No field is exposed until its temperature semantics are proven. */
        public final Field temperatureObservable = null;
        public final Map<String, String> diagnostics = new LinkedHashMap<>();

        public boolean weatherMapped() { return weatherRenderer != null; }
        public boolean mediaMapped() { return mediaComposeEntry != null; }
        public boolean expandedMediaMapped() { return mediaPlaybackUpdate != null; }
    }

    public static synchronized Results resolve(String apk, ClassLoader loader, int temperatureId, int explicitId) {
        File source = new File(apk);
        String identity = apk + ":" + source.length() + ":" + source.lastModified() + ":" + temperatureId + ":" + explicitId;
        if (identity.equals(cachedIdentity) && loader == cachedLoader && cachedResults != null) return cachedResults;
        Results result = new Results();
        try {
            System.loadLibrary("dexkit");
            try (DexKitBridge dex = DexKitBridge.create(apk)) {
                dex.setThreadNum(2);
                try { weather(dex, loader, temperatureId, result); }
                catch (Throwable error) { result.diagnostics.put("weatherUnresolved", error.toString()); }
                try { dashboardCards(dex, loader, result); }
                catch (Throwable error) { result.diagnostics.put("dashboardCardsUnresolved", error.toString()); }
                try { media(dex, loader, result); }
                catch (Throwable error) { result.diagnostics.put("mediaUnresolved", error.toString()); }
                try { interop(dex, loader, result); }
                catch (Throwable error) { result.diagnostics.put("interopUnresolved", error.toString()); }
                try { weatherGeometry(dex, loader, result); }
                catch (Throwable error) { result.diagnostics.put("weatherGeometryUnresolved", error.toString()); }
                try { fullTemplate(dex, loader, result); }
                catch (Throwable error) { result.diagnostics.put("fullTemplateUnresolved", error.toString()); }
                try { playerSpace(dex, loader, result); }
                catch (Throwable error) { result.diagnostics.put("playerSpaceUnresolved", error.toString()); }
                try { mediaBadges(dex, loader, explicitId, result); }
                catch (Throwable error) { result.diagnostics.put("mediaBadgesUnresolved", error.toString()); }
                try { expanded(dex, loader, result); }
                catch (Throwable error) { result.diagnostics.put("expandedMediaUnresolved", error.toString()); }
            }
        } catch (Throwable error) {
            result.diagnostics.put("resolverUnresolved", error.toString());
        }
        // Cache failures too. Repeated attachment must not rescan the APK or repeatedly load native code.
        cachedIdentity = identity;
        cachedLoader = loader;
        cachedResults = result;
        return result;
    }

    private static List<MethodData> find(DexKitBridge dex, MethodMatcher matcher) {
        return dex.findMethod(FindMethod.create().matcher(matcher));
    }

    private static MethodData one(String role, List<MethodData> methods) {
        if (methods.size() != 1) throw new IllegalStateException(role + ": expected one match, got " + methods.size());
        return methods.get(0);
    }

    private static boolean invokesOwner(MethodData method, String owner) {
        for (MethodData call : method.getInvokes()) if (owner.equals(call.getClassName())) return true;
        return false;
    }

    private static Method reflective(MethodData data, ClassLoader loader) throws Exception {
        Method method = data.getMethodInstance(loader);
        method.setAccessible(true);
        return method;
    }

    private static void weather(DexKitBridge dex, ClassLoader loader, int temperatureId, Results result) throws Exception {
        if (temperatureId == 0) throw new IllegalArgumentException("Missing current_temperature resource");
        MethodData manager = one("weatherManager", find(dex, MethodMatcher.create().usingStrings("GH.WeatherManager")));
        List<MethodData> constructors = new ArrayList<>();
        for (MethodData method : find(dex, MethodMatcher.create().usingNumbers(temperatureId))) {
            if (method.isConstructor() && method.getParamCount() == 0 && invokesOwner(method, manager.getClassName()))
                constructors.add(method);
        }
        MethodData model = one("weatherViewModel", constructors);
        MethodData renderer = one("weatherRenderer", find(dex, MethodMatcher.create()
                .usingNumbers(-42245119, -343067436).paramCount(4).returnType("void")));
        if (!Modifier.isStatic(renderer.getModifiers()) || !renderer.getUsingStrings().contains(
                "No ViewModelStoreOwner was provided via LocalViewModelStoreOwner"))
            throw new IllegalStateException("Weather renderer structure changed");
        List<MethodData> dispatchers = new ArrayList<>();
        for (MethodData caller : renderer.getCallers()) {
            if (Modifier.isStatic(caller.getModifiers()) && caller.getParamCount() == 3
                    && "void".equals(caller.getReturnTypeName())) dispatchers.add(caller);
        }
        MethodData dispatcher = one("dashboardDispatcher", dispatchers);
        if (!consumesModel(renderer, model.getClassName()))
            throw new IllegalStateException("Weather renderer has no bounded model consumer path");
        Class<?> modelClass = Class.forName(model.getClassName(), false, loader);
        Constructor<?> constructor = modelClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        Method render = reflective(renderer, loader);
        Method dispatch = reflective(dispatcher, loader);
        Field[] modelFields = modelClass.getDeclaredFields();
        for (Field field : modelFields) field.setAccessible(true);
        result.weatherViewModel = modelClass;
        result.weatherViewModelConstructor = constructor;
        result.dashboardDispatcher = dispatch;
        result.weatherRenderer = render;
        List<Method> callers = new ArrayList<>();
        for (MethodData caller : renderer.getCallers()) {
            Method method = reflective(caller, loader);
            if (!callers.contains(method)) callers.add(method);
        }
        // The low-height dashboard uses a separate weather entry with the same model.
        List<MethodData> legacy = find(dex, MethodMatcher.create()
                .paramTypes(renderer.getParamTypeNames().get(0), "int", "int", model.getClassName(),
                        renderer.getParamTypeNames().get(2), "int").returnType("void"));
        if (legacy.size() == 1 && Modifier.isStatic(legacy.get(0).getModifiers())
                && legacy.get(0).getUsingStrings().contains(
                        "No ViewModelStoreOwner was provided via LocalViewModelStoreOwner")
                && consumesModel(legacy.get(0), model.getClassName())) {
            MethodData entry = legacy.get(0);
            result.legacyWeatherRenderer = reflective(entry, loader);
            result.diagnostics.put("legacyWeatherRenderer", entry.getDescriptor());
            for (MethodData caller : entry.getCallers()) {
                Method method = reflective(caller, loader);
                if (!callers.contains(method)) callers.add(method);
            }
        } else result.diagnostics.put("legacyWeatherUnresolved", "No unique model-owned legacy entry");
        result.weatherCallers = callers.toArray(new Method[0]);
        result.diagnostics.put("weatherCallerCount", String.valueOf(callers.size()));
        result.weatherCandidateFields = modelFields;
        result.diagnostics.put("weatherRenderer", renderer.getDescriptor());
        result.diagnostics.put("weatherViewModel", model.getDescriptor());
        result.diagnostics.put("temperatureUnresolved", "Observable field semantics require runtime validation");
    }

    private static void dashboardCards(DexKitBridge dex, ClassLoader loader, Results result) throws Exception {
        if (result.dashboardDispatcher == null) return;
        MethodData layout = one("dashboardLayout", find(dex, MethodMatcher.create()
                .usingStrings("earth_minimized_card").paramCount(9).returnType("void")));
        MethodData dispatcher = one("dashboardDispatcher", find(dex, MethodMatcher.create()
                .declaredClass(result.dashboardDispatcher.getDeclaringClass().getName())
                .name(result.dashboardDispatcher.getName()).paramCount(3)));
        if (!layout.getClassName().equals(dispatcher.getClassName())
                || !layout.getParamTypeNames().get(6).equals(dispatcher.getParamTypeNames().get(1)))
            throw new IllegalStateException("Dashboard layout owner changed");
        MethodData dump = one("navigationDump", find(dex, MethodMatcher.create()
                .usingStrings("NavigationSuggestions card", "NavigationSuggestionItem(name=", "suggestionItems = ")
                .paramTypes("java.io.PrintWriter").returnType("void")));
        boolean semanticRenderer = false;
        for (UsingFieldData use : dump.getUsingFields()) {
            if (!use.getUsingType().isRead()) continue;
            for (MethodData call : dispatcher.getInvokes()) {
                if (call.getClassName().equals(use.getField().getClassName())
                        && Modifier.isStatic(call.getModifiers()) && call.getParamCount() == 4
                        && "void".equals(call.getReturnTypeName())
                        && call.getUsingStrings().contains("No ViewModelStoreOwner was provided via LocalViewModelStoreOwner")
                        && invokesOwner(call, dump.getClassName())) semanticRenderer = true;
            }
        }
        if (!semanticRenderer) throw new IllegalStateException("Navigation renderer is not a dashboard card");
        List<FieldData> candidates = new ArrayList<>();
        for (UsingFieldData use : dispatcher.getUsingFields()) {
            FieldData field = use.getField();
            if (!use.getUsingType().isRead() || !field.getClassName().equals(field.getTypeName())
                    || distinctReaders(field) != 2) continue;
            for (MethodData reader : field.getReaders()) {
                if (!"invoke".equals(reader.getMethodName()) || reader.getParamCount() != 3
                        || !"java.lang.Object".equals(reader.getReturnTypeName())) continue;
                // The suggestion producer also reads the weather singleton. Other dashboard
                // singletons do not share this producer; weather has an extra layout reader.
                for (UsingFieldData shared : reader.getUsingFields()) {
                    FieldData other = shared.getField();
                    if (!shared.getUsingType().isRead() || other.equals(field)
                            || !other.getClassName().equals(other.getTypeName())) continue;
                    for (MethodData otherReader : other.getReaders())
                        if (otherReader.getDescriptor().equals(dispatcher.getDescriptor()) && !candidates.contains(field)) candidates.add(field);
                }
            }
        }
        if (candidates.size() != 1) throw new IllegalStateException("Navigation singleton ambiguous: " + candidates.size());
        List<MethodData> listReads = new ArrayList<>();
        for (MethodData call : layout.getInvokes()) {
            if (!Modifier.isStatic(call.getModifiers()) || call.getParamCount() != 1
                    || !"java.util.List".equals(call.getReturnTypeName()) || call.getInvokes().size() != 1) continue;
            MethodData getter = call.getInvokes().get(0);
            if (getter.getParamCount() == 0 && "java.lang.Object".equals(getter.getReturnTypeName())) listReads.add(call);
        }
        MethodData read = one("dashboardCardsRead", listReads);
        Field singleton = candidates.get(0).getFieldInstance(loader);
        if (!Modifier.isStatic(singleton.getModifiers()) || !Modifier.isFinal(singleton.getModifiers()))
            throw new IllegalStateException("Navigation card is not a constant singleton");
        singleton.setAccessible(true);
        Object card = singleton.get(null);
        boolean cardPayload = false;
        for (Field field : result.dashboardDispatcher.getParameterTypes()[0].getDeclaredFields())
            if (!Modifier.isStatic(field.getModifiers()) && card != null && field.getType().isInstance(card)) cardPayload = true;
        if (!cardPayload) throw new IllegalStateException("Navigation singleton is not a dispatcher payload");
        result.dashboardLayout = reflective(layout, loader);
        result.dashboardCardsRead = reflective(read, loader);
        result.navigationSuggestionCard = card;
        result.diagnostics.put("dashboardLayout", layout.getDescriptor());
        result.diagnostics.put("dashboardCardsRead", read.getDescriptor());
        result.diagnostics.put("navigationSuggestionCard", candidates.get(0).getDescriptor());
    }

    private static int distinctReaders(FieldData field) {
        HashSet<String> descriptors = new HashSet<>();
        for (MethodData reader : field.getReaders()) descriptors.add(reader.getDescriptor());
        return descriptors.size();
    }

    private static boolean consumesModel(MethodData renderer, String model) {
        ArrayDeque<MethodData> queue = new ArrayDeque<>();
        ArrayDeque<Integer> depths = new ArrayDeque<>();
        queue.add(renderer);
        depths.add(0);
        HashSet<String> visited = new HashSet<>();
        while (!queue.isEmpty()) {
            MethodData current = queue.remove();
            int depth = depths.remove();
            if (!visited.add(current.getDescriptor())) continue;
            if (visited.size() > 128) throw new IllegalStateException("Weather graph budget exceeded");
            for (UsingFieldData use : current.getUsingFields())
                if (use.getUsingType().isRead() && model.equals(use.getField().getClassName())) return true;
            if (depth >= 6) continue;
            for (MethodData call : current.getInvokes()) {
                if (call.isConstructor()) {
                    for (MethodData method : call.getDeclaredClass().getMethods()) {
                        if ("invoke".equals(method.getMethodName())) {
                            queue.add(method);
                            depths.add(depth + 1);
                        }
                    }
                } else if (call.getParamTypeNames().contains(model)) {
                    queue.add(call);
                    depths.add(depth + 1);
                }
            }
        }
        return false;
    }

    private static void media(DexKitBridge dex, ClassLoader loader, Results result) throws Exception {
        MethodData compose = one("mediaCompose", find(dex, MethodMatcher.create().usingStrings("GH.ComposeMediaPlayer")));
        MethodData dump = one("mediaModelDump", find(dex, MethodMatcher.create().usingStrings("\n         mediaApp: ")));
        List<MethodData> entries = new ArrayList<>();
        for (MethodData method : compose.getDeclaredClass().getMethods()) {
            if (Modifier.isStatic(method.getModifiers()) && "void".equals(method.getReturnTypeName())
                    && method.getParamCount() >= 14 && method.getParamCount() <= 18
                    && method.getParamTypeNames().contains(dump.getClassName())) entries.add(method);
        }
        MethodData entry = one("mediaComposeEntry", entries);
        if (!invokesOwner(entry, dump.getClassName())) throw new IllegalStateException("Media entry has no model calls");
        MethodData artwork = one("mediaArtwork", find(dex, MethodMatcher.create()
                .usingStrings("albumArt").usingNumbers(1865263564)));
        if (!artwork.getClassName().equals(compose.getClassName()) || artwork.getParamCount() != 7
                || !"void".equals(artwork.getReturnTypeName())
                || !artwork.getParamTypeNames().get(0).equals("android.graphics.Bitmap"))
            throw new IllegalStateException("Media artwork shape changed");
        Method parent = reflective(entry, loader);
        Method art = reflective(artwork, loader);
        Method modelDump = reflective(dump, loader);
        Map<String, MethodData> readers = new LinkedHashMap<>();
        Map<String, MethodData> getters = new LinkedHashMap<>();
        for (MethodData call : dump.getInvokes()) {
            if (call.getParamCount() == 0 && !Modifier.isStatic(call.getModifiers())) {
                if ("java.lang.Object".equals(call.getReturnTypeName())) readers.put(call.getDescriptor(), call);
                else if (dump.getClassName().equals(call.getClassName()) && !"void".equals(call.getReturnTypeName()))
                    getters.put(call.getDescriptor(), call);
            }
        }
        MethodData readerData = one("mediaObservableRead", new ArrayList<>(readers.values()));
        Method reader = reflective(readerData, loader);
        List<Method> modelGetters = new ArrayList<>();
        for (MethodData getter : getters.values()) {
            if (getter.getReturnTypeName().equals(readerData.getClassName())) modelGetters.add(reflective(getter, loader));
        }
        if (modelGetters.isEmpty()) throw new IllegalStateException("Media model has no observable getters");
        result.mediaModelDump = modelDump;
        result.mediaObservableRead = reader;
        result.mediaModelGetters = modelGetters.toArray(new Method[0]);
        List<MethodData> slots = new ArrayList<>();
        for (MethodData candidate : find(dex, MethodMatcher.create().usingNumbers(-428056636))) {
            if (candidate.getClassName().equals(entry.getClassName()) && candidate.getParamCount() == 21
                    && candidate.getParamTypeNames().get(0).equals(candidate.getParamTypeNames().get(1))
                    && candidate.getParamTypeNames().get(1).equals(candidate.getParamTypeNames().get(2))) slots.add(candidate);
        }
        MethodData slot = one("mediaContentSlot", slots);
        List<MethodData> containers = new ArrayList<>();
        for (MethodData call : slot.getInvokes()) {
            if (call.getClassName().equals(slot.getClassName()) && call.getParamCount() == 15
                    && call.getParamTypeNames().get(0).equals(slot.getParamTypeNames().get(0))
                    && call.getParamTypeNames().get(1).equals(slot.getParamTypeNames().get(1))
                    && call.getParamTypeNames().get(2).equals(slot.getParamTypeNames().get(3))) containers.add(call);
        }
        MethodData container = one("mediaTextContainer", containers);
        String modifierType = slot.getParamTypeNames().get(12);
        HashSet<String> modifierOwners = new HashSet<>();
        for (MethodData call : container.getInvokes()) {
            if (call.getParamCount() == 1 && call.getReturnTypeName().equals(modifierType)
                    && call.getParamTypeNames().get(0).equals(modifierType)) modifierOwners.add(call.getClassName());
        }
        List<MethodData> fills = new ArrayList<>();
        for (MethodData candidate : find(dex, MethodMatcher.create().usingNumbers(3, 1.0f))) {
            if (modifierOwners.contains(candidate.getClassName()) && candidate.getReturnTypeName().equals(modifierType)
                    && candidate.getParamTypeNames().equals(Arrays.asList(modifierType, "float"))) fills.add(candidate);
        }
        MethodData fill = one("fillContentModifier", fills);
        List<FieldData> alignments = new ArrayList<>();
        for (UsingFieldData use : slot.getUsingFields())
            if (use.getUsingType().isRead() && use.getField().getTypeName().equals(slot.getParamTypeNames().get(16)))
                alignments.add(use.getField());
        if (alignments.size() != 1) throw new IllegalStateException("Media alignment ambiguous");
        Field alignment = alignments.get(0).getFieldInstance(loader);
        alignment.setAccessible(true);
        Object textAlignment = alignment.get(null);
        List<MethodData> paddingConstructors = new ArrayList<>();
        for (MethodData call : container.getInvokes())
            if (call.isConstructor() && call.getParamTypeNames().equals(Arrays.asList("float", "float", "float", "float")))
                paddingConstructors.add(call);
        Constructor<?> padding = one("mediaPadding", paddingConstructors).getConstructorInstance(loader);
        padding.setAccessible(true);
        result.mediaTextAlignment = textAlignment;
        result.zeroMediaPadding = padding.newInstance(0f, 0f, 0f, 0f);
        result.mediaTextContainer = reflective(container, loader);
        result.fillContentModifier = reflective(fill, loader);
        List<MethodData> weights = new ArrayList<>();
        for (MethodData candidate : find(dex, MethodMatcher.create().usingStrings("invalid weight; must be greater than zero")
                .paramTypes(modifierType, "float", "boolean").returnType(modifierType)))
            if (Modifier.isStatic(candidate.getModifiers())) weights.add(candidate);
        result.columnWeightModifier = reflective(one("columnWeightModifier", weights), loader);
        List<MethodData> wraps = new ArrayList<>();
        for (MethodData call : container.getInvokes())
            if (Modifier.isStatic(call.getModifiers()) && call.getReturnTypeName().equals(modifierType)
                    && call.getParamTypeNames().equals(Arrays.asList(modifierType, slot.getParamTypeNames().get(16), "boolean")))
                wraps.add(call);
        result.mediaWrapHeightModifier = reflective(one("mediaWrapHeightModifier", wraps), loader);
        result.diagnostics.put("mediaTextContainer", container.getDescriptor());
        result.diagnostics.put("fillContentModifier", fill.getDescriptor());
        List<MethodData> scopeEnds = new ArrayList<>();
        for (MethodData call : slot.getInvokes()) {
            if (call.getClassName().equals(slot.getParamTypeNames().get(17)) && call.getParamCount() == 0
                    && !call.getReturnTypeName().equals("void") && !call.getReturnTypeName().equals("boolean")) scopeEnds.add(call);
        }
        MethodData scopeEnd = one("scopeEnd", scopeEnds);
        List<MethodData> scopeReaders = new ArrayList<>(), invalidators = new ArrayList<>();
        for (MethodData candidate : find(dex, MethodMatcher.create().paramCount(0).returnType(scopeEnd.getReturnTypeName()))) {
            for (MethodData call : candidate.getInvokes()) if (call.getClassName().equals("java.util.ArrayList")
                    && call.getMethodName().equals("get")) { scopeReaders.add(candidate); break; }
        }
        for (MethodData candidate : find(dex, MethodMatcher.create().declaredClass(scopeEnd.getReturnTypeName())
                .paramCount(0).returnType("void"))) {
            for (MethodData call : candidate.getInvokes()) if (call.getParamCount() == 2
                    && call.getParamTypeNames().get(0).equals(scopeEnd.getReturnTypeName())
                    && call.getParamTypeNames().get(1).equals("java.lang.Object")
                    && call.getReturnTypeName().equals("int")) { invalidators.add(candidate); break; }
        }
        result.currentScope = reflective(one("currentScope", scopeReaders), loader);
        result.invalidateScope = reflective(one("invalidateScope", invalidators), loader);
        List<MethodData> markers = new ArrayList<>();
        for (MethodData candidate : find(dex, MethodMatcher.create().declaredClass(scopeEnd.getReturnTypeName())
                .paramCount(0).returnType("void").usingNumbers(1))) {
            if (candidate.getInvokes().isEmpty()) markers.add(candidate);
        }
        result.markScopeUsed = reflective(one("markScopeUsed", markers), loader);


        result.mediaContentSlot = reflective(slot, loader);
        result.diagnostics.put("mediaContentSlot", slot.getDescriptor());
        result.mediaArtwork = art;
        result.mediaComposeEntry = parent;
        result.diagnostics.put("mediaComposeEntry", entry.getDescriptor());
        result.diagnostics.put("mediaArtwork", artwork.getDescriptor());
        // The background lambda owns the whole card artwork, including its tint layer.
        List<MethodData> backgrounds = new ArrayList<>();
        for (MethodData constructor : artwork.getInvokes()) {
            if (!"<init>".equals(constructor.getMethodName())) continue;
            for (MethodData method : constructor.getDeclaredClass().getMethods()) {
                if (!"invoke".equals(method.getMethodName()) || method.getParamCount() != 3) continue;
                for (MethodData call : method.getInvokes()) {
                    if (call.getParamCount() == 5 && call.getParamTypeNames().get(0).equals("android.graphics.Bitmap")
                            && call.getClassName().equals(artwork.getClassName())) backgrounds.add(method);
                }
            }
        }
        MethodData background = one("mediaArtworkContent", backgrounds);
        List<MethodData> modifiers = new ArrayList<>();
        for (MethodData call : background.getInvokes()) {
            if (call.getReturnTypeName().equals(modifierType)
                    && call.getParamTypeNames().equals(Arrays.asList(slot.getParamTypeNames().get(17), modifierType))) modifiers.add(call);
        }
        List<MethodData> layers = new ArrayList<>();
        for (MethodData method : find(dex, MethodMatcher.create().returnType(modifierType).paramCount(10)
                .usingNumbers(1024, 65536))) {
            List<String> types = method.getParamTypeNames();
            if (types.get(0).equals(modifierType) && types.subList(1, 6).equals(Arrays.asList("float", "float", "float", "float", "float"))
                    && types.subList(7, 10).equals(Arrays.asList("boolean", "int", "int"))) layers.add(method);
        }
        result.mediaArtworkContent = reflective(background, loader);
        result.mediaArtworkModifier = reflective(one("mediaArtworkModifier", modifiers), loader);
        result.graphicsLayer = reflective(one("graphicsLayer", layers), loader);
        MethodData floatState = one("floatState", find(dex, MethodMatcher.create().usingStrings("MutableFloatState(value=")));
        Class<?> stateType = Class.forName(floatState.getClassName(), false, loader);
        result.floatStateConstructor = stateType.getDeclaredConstructor(float.class);
        result.floatStateConstructor.setAccessible(true);
        List<MethodData> reads = new ArrayList<>(), writes = new ArrayList<>();
        for (MethodData method : floatState.getDeclaredClass().getMethods()) {
            if (method.getParamCount() == 0 && method.getReturnTypeName().equals("float")) reads.add(method);
            if (!"<init>".equals(method.getMethodName()) && method.getParamTypeNames().equals(Arrays.asList("float"))
                    && method.getReturnTypeName().equals("void")) writes.add(method);
        }
        result.floatStateRead = reflective(one("floatStateRead", reads), loader);
        result.floatStateWrite = reflective(one("floatStateWrite", writes), loader);
        result.diagnostics.put("mediaArtworkContent", background.getDescriptor());
    }

    private static void interop(DexKitBridge dex, ClassLoader loader, Results result) throws Exception {
        List<MethodData> emitters = new ArrayList<>();
        for (MethodData method : find(dex, MethodMatcher.create().usingNumbers(-1783766393))) {
            if (method.getParamCount() == 6 && "void".equals(method.getReturnTypeName())
                    && method.getParamTypeNames().get(0).equals(method.getParamTypeNames().get(2))
                    && "int".equals(method.getParamTypeNames().get(4)) && "int".equals(method.getParamTypeNames().get(5))) emitters.add(method);
        }
        MethodData emitter = one("androidViewEmitter", emitters);
        boolean core = false;
        for (MethodData call : emitter.getInvokes()) if (call.getClassName().equals(emitter.getClassName())
                && call.getParamCount() == 6 && call.getUsingStrings().contains("Invalid applier")) core = true;
        if (!core) throw new IllegalStateException("AndroidView core edge changed");
        Method reflected = reflective(emitter, loader);
        Class<?> factory = reflected.getParameterTypes()[0], modifier = reflected.getParameterTypes()[1];
        for (Field field : reflected.getDeclaringClass().getDeclaredFields()) if (Modifier.isStatic(field.getModifiers()) && factory.equals(field.getType())) {
            field.setAccessible(true); result.androidViewNoOp = field.get(null);
        }
        for (Field field : modifier.getDeclaredFields()) if (Modifier.isStatic(field.getModifiers()) && modifier.isAssignableFrom(field.getType())) {
            field.setAccessible(true); result.emptyModifier = field.get(null);
        }
        if (result.androidViewNoOp == null || result.emptyModifier == null) throw new IllegalStateException("Interop defaults unresolved");
        result.androidViewEmitter = reflected;
        result.diagnostics.put("androidViewEmitter", emitter.getDescriptor());
    }

    private static void weatherGeometry(DexKitBridge dex, ClassLoader loader, Results result) throws Exception {
        if (result.androidViewEmitter == null) return;
        String function = result.androidViewEmitter.getParameterTypes()[0].getName();
        String modifier = result.androidViewEmitter.getParameterTypes()[1].getName();
        // Compose's size observer starts with IntSize(Int.MIN_VALUE, Int.MIN_VALUE).
        List<MethodData> nodes = new ArrayList<>();
        for (MethodData method : find(dex, MethodMatcher.create().paramTypes(function)
                .usingNumbers(-9223372034707292160L))) if (method.isConstructor()) nodes.add(method);
        MethodData node = one("sizeObserverNode", nodes);
        MethodData measure = one("sizeObserverMeasure", find(dex, MethodMatcher.create()
                .declaredClass(node.getClassName()).paramTypes("long").returnType("void")));
        List<MethodData> boxes = new ArrayList<>(), creators = new ArrayList<>();
        for (MethodData call : measure.getInvokes())
            if (call.isConstructor() && call.getParamTypeNames().equals(Arrays.asList("long"))) boxes.add(call);
        if (!invokesOwner(measure, function)) throw new IllegalStateException("Size observer callback changed");
        for (MethodData caller : node.getCallers()) if (caller.getParamCount() == 0) creators.add(caller);
        String element = one("sizeObserverElement", creators).getClassName();
        List<MethodData> modifiers = new ArrayList<>();
        for (MethodData method : find(dex, MethodMatcher.create().paramTypes(modifier, function).returnType(modifier)))
            if (Modifier.isStatic(method.getModifiers()) && invokesOwner(method, element)) modifiers.add(method);
        List<Field> fields = new ArrayList<>();
        for (Field field : Class.forName(one("sizeObserverValue", boxes).getClassName(), false, loader).getDeclaredFields())
            if (!Modifier.isStatic(field.getModifiers()) && field.getType() == long.class) fields.add(field);
        if (fields.size() != 1) throw new IllegalStateException("Size observer value changed");
        List<Field> callbacks = new ArrayList<>();
        for (Field field : Class.forName(element, false, loader).getDeclaredFields())
            if (!Modifier.isStatic(field.getModifiers()) && field.getType().getName().equals(function)) callbacks.add(field);
        if (callbacks.size() != 1) throw new IllegalStateException("Size observer element callback changed");
        List<Method> predicates = new ArrayList<>();
        for (Method method : result.androidViewEmitter.getParameterTypes()[1].getMethods())
            if (!Modifier.isStatic(method.getModifiers()) && method.getReturnType() == boolean.class
                    && method.getParameterCount() == 1 && method.getParameterTypes()[0].getName().equals(function)) predicates.add(method);
        if (predicates.size() != 1) throw new IllegalStateException("Modifier element traversal changed");
        callbacks.get(0).setAccessible(true);
        predicates.get(0).setAccessible(true);
        fields.get(0).setAccessible(true);
        result.weatherSizeValue = fields.get(0);
        result.weatherObserverCallback = callbacks.get(0);
        result.weatherModifierAll = predicates.get(0);
        MethodData factory = one("sizeObserverModifier", modifiers);
        result.weatherSizeModifier = reflective(factory, loader);
        result.diagnostics.put("weatherSizeModifier", factory.getDescriptor());
        result.diagnostics.put("weatherObserverCallback", callbacks.get(0).toString());
        result.diagnostics.put("weatherModifierAll", predicates.get(0).toString());
    }

    private static void fullTemplate(DexKitBridge dex, ClassLoader loader, Results result) throws Exception {
        MethodData dispatcher = one("fullMediaDispatcher", find(dex, MethodMatcher.create().usingStrings(
                "contentWidth=%s, contentHeight=%s. Using media playback layout with media width=%s and media height=%s")));
        if (dispatcher.getParamCount() != 6 || !dispatcher.getParamTypeNames().get(0).equals("float")
                || !dispatcher.getParamTypeNames().get(1).equals("float"))
            throw new IllegalStateException("Full dispatcher shape changed");
        String model = dispatcher.getParamTypeNames().get(2);
        one("fullModel", find(dex, MethodMatcher.create().declaredClass(model).usingStrings("MediaPlaybackUiModel(header=")));
        List<Method> content = new ArrayList<>(), artwork = new ArrayList<>();
        HashSet<String> owners = new HashSet<>();
        for (int key : new int[]{-860189913, 460604537}) {
            MethodData method = one("fullContent" + key, find(dex, MethodMatcher.create().usingNumbers(key)));
            if (method.getParamCount() != 4 || !method.getParamTypeNames().get(0).equals(model)
                    || !method.getParamTypeNames().get(1).equals(dispatcher.getParamTypeNames().get(3)))
                throw new IllegalStateException("Full metadata shape changed");
            owners.add(method.getClassName()); content.add(reflective(method, loader));
        }
        for (int key : new int[]{8693722, 172151532, 371671564}) {
            MethodData method = one("fullArtwork" + key, find(dex, MethodMatcher.create().usingNumbers(key)));
            if (!owners.contains(method.getClassName()) || (method.getParamCount() != 3 && method.getParamCount() != 4)
                    || !method.getParamTypeNames().get(method.getParamCount() - 2).equals(dispatcher.getParamTypeNames().get(4)))
                throw new IllegalStateException("Full artwork shape changed");
            artwork.add(reflective(method, loader));
        }
        result.fullMediaDispatcher = reflective(dispatcher, loader);
        result.fullMediaContent = content.toArray(new Method[0]);
        result.fullMediaArtwork = artwork.toArray(new Method[0]);
        result.diagnostics.put("fullMediaDispatcher", dispatcher.getDescriptor());
    }

    private static void playerSpace(DexKitBridge dex, ClassLoader loader, Results result) throws Exception {
        if (result.fullMediaDispatcher == null || result.mediaContentSlot == null) return;
        MethodData progressModel = one("progressModel", find(dex, MethodMatcher.create()
                .usingStrings("PlaybackProgressUiModel(timeFlow=")));
        MethodData progress = one("fullMediaProgress", find(dex, MethodMatcher.create()
                .usingStrings("slider").usingNumbers(1804370868).paramCount(4).returnType("void")));
        if (!Modifier.isStatic(progress.getModifiers())
                || !progress.getParamTypeNames().get(0).equals(progressModel.getClassName())
                || !progress.getParamTypeNames().get(2).equals(result.fullMediaDispatcher.getParameterTypes()[4].getName()))
            throw new IllegalStateException("Full progress shape changed");
        result.fullMediaProgress = reflective(progress, loader);
        result.diagnostics.put("fullMediaProgress", progress.getDescriptor());
        MethodData thumbnail = one("mediaThumbnail", find(dex, MethodMatcher.create()
                .usingNumbers(1454845343).paramCount(5).returnType("void")));
        if (!Modifier.isStatic(thumbnail.getModifiers())
                || !thumbnail.getClassName().equals(result.mediaContentSlot.getDeclaringClass().getName())
                || !thumbnail.getParamTypeNames().get(0).equals("android.graphics.Bitmap")
                || !thumbnail.getParamTypeNames().get(3).equals(result.mediaContentSlot.getParameterTypes()[17].getName()))
            throw new IllegalStateException("Compact thumbnail shape changed");
        result.mediaThumbnail = reflective(thumbnail, loader);
        result.diagnostics.put("mediaThumbnail", thumbnail.getDescriptor());
    }

    private static void mediaBadges(DexKitBridge dex, ClassLoader loader, int explicitId, Results result) throws Exception {
        if (explicitId == 0) throw new IllegalStateException("Explicit drawable resource missing");
        one("explicitMetadataProducer", find(dex, MethodMatcher.create()
                .usingStrings("android.media.IS_EXPLICIT").usingNumbers(explicitId)));
        MethodData content = one("mediaBadgeContent", find(dex, MethodMatcher.create()
                .usingNumbers(-1612190263, 168862689)));
        if (!content.getParamTypeNames().equals(Arrays.asList("java.lang.Object", "java.lang.Object", "java.lang.Object"))
                || !"java.lang.Object".equals(content.getReturnTypeName()))
            throw new IllegalStateException("Media badge callback shape changed");
        List<MethodData> readers = new ArrayList<>(), text = new ArrayList<>();
        String composer = result.mediaContentSlot.getParameterTypes()[17].getName();
        for (MethodData call : content.getInvokes()) {
            if (call.getParamCount() == 1 && "java.util.List".equals(call.getReturnTypeName())) readers.add(call);
            if (call.getParamCount() == 6 && call.getParamTypeNames().get(0).equals("java.lang.String")
                    && call.getParamTypeNames().get(3).equals(composer) && "void".equals(call.getReturnTypeName())) text.add(call);
        }
        Method callback = reflective(content, loader);
        Method reader = reflective(one("mediaBadgeListRead", readers), loader);
        Class<?> textScope = reflective(one("mediaBadgeText", text), loader).getDeclaringClass();
        result.mediaBadgeContent = callback;
        result.mediaBadgeListRead = reader;
        result.mediaBadgeTextScope = textScope;
        result.diagnostics.put("mediaBadgeContent", content.getDescriptor());
        MethodData painter = one("fullBadgePainter", find(dex, MethodMatcher.create().usingNumbers(-1585463547)));
        if (!painter.getParamTypeNames().equals(Arrays.asList("int", composer, "int")))
            throw new IllegalStateException("Car icon painter shape changed");
        List<FieldData> icons = new ArrayList<>();
        for (FieldData field : painter.getDeclaredClass().getFields())
            if (field.getTypeName().equals("androidx.car.app.model.CarIcon")) icons.add(field);
        if (icons.size() != 1) throw new IllegalStateException("Car icon field ambiguous");
        Field icon = icons.get(0).getFieldInstance(loader); icon.setAccessible(true);
        Method getIcon = icon.getType().getMethod("getIcon");
        Method resourceId = reflective(one("iconResourceId", find(dex, MethodMatcher.create()
                .declaredClass(getIcon.getReturnType().getName()).usingStrings("called getResId() on "))), loader);
        Method resourcePackage = reflective(one("iconResourcePackage", find(dex, MethodMatcher.create()
                .declaredClass(getIcon.getReturnType().getName()).usingStrings("called getResPackage() on "))), loader);
        List<MethodData> drawables = new ArrayList<>();
        for (MethodData call : painter.getInvokes())
            if (call.getReturnTypeName().equals("android.graphics.drawable.Drawable") && call.getParamCount() == 3
                    && call.getParamTypeNames().get(1).equals("androidx.car.app.model.CarIcon")) drawables.add(call);
        Method drawable = reflective(one("fullBadgeDrawable", drawables), loader);
        result.fullBadgePainter = reflective(painter, loader);
        result.fullBadgeDrawable = drawable;
        result.fullBadgeIcon = icon; result.carIconRead = getIcon;
        result.iconResourceId = resourceId; result.iconResourcePackage = resourcePackage;
        result.diagnostics.put("fullBadgePainter", painter.getDescriptor());
    }

    private static void expanded(DexKitBridge dex, ClassLoader loader, Results result) throws Exception {
        String playbackOwner = "com.google.android.apps.auto.components.ui.media.MediaPlaybackView";
        MethodData metadata = one("metadataApply", find(dex, MethodMatcher.create()
                .usingStrings("No content image supplied. Clearing the image view.")));
        if (!metadata.getClassName().equals("com.google.android.apps.auto.components.metadataview.MetadataView"))
            throw new IllegalStateException("Metadata owner changed");
        List<MethodData> updates = new ArrayList<>();
        for (MethodData caller : metadata.getCallers()) if (playbackOwner.equals(caller.getClassName())) updates.add(caller);
        MethodData update = one("mediaPlaybackUpdate", updates);
        List<FieldData> identities = new ArrayList<>();
        for (FieldData field : update.getDeclaredClass().getFields())
            if ("android.content.ComponentName".equals(field.getTypeName())) identities.add(field);
        if (identities.size() != 1) throw new IllegalStateException("Media identity field ambiguous");
        FieldData identity = identities.get(0);
        List<MethodData> writers = new ArrayList<>();
        for (MethodData writer : identity.getWriters()) {
            if (writer.getParamTypeNames().equals(Arrays.asList("android.content.ComponentName", "android.content.ComponentName")))
                writers.add(writer);
        }
        MethodData writer = one("mediaIdentityWriter", writers);
        Method apply = reflective(metadata, loader);
        Method playback = reflective(update, loader);
        Method identityWriter = reflective(writer, loader);
        Field field = Class.forName(identity.getClassName(), false, loader).getDeclaredField(identity.getName());
        field.setAccessible(true);
        result.metadataApply = apply;
        result.mediaIdentity = field;
        result.mediaIdentityWriter = identityWriter;
        result.mediaPlaybackUpdate = playback;
        result.diagnostics.put("mediaPlaybackUpdate", update.getDescriptor());
        result.diagnostics.put("expandedActivationUnresolved", "Legacy native component not verified on current template");
    }
}
