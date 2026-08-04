package com.github.postyizhan.betterquesting.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.postyizhan.betterquesting.api.properties.NativeProps;
import net.minecraft.NBTTagCompound;
import org.junit.jupiter.api.Test;

class QuestSettingsTest {
    @Test
    void constructorInstallsUpstreamDefaults() {
        QuestSettings settings = new QuestSettings();
        assertEquals("", settings.getProperty(NativeProps.PACK_NAME));
        assertEquals(0, settings.getProperty(NativeProps.PACK_VER));
        assertTrue(settings.getProperty(NativeProps.PARTY_ENABLE));
        assertTrue(settings.getProperty(NativeProps.EDIT_MODE));
        assertFalse(settings.getProperty(NativeProps.HARDCORE));
        assertEquals(3, settings.getProperty(NativeProps.LIVES_DEF));
        assertEquals(10, settings.getProperty(NativeProps.LIVES_MAX));
        assertEquals("betterquesting:textures/gui/default_title.png", settings.getProperty(NativeProps.HOME_IMAGE));
        assertEquals(0.5F, settings.getProperty(NativeProps.HOME_ANC_X));
        assertEquals(0F, settings.getProperty(NativeProps.HOME_ANC_Y));
        assertEquals(-128, settings.getProperty(NativeProps.HOME_OFF_X));
        assertEquals(0, settings.getProperty(NativeProps.HOME_OFF_Y));
    }

    @Test
    void readPreservesValuesAndReinstallsMissingDefaults() {
        QuestSettings settings = new QuestSettings();
        NBTTagCompound input = new NBTTagCompound();
        NBTTagCompound domain = new NBTTagCompound();
        domain.setString("pack_name", "Pack");
        input.setTag("betterquesting", domain);
        settings.readFromNBT(input);
        assertEquals("Pack", settings.getProperty(NativeProps.PACK_NAME));
        assertTrue(settings.hasProperty(NativeProps.PACK_VER));
        assertTrue(settings.hasProperty(NativeProps.HOME_IMAGE));
    }

    @Test
    void resetRestoresEveryDefault() {
        QuestSettings settings = new QuestSettings();
        settings.setProperty(NativeProps.PACK_NAME, "Changed");
        settings.setProperty(NativeProps.HARDCORE, true);
        settings.reset();
        assertEquals("", settings.getProperty(NativeProps.PACK_NAME));
        assertFalse(settings.getProperty(NativeProps.HARDCORE));
        assertTrue(settings.hasProperty(NativeProps.LIVES_MAX));
    }
}
