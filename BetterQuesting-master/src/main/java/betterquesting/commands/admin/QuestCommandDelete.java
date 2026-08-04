package betterquesting.commands.admin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentTranslation;

import betterquesting.api.properties.NativeProps;
import betterquesting.api.questing.IQuest;
import betterquesting.api.utils.UuidConverter;
import betterquesting.commands.QuestCommandBase;
import betterquesting.handlers.SaveLoadHandler;
import betterquesting.network.handlers.NetChapterSync;
import betterquesting.network.handlers.NetQuestEdit;
import betterquesting.network.handlers.NetQuestSync;
import betterquesting.questing.QuestDatabase;
import betterquesting.questing.QuestLineDatabase;

public class QuestCommandDelete extends QuestCommandBase {

    @Override
    public String getUsageSuffix() {
        return "[all|<quest_id>]";
    }

    @Override
    public boolean validArgs(String[] args) {
        return args.length == 2;
    }

    @Override
    public List<String> autoComplete(MinecraftServer server, ICommandSender sender, String[] args) {
        ArrayList<String> list = new ArrayList<>();

        if (args.length == 2) {
            list.add("all");

            for (UUID id : QuestDatabase.INSTANCE.keySet()) {
                list.add(UuidConverter.encodeUuid(id));
            }
        }

        return list;
    }

    @Override
    public String getCommand() {
        return "delete";
    }

    @Override
    public void runCommand(MinecraftServer server, CommandBase command, ICommandSender sender, String[] args) {
        if (args[1].equalsIgnoreCase("all")) {
            QuestDatabase.INSTANCE.clear();
            QuestLineDatabase.INSTANCE.clear();
            NetQuestSync.sendSync(null, null, true, true);
            NetChapterSync.sendSync(null, null);
            SaveLoadHandler.INSTANCE.markDirty();

            sender.addChatMessage(new ChatComponentTranslation("betterquesting.cmd.delete.all"));
        } else {
            try {
                UUID id = UuidConverter.decodeUuid(args[1].trim());
                IQuest quest = QuestDatabase.INSTANCE.get(id);
                NetQuestEdit.deleteQuests(Collections.singletonList(id));

                sender.addChatMessage(
                    new ChatComponentTranslation(
                        "betterquesting.cmd.delete.single",
                        new ChatComponentTranslation(quest.getProperty(NativeProps.NAME))));
                SaveLoadHandler.INSTANCE.markDirty();
            } catch (Exception e) {
                throw getException(command);
            }
        }
    }

}
