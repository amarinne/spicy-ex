package com.eza.spicyex.settings.registry;

/** A visibility rule evaluated against one immutable render snapshot. */
@FunctionalInterface
public interface Visibility {
    Visibility ALWAYS = context -> true;
    Visibility NEVER = context -> false;

    boolean test(UiContext context);
}
