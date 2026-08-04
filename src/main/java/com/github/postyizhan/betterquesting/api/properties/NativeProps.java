package com.github.postyizhan.betterquesting.api.properties;

import com.github.postyizhan.betterquesting.api.enums.EnumLogic;
import com.github.postyizhan.betterquesting.api.enums.EnumQuestVisibility;
import com.github.postyizhan.betterquesting.api.properties.basic.PropertyTypeBoolean;
import com.github.postyizhan.betterquesting.api.properties.basic.PropertyTypeEnum;
import com.github.postyizhan.betterquesting.api.properties.basic.PropertyTypeFloat;
import com.github.postyizhan.betterquesting.api.properties.basic.PropertyTypeInteger;
import com.github.postyizhan.betterquesting.api.properties.basic.PropertyTypeString;
import com.github.postyizhan.betterquesting.api.util.ResourceKey;

public final class NativeProps {
    public static final IPropertyType<String> NAME = string("betterquesting:name", "untitled.name");
    public static final IPropertyType<String> DESC = string("betterquesting:desc", "untitled.desc");

    @Deprecated
    public static final IPropertyType<Boolean> MAIN = bool("betterquesting:isMain", false);
    public static final IPropertyType<Boolean> GLOBAL = bool("betterquesting:isGlobal", false);
    public static final IPropertyType<Boolean> GLOBAL_SHARE = bool("betterquesting:globalShare", false);
    public static final IPropertyType<Boolean> SILENT = bool("betterquesting:isSilent", false);
    public static final IPropertyType<Boolean> AUTO_CLAIM = bool("betterquesting:autoClaim", false);
    public static final IPropertyType<Boolean> LOCKED_PROGRESS = bool("betterquesting:lockedProgress", false);
    public static final IPropertyType<Boolean> SIMULTANEOUS = bool("betterquesting:simultaneous", false);
    public static final IPropertyType<Boolean> COUNT_AS_QUEST = bool("betterquesting:countAsQuest", true);

    public static final IPropertyType<EnumQuestVisibility> VISIBILITY = new PropertyTypeEnum<>(
        ResourceKey.parse("betterquesting:visibility"), EnumQuestVisibility.NORMAL);
    public static final IPropertyType<EnumLogic> LOGIC_TASK = new PropertyTypeEnum<>(
        ResourceKey.parse("betterquesting:taskLogic"), EnumLogic.AND);
    public static final IPropertyType<EnumLogic> LOGIC_QUEST = new PropertyTypeEnum<>(
        ResourceKey.parse("betterquesting:questLogic"), EnumLogic.AND);

    public static final IPropertyType<Integer> REPEAT_TIME = integer("betterquesting:repeatTime", -1);
    public static final IPropertyType<Boolean> REPEAT_REL = bool("betterquesting:repeat_relative", true);

    public static final IPropertyType<String> SOUND_UNLOCK = string("betterquesting:snd_unlock", "random.click");
    public static final IPropertyType<String> SOUND_UPDATE = string("betterquesting:snd_update", "random.levelup");
    public static final IPropertyType<String> SOUND_COMPLETE = string("betterquesting:snd_complete", "random.levelup");

    // PropertyTypeItemStack is deferred with BigItemStack; persisted key: "betterquesting:icon".
    public static final IPropertyType<String> COMPLETION_PARTICLE = string("betterquesting:completion_particle", "default");
    public static final IPropertyType<String> COMPLETION_ANIMATION = string("betterquesting:completion_animation", "default");
    // PropertyTypeItemStack is deferred with BigItemStack; persisted key: "betterquesting:confetti_icon".
    public static final IPropertyType<Integer> PARTICLE_COUNT = integer("betterquesting:particle_count", -1);

