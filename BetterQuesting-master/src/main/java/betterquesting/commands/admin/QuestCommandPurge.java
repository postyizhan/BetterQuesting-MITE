package betterquesting.commands.admin;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentTranslation;

import com.google.common.collect.Sets;

import betterquesting.api2.storage.IUuidDatabase;
import betterquesting.commands.QuestCommandBase;
import betterquesting.network.handlers.NetQuestEdit;
import betterquesting.questing.QuestDatabase;
import betterquesting.questing.QuestLineDatabase;

public class QuestCommandPurge extends QuestCommandBase {

    @Override
    public String getCommand() {
        return "purge_hidden_quests";
    }

    @Override
    public void runCommand(MinecraftServer server, CommandBase command, ICommandSender sender, String[] args) {
        HashSet<UUID> knownKeys = QuestLineDatabase.INSTANCE.values()
            .stream()
            .map(IUuidDatabase::keySet)
            .flatMap(Set::stream)
            .collect(Collectors.toCollection(HashSet::new));

        Set<UUID> hiddenQuests = Sets.difference(QuestDatabase.INSTANCE.keySet(), knownKeys);
        NetQuestEdit.deleteQuests(hiddenQuests);

        sender.addChatMessage(new ChatComponentTranslation("betterquesting.cmd.purge_hidden", hiddenQuests.size()));
    }
}
