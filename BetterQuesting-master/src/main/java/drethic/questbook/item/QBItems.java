package drethic.questbook.item;

import net.minecraft.item.Item;

import cpw.mods.fml.common.registry.GameRegistry;

public final class QBItems {

    public static Item ItemQuestBook = new ItemQuestBook();

    public static void init() {
        GameRegistry.registerItem(ItemQuestBook, "ItemQuestBook");
    }
}