    public static final IPropertyType<String> NOTIFICATION_STYLE = string("betterquesting:notification_style", "default");
    public static final IPropertyType<String> NOTIFICATION_SHOW_ICON = string("betterquesting:notification_show_icon", "default");
    public static final IPropertyType<String> NOTIFICATION_TITLE = string("betterquesting:notification_title", "");
    public static final IPropertyType<String> NOTIFICATION_SUBTITLE = string("betterquesting:notification_subtitle", "");
    public static final IPropertyType<Float> NOTIFICATION_DURATION = decimal("betterquesting:notification_duration", -1F);
    public static final IPropertyType<Float> NOTIFICATION_FADE_IN = decimal("betterquesting:notification_fade_in", -1F);
    public static final IPropertyType<Float> NOTIFICATION_FADE_OUT = decimal("betterquesting:notification_fade_out", -1F);
    public static final IPropertyType<Float> NOTIFICATION_TITLE_SCALE = decimal("betterquesting:notification_title_scale", -1F);
    public static final IPropertyType<Float> NOTIFICATION_SUBTITLE_SCALE = decimal("betterquesting:notification_subtitle_scale", -1F);
    public static final IPropertyType<Float> NOTIFICATION_ICON_SCALE = decimal("betterquesting:notification_icon_scale", -1F);
    public static final IPropertyType<Integer> NOTIFICATION_ICON_OFFSET_Y = integer("betterquesting:notification_icon_offset_y", Integer.MIN_VALUE);
    public static final IPropertyType<Integer> NOTIFICATION_POS_X = integer("betterquesting:notification_pos_x", Integer.MIN_VALUE);
    public static final IPropertyType<Integer> NOTIFICATION_POS_Y = integer("betterquesting:notification_pos_y", Integer.MIN_VALUE);
    public static final IPropertyType<Integer> NOTIFICATION_EFFECT = integer("betterquesting:notification_effect", -1);
    // Upstream placeholder, not currently active: "betterquesting:frame".

    public static final IPropertyType<String> BG_IMAGE = string("betterquesting:bg_image", "");
    public static final IPropertyType<Integer> BG_SIZE = integer("betterquesting:bg_size", 256);
    public static final IPropertyType<Boolean> PARTY_ENABLE = bool("betterquesting:party_enable", true);
    public static final IPropertyType<Boolean> HARDCORE = bool("betterquesting:hardcore", false);
    public static final IPropertyType<Boolean> EDIT_MODE = bool("betterquesting:editMode", true);
    public static final IPropertyType<Integer> LIVES = integer("betterquesting:lives", 1);
    public static final IPropertyType<Integer> LIVES_DEF = integer("betterquesting:livesDef", 3);
    public static final IPropertyType<Integer> LIVES_MAX = integer("betterquesting:livesMax", 10);

    public static final IPropertyType<String> HOME_IMAGE = string(
        "betterquesting:home_image", "betterquesting:textures/gui/default_title.png");
    public static final IPropertyType<Float> HOME_ANC_X = decimal("betterquesting:home_anchor_x", 0.5F);
    public static final IPropertyType<Float> HOME_ANC_Y = decimal("betterquesting:home_anchor_y", 0F);
    public static final IPropertyType<Integer> HOME_OFF_X = integer("betterquesting:home_offset_x", -128);
    public static final IPropertyType<Integer> HOME_OFF_Y = integer("betterquesting:home_offset_y", 0);
    public static final IPropertyType<Integer> PACK_VER = integer("betterquesting:pack_version", 0);
    public static final IPropertyType<String> PACK_NAME = string("betterquesting:pack_name", "");

    private NativeProps() {
    }

    private static IPropertyType<String> string(String key, String defaultValue) {
        return new PropertyTypeString(ResourceKey.parse(key), defaultValue);
    }

    private static IPropertyType<Boolean> bool(String key, boolean defaultValue) {
        return new PropertyTypeBoolean(ResourceKey.parse(key), defaultValue);
    }

    private static IPropertyType<Integer> integer(String key, int defaultValue) {
        return new PropertyTypeInteger(ResourceKey.parse(key), defaultValue);
    }

    private static IPropertyType<Float> decimal(String key, float defaultValue) {
        return new PropertyTypeFloat(ResourceKey.parse(key), defaultValue);
    }
}
