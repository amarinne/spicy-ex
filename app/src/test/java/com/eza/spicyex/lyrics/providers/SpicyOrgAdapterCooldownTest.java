package com.eza.spicyex.lyrics.providers;

import com.eza.spicyex.testsupport.FakeAndroidContext;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class SpicyOrgAdapterCooldownTest {
    private final FakeAndroidContext context = new FakeAndroidContext();

    @Before public void clearProcessCooldown() throws Exception {
        for (String name : new String[] {"cooldownEpoch", "cooldownUntilMs"}) {
            java.lang.reflect.Field field = SpicyOrgAdapter.class.getDeclaredField(name);
            field.setAccessible(true);
            field.setLong(null, -1L);
        }
    }

    private void rotate(long epoch) {
        context.getSharedPreferences("SpotifyPlusSpicyOrgKeyState", 0)
                .edit().putLong("epoch", epoch).apply();
    }

    @Test public void rotatedKeyUsesItsOwnDeadline() {
        rotate(101);
        SpicyOrgAdapter.recordCooldown(context, 101, 3600, 1000);
        rotate(102);
        assertFalse(SpicyOrgAdapter.cooldownActive(102, 1000));
        SpicyOrgAdapter.recordCooldown(context, 102, 50, 1000);
        assertTrue(SpicyOrgAdapter.cooldownActive(102, 50999));
        assertFalse(SpicyOrgAdapter.cooldownActive(102, 51000));
    }

    @Test public void retiredResponseCannotReplaceCurrentCooldown() {
        rotate(201);
        rotate(202);
        SpicyOrgAdapter.recordCooldown(context, 202, 50, 1000);
        SpicyOrgAdapter.recordCooldown(context, 201, 3600, 2000);
        assertTrue(SpicyOrgAdapter.cooldownActive(202, 3000));
        assertFalse(SpicyOrgAdapter.cooldownActive(201, 3000));
        assertFalse(SpicyOrgAdapter.cooldownActive(202, 51000));
    }

    @Test public void retiredResponseCannotCreateCooldownBeforeCurrentResponse() {
        rotate(301);
        rotate(302);
        SpicyOrgAdapter.recordCooldown(context, 301, 3600, 1000);
        assertFalse(SpicyOrgAdapter.cooldownActive(301, 2000));
        assertFalse(SpicyOrgAdapter.cooldownActive(302, 2000));
    }

    @Test public void sameKeyExtendsButNeverShortensCooldown() {
        rotate(401);
        SpicyOrgAdapter.recordCooldown(context, 401, 50, 1000);
        SpicyOrgAdapter.recordCooldown(context, 401, 50, 2000);
        SpicyOrgAdapter.recordCooldown(context, 401, 1, 3000);
        assertTrue(SpicyOrgAdapter.cooldownActive(401, 51999));
        assertFalse(SpicyOrgAdapter.cooldownActive(401, 52000));
    }
}
