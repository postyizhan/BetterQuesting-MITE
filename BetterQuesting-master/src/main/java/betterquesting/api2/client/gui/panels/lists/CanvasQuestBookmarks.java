package betterquesting.api2.client.gui.panels.lists;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import net.minecraft.entity.player.EntityPlayer;

import com.google.common.collect.Maps;

import betterquesting.api.api.QuestingAPI;
import betterquesting.api.properties.NativeProps;
import betterquesting.api.questing.IQuest;
import betterquesting.api.questing.IQuestLine;
import betterquesting.api.questing.IQuestLineEntry;
import betterquesting.api2.cache.QuestCache;
import betterquesting.api2.client.gui.controls.PanelButtonCustom;
import betterquesting.api2.client.gui.controls.PanelButtonQuest;
import betterquesting.api2.client.gui.misc.GuiRectangle;
import betterquesting.api2.client.gui.misc.IGuiRect;
import betterquesting.api2.client.gui.panels.IGuiPanel;
import betterquesting.api2.client.gui.panels.content.PanelGeneric;
import betterquesting.api2.client.gui.panels.content.PanelTextBox;
import betterquesting.api2.client.gui.resources.textures.OreDictTexture;
import betterquesting.api2.utils.QuestTranslation;
import betterquesting.client.BookmarkHandler;
import betterquesting.misc.QuestSearchEntry;
import betterquesting.questing.QuestDatabase;
import betterquesting.questing.QuestLineDatabase;

public class CanvasQuestBookmarks extends CanvasScrolling {

    private List<QuestSearchEntry> questList;
    private Consumer<QuestSearchEntry> questOpenCallback;
    private Consumer<QuestSearchEntry> questHighlightCallback;
    private static List<QuestSearchEntry> bookmarkedQuests;
    private final EntityPlayer player;
    private final UUID questingUUID;

    public CanvasQuestBookmarks(IGuiRect rect, EntityPlayer player) {
        super(rect);
        this.player = player;
        questingUUID = QuestingAPI.getQuestingUUID(player);
    }

    @Override
    public void initPanel() {
        super.initPanel();
        int resultWidth = this.getTransform()
            .getWidth();
        if (BookmarkHandler.hasChanged()) {
            bookmarkedQuests = getBookmarkedQuests();
        }

        for (int i = 0; i < bookmarkedQuests.size(); i++) {
            QuestSearchEntry entry = bookmarkedQuests.get(i);
            addResult(entry, i, resultWidth);
        }
    }

    protected List<QuestSearchEntry> getBookmarkedQuests() {
        if (questList == null) {
            questList = collectQuests();
        }
        return questList.stream()
            .filter(
                e -> BookmarkHandler.isBookmarked(
                    e.getQuest()
                        .getKey()))
            .sorted(
                Comparator.comparing(
                    e -> BookmarkHandler.getIndexOf(
                        e.getQuest()
                            .getKey())))
            .collect(Collectors.toList());
    }

    private List<QuestSearchEntry> collectQuests() {
        return QuestLineDatabase.INSTANCE.entrySet()
            .stream()
            .flatMap(
                iQuestLineDBEntry -> iQuestLineDBEntry.getValue()
                    .entrySet()
                    .stream()
                    .map(iQuestLineEntryDBEntry -> createQuestSearchEntry(iQuestLineEntryDBEntry, iQuestLineDBEntry)))
            .collect(Collectors.toList());
    }

    private QuestSearchEntry createQuestSearchEntry(Map.Entry<UUID, IQuestLineEntry> iQuestLineEntryDBEntry,
        Map.Entry<UUID, IQuestLine> iQuestLineDBEntry) {
        UUID questId = iQuestLineEntryDBEntry.getKey();
        Map.Entry<UUID, IQuest> quest = Maps.immutableEntry(questId, QuestDatabase.INSTANCE.get(questId));
        return new QuestSearchEntry(quest, iQuestLineDBEntry);
    }

    public void setQuestHighlightCallback(Consumer<QuestSearchEntry> questHighlightCallback) {
        this.questHighlightCallback = questHighlightCallback;
    }

    public void setQuestOpenCallback(Consumer<QuestSearchEntry> questOpenCallback) {
        this.questOpenCallback = questOpenCallback;
    }

    protected void addResult(QuestSearchEntry entry, int index, int cachedWidth) {
        PanelButtonCustom buttonContainer = createContainerButton(entry, index, cachedWidth);

        addTextBox(
            cachedWidth,
            buttonContainer,
            56,
            6,
            QuestTranslation.translateQuestLineName(entry.getQuestLineEntry()));
        addTextBox(cachedWidth, buttonContainer, 36, 20, QuestTranslation.translateQuestName(entry.getQuest()));
    }

    private void addTextBox(int cachedWidth, PanelButtonCustom buttonContainer, int xOffset, int yOffset, String text) {
        PanelTextBox questName = new PanelTextBox(
            new GuiRectangle(xOffset, yOffset, cachedWidth - xOffset, 16),
            QuestTranslation.translate(text));
        buttonContainer.addPanel(questName);
    }

    private PanelButtonCustom createContainerButton(QuestSearchEntry entry, int index, int cachedWidth) {
        PanelButtonCustom buttonContainer = new PanelButtonCustom(
            new GuiRectangle(0, index * 32, cachedWidth, 32, 0),
            2);
        buttonContainer.setCallback(panelButtonCustom -> {
            if (!buttonContainer.isActive()) return;
            if (questHighlightCallback != null) questHighlightCallback.accept(entry);
        });
        buttonContainer.setActive(
            QuestCache.isQuestShown(
                entry.getQuest()
                    .getValue(),
                questingUUID,
                player));
        this.addPanel(buttonContainer);

        buttonContainer.addPanel(createQuestPanelButton(entry));

        buttonContainer.addPanel(
            new PanelGeneric(
                new GuiRectangle(36, 2, 14, 14, 0),
                new OreDictTexture(
                    1F,
                    entry.getQuestLineEntry()
                        .getValue()
                        .getProperty(NativeProps.ICON),
                    false,
                    true)));
        return buttonContainer;
    }

    private PanelButtonQuest createQuestPanelButton(QuestSearchEntry entry) {
        PanelButtonQuest questButton = new PanelButtonQuest(new GuiRectangle(2, 2, 28, 28), 0, "", entry.getQuest());

        questButton.setCallback(value -> {
            if (!questButton.isActive()) return;
            if (questOpenCallback != null) questOpenCallback.accept(entry);
        });
        return questButton;
    }

    public PanelButtonQuest getButtonAt(int mx, int my) {
        for (IGuiPanel panel : getChildren()) {
            if (panel instanceof PanelButtonCustom) {
                int smx = mx - getTransform().getX() + getScrollX();
                int smy = my - getTransform().getY() + getScrollY();
                if (panel.getTransform()
                    .contains(smx, smy)) {
                    for (IGuiPanel btn : ((PanelButtonCustom) panel).getChildren()) {
                        if (btn instanceof PanelButtonQuest) {
                            return (PanelButtonQuest) btn;
                        }
                    }
                }
            }
        }
        return null;
    }
}
