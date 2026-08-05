package com.github.postyizhan.betterquesting.platform.fml;

import com.github.postyizhan.betterquesting.platform.api.PlayerIdentityResolution;
import com.github.postyizhan.betterquesting.platform.api.PlayerIdentityService;
import java.util.Objects;
import net.minecraft.EntityPlayer;

/** Keeps the only Minecraft dependency at the platform edge and uses the mapped public name accessor. */
public final class MitePlayerIdentityAdapter {
    private final PlayerIdentityService identities;

    public MitePlayerIdentityAdapter(PlayerIdentityService identities) {
        this.identities = Objects.requireNonNull(identities, "identities");
    }

    public PlayerIdentityResolution resolve(EntityPlayer player) {
        Objects.requireNonNull(player, "player");
        String username = player.getEntityName();
        return username == null
            ? PlayerIdentityResolution.unsupportedUsername(null)
            : identities.resolveUsername(username);
    }
}
