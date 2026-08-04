package betterquesting.api2.client.gui.themes;

import com.google.gson.JsonObject;

import betterquesting.api2.client.gui.resources.colors.IGuiColor;
import betterquesting.api2.client.gui.resources.lines.IGuiLine;
import betterquesting.api2.client.gui.resources.textures.IGuiTexture;
import betterquesting.api2.registry.IFactoryData;
import betterquesting.api2.registry.IRegistry;

/** Registry for BQ GUI resource type loaders. Used primarily for loading themes from JSON */
public interface IResourceReg {

    IRegistry<IFactoryData<IGuiTexture, JsonObject>, IGuiTexture> getTexReg();

    IRegistry<IFactoryData<IGuiColor, JsonObject>, IGuiColor> getColorReg();

    IRegistry<IFactoryData<IGuiLine, JsonObject>, IGuiLine> getLineReg();
}
