package com.github.postyizhan.betterquesting.api.properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.postyizhan.betterquesting.api.enums.EnumLogic;
import com.github.postyizhan.betterquesting.api.enums.EnumQuestVisibility;
import com.github.postyizhan.betterquesting.api.properties.basic.PropertyTypeBoolean;
import com.github.postyizhan.betterquesting.api.properties.basic.PropertyTypeEnum;
import com.github.postyizhan.betterquesting.api.properties.basic.PropertyTypeFloat;
import com.github.postyizhan.betterquesting.api.properties.basic.PropertyTypeInteger;
import com.github.postyizhan.betterquesting.api.properties.basic.PropertyTypeString;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class NativePropsTest {
    // BigItemStack 移植完成后必须恢复这些字段；保留为可检索的存档兼容性清单。
    private static final String DEFERRED_ICON_KEY = "betterquesting:icon";
    private static final String DEFERRED_CONFETTI_ICON_KEY = "betterquesting:confetti_icon";
    private static final String DEFERRED_FRAME_KEY = "betterquesting:frame";

    @Test
    void activePropertiesMatchUpstreamPersistedContract() throws ReflectiveOperationException {
        List<ExpectedProperty> expected = Arrays.asList(
            property("NAME", "betterquesting:name", "untitled.name", PropertyTypeString.class),
            property("DESC", "betterquesting:desc", "untitled.desc", PropertyTypeString.class),
            property("MAIN", "betterquesting:isMain", false, PropertyTypeBoolean.class),
            property("GLOBAL", "betterquesting:isGlobal", false, PropertyTypeBoolean.class),
            property("GLOBAL_SHARE", "betterquesting:globalShare", false, PropertyTypeBoolean.class),
            property("SILENT", "betterquesting:isSilent", false, PropertyTypeBoolean.class),
            property("AUTO_CLAIM", "betterquesting:autoClaim", false, PropertyTypeBoolean.class),
            property("LOCKED_PROGRESS", "betterquesting:lockedProgress", false, PropertyTypeBoolean.class),
            property("SIMULTANEOUS", "betterquesting:simultaneous", false, PropertyTypeBoolean.class),
            property("COUNT_AS_QUEST", "betterquesting:countAsQuest", true, PropertyTypeBoolean.class),
            property("VISIBILITY", "betterquesting:visibility", EnumQuestVisibility.NORMAL, PropertyTypeEnum.class),
            property("LOGIC_TASK", "betterquesting:taskLogic", EnumLogic.AND, PropertyTypeEnum.class),
            property("LOGIC_QUEST", "betterquesting:questLogic", EnumLogic.AND, PropertyTypeEnum.class),
            property("REPEAT_TIME", "betterquesting:repeatTime", -1, PropertyTypeInteger.class),
            property("REPEAT_REL", "betterquesting:repeat_relative", true, PropertyTypeBoolean.class),
            property("SOUND_UNLOCK", "betterquesting:snd_unlock", "random.click", PropertyTypeString.class),
            property("SOUND_UPDATE", "betterquesting:snd_update", "random.levelup", PropertyTypeString.class),
            property("SOUND_COMPLETE", "betterquesting:snd_complete", "random.levelup", PropertyTypeString.class),
            property("COMPLETION_PARTICLE", "betterquesting:completion_particle", "default", PropertyTypeString.class),
            property("COMPLETION_ANIMATION", "betterquesting:completion_animation", "default", PropertyTypeString.class),
            property("PARTICLE_COUNT", "betterquesting:particle_count", -1, PropertyTypeInteger.class),
            property("NOTIFICATION_STYLE", "betterquesting:notification_style", "default", PropertyTypeString.class),
            property("NOTIFICATION_SHOW_ICON", "betterquesting:notification_show_icon", "default", PropertyTypeString.class),
            property("NOTIFICATION_TITLE", "betterquesting:notification_title", "", PropertyTypeString.class),
            property("NOTIFICATION_SUBTITLE", "betterquesting:notification_subtitle", "", PropertyTypeString.class),
            property("NOTIFICATION_DURATION", "betterquesting:notification_duration", -1F, PropertyTypeFloat.class),
            property("NOTIFICATION_FADE_IN", "betterquesting:notification_fade_in", -1F, PropertyTypeFloat.class),
            property("NOTIFICATION_FADE_OUT", "betterquesting:notification_fade_out", -1F, PropertyTypeFloat.class),
            property("NOTIFICATION_TITLE_SCALE", "betterquesting:notification_title_scale", -1F, PropertyTypeFloat.class),
            property("NOTIFICATION_SUBTITLE_SCALE", "betterquesting:notification_subtitle_scale", -1F, PropertyTypeFloat.class),
            property("NOTIFICATION_ICON_SCALE", "betterquesting:notification_icon_scale", -1F, PropertyTypeFloat.class),
            property("NOTIFICATION_ICON_OFFSET_Y", "betterquesting:notification_icon_offset_y", Integer.MIN_VALUE, PropertyTypeInteger.class),
            property("NOTIFICATION_POS_X", "betterquesting:notification_pos_x", Integer.MIN_VALUE, PropertyTypeInteger.class),
            property("NOTIFICATION_POS_Y", "betterquesting:notification_pos_y", Integer.MIN_VALUE, PropertyTypeInteger.class),
            property("NOTIFICATION_EFFECT", "betterquesting:notification_effect", -1, PropertyTypeInteger.class),
            property("BG_IMAGE", "betterquesting:bg_image", "", PropertyTypeString.class),
            property("BG_SIZE", "betterquesting:bg_size", 256, PropertyTypeInteger.class),
            property("PARTY_ENABLE", "betterquesting:party_enable", true, PropertyTypeBoolean.class),
            property("HARDCORE", "betterquesting:hardcore", false, PropertyTypeBoolean.class),
            property("EDIT_MODE", "betterquesting:editMode", true, PropertyTypeBoolean.class),
            property("LIVES", "betterquesting:lives", 1, PropertyTypeInteger.class),
            property("LIVES_DEF", "betterquesting:livesDef", 3, PropertyTypeInteger.class),
            property("LIVES_MAX", "betterquesting:livesMax", 10, PropertyTypeInteger.class),
            property("HOME_IMAGE", "betterquesting:home_image", "betterquesting:textures/gui/default_title.png", PropertyTypeString.class),
            property("HOME_ANC_X", "betterquesting:home_anchor_x", 0.5F, PropertyTypeFloat.class),
            property("HOME_ANC_Y", "betterquesting:home_anchor_y", 0F, PropertyTypeFloat.class),
            property("HOME_OFF_X", "betterquesting:home_offset_x", -128, PropertyTypeInteger.class),
            property("HOME_OFF_Y", "betterquesting:home_offset_y", 0, PropertyTypeInteger.class),
            property("PACK_VER", "betterquesting:pack_version", 0, PropertyTypeInteger.class),
            property("PACK_NAME", "betterquesting:pack_name", "", PropertyTypeString.class));

        assertEquals(expected.size(), activeNativePropertyFields().size());
        for (ExpectedProperty contract : expected) {
            IPropertyType<?> actual = (IPropertyType<?>) NativeProps.class.getField(contract.name).get(null);
            assertEquals(contract.key, actual.getKey().toString(), contract.name + " key");
            assertEquals(contract.defaultValue, actual.getDefault(), contract.name + " default");
            assertEquals(contract.type, actual.getClass(), contract.name + " type");
        }
    }

    private static List<Field> activeNativePropertyFields() {
        return Arrays.stream(NativeProps.class.getFields())
            .filter(field -> Modifier.isStatic(field.getModifiers()))
            .filter(field -> IPropertyType.class.isAssignableFrom(field.getType()))
            .collect(Collectors.toList());
    }

    private static ExpectedProperty property(String name, String key, Object defaultValue, Class<?> type) {
        return new ExpectedProperty(name, key, defaultValue, type);
    }

    private static final class ExpectedProperty {
        private final String name;
        private final String key;
        private final Object defaultValue;
        private final Class<?> type;

        private ExpectedProperty(String name, String key, Object defaultValue, Class<?> type) {
            this.name = name;
            this.key = key;
            this.defaultValue = defaultValue;
            this.type = type;
        }
    }
}
