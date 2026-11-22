/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package com.ayan7601.blazed.modules.main;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.meteordev.starscript.Script;

import com.ayan7601.blazed.BlazedAddon;

import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import com.ayan7601.blazed.ipc.DiscordIPC;
import com.ayan7601.blazed.ipc.RichPresence;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WidgetScreen;
import meteordevelopment.meteorclient.gui.utils.StarscriptTextBoxRenderer;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.misc.MeteorStarscript;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DirectJoinServerScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.ManageServerScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.WinScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.options.AccessibilityOptionsScreen;
import net.minecraft.client.gui.screens.options.ChatOptionsScreen;
import net.minecraft.client.gui.screens.options.LanguageSelectScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.options.SkinCustomizationScreen;
import net.minecraft.client.gui.screens.options.SoundOptionsScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.client.gui.screens.options.controls.ControlsScreen;
import net.minecraft.client.gui.screens.packs.PackSelectionScreen;
import net.minecraft.client.gui.screens.worldselection.AbstractGameRulesScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.EditWorldScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.realms.RealmsScreen;
import net.minecraft.util.Util;

public class DiscordPresence extends Module {
    public enum SelectMode {
        Random,
        Sequential
    }

    private final SettingGroup sgLine2 = settings.createGroup("Status");
    private final SettingGroup sgPlayerHead = settings.createGroup("Player Head");

    private final Setting<String> customPlayerHeadUrl = sgPlayerHead.add(new StringSetting.Builder()
        .name("custom-player-head-url")
        .description("Optional public HTTPS image URL for your player head. Leave blank to use the logged-in Minecraft name automatically. Useful for cracked accounts with custom skins.")
        .defaultValue("")
        .build()
    );

    private final Setting<List<String>> line2Strings = sgLine2.add(new StringListSetting.Builder()
        .name("line-2-messages")
        .description("Messages used for the second line.")
        .defaultValue("{round(server.tps, 1)} TPS", "Playing on {server.difficulty} difficulty.", "{server.player_count} Players online")
        .onChanged(_ -> recompileLine2())
        .renderer(StarscriptTextBoxRenderer.class)
        .build()
    );

    private final Setting<Integer> line2UpdateDelay = sgLine2.add(new IntSetting.Builder()
        .name("line-2-update-delay")
        .description("How fast to update the second line in ticks.")
        .defaultValue(60)
        .min(10)
        .sliderRange(10, 200)
        .build()
    );

    private final Setting<SelectMode> line2SelectMode = sgLine2.add(new EnumSetting.Builder<SelectMode>()
        .name("line-2-select-mode")
        .description("How to select messages for the second line.")
        .defaultValue(SelectMode.Sequential)
        .build()
    );

    private static final RichPresence rpc = new RichPresence();
    private boolean forceUpdate, lastWasInMainMenu;

    private final List<Script> line2Scripts = new ArrayList<>();
    private int line2Ticks, line2I;

    public static final Object2ObjectLinkedOpenHashMap<String, String> customStates = new Object2ObjectLinkedOpenHashMap<>();

    static {
        registerCustomState("com.terraformersmc.modmenu.gui", "Browsing mods");
        registerCustomState("me.jellysquid.mods.sodium.client", "Changing options");
    }

    public DiscordPresence() {
        super(BlazedAddon.CATEGORY, "discord-rich-presence", "Displays Blazed as your presence on Discord.");

        runInMainMenu = true;
    }

    /**
     * Registers a custom state to be used when the current screen is a class in the specified package.
     */
    public static void registerCustomState(String packageName, String state) {
        customStates.put(packageName, state);
    }

    /**
     * The package name must match exactly to the one provided through {@link #registerCustomState(String, String)}.
     */
    public static void unregisterCustomState(String packageName) {
        customStates.remove(packageName);
    }

    @Override
    public void onActivate() {
        // Uses Blazed's own copy of discord-ipc (com.ayan7601.blazed.ipc). The copy bundled in
        // Meteor 26.2-35 builds the Windows pipe path as \\?\pipe\..., which Java 25 cannot open.
        boolean started = DiscordIPC.start(1545725506870313012L, () -> info("Discord IPC ready"));
        if (!started) warning("Discord IPC could not open a connection. Is the Discord desktop app running?");

        rpc.setStart(System.currentTimeMillis() / 1000L);

        String minecraftVersion = FabricLoader.getInstance().getModContainer("minecraft")
            .map(container -> container.getMetadata().getVersion().getFriendlyString())
            .orElse("unknown");
        String loaderName = FabricLoader.getInstance().getModContainer("fabricloader")
            .map(container -> container.getMetadata().getName().replace(" Loader", ""))
            .orElse("Fabric");
        String largeText = "Minecraft %s %s".formatted(minecraftVersion, loaderName);
        rpc.setLargeImage("minecraft", largeText);

        recompileLine2();

        line2Ticks = 0;
        lastWasInMainMenu = false;

        line2I = 0;
    }

    @Override
    public void onDeactivate() {
        DiscordIPC.stop();
    }

    private void recompile(List<String> messages, List<Script> scripts) {
        scripts.clear();

        for (String message : messages) {
            Script script = MeteorStarscript.compile(message);
            if (script != null) scripts.add(script);
        }

        forceUpdate = true;
    }

    private void recompileLine2() {
        recompile(line2Strings.get(), line2Scripts);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        boolean update = false;

        if (Utils.canUpdate()) {
            if (line2Ticks >= line2UpdateDelay.get() || forceUpdate) {
                if (!line2Scripts.isEmpty()) {
                    int i = Utils.random(0, line2Scripts.size());
                    if (line2SelectMode.get() == SelectMode.Sequential) {
                        if (line2I >= line2Scripts.size()) line2I = 0;
                        i = line2I++;
                    }

                    String message = MeteorStarscript.run(line2Scripts.get(i));
                    if (message != null) rpc.setState(message);
                }
                update = true;

                line2Ticks = 0;
            } else line2Ticks++;
        } else {
            if (!lastWasInMainMenu) {
                if (mc.gui.screen() instanceof TitleScreen) rpc.setState("Looking at title screen");
                else if (mc.gui.screen() instanceof SelectWorldScreen) rpc.setState("Selecting world");
                else if (mc.gui.screen() instanceof CreateWorldScreen || mc.gui.screen() instanceof AbstractGameRulesScreen)
                    rpc.setState("Creating world");
                else if (mc.gui.screen() instanceof EditWorldScreen) rpc.setState("Editing world");
                else if (mc.gui.screen() instanceof LevelLoadingScreen) rpc.setState("Loading world");
                else if (mc.gui.screen() instanceof JoinMultiplayerScreen) rpc.setState("Selecting server");
                else if (mc.gui.screen() instanceof ManageServerScreen) rpc.setState("Adding server");
                else if (mc.gui.screen() instanceof ConnectScreen || mc.gui.screen() instanceof DirectJoinServerScreen)
                    rpc.setState("Connecting to server");
                else if (mc.gui.screen() instanceof WidgetScreen) rpc.setState("Browsing Meteor's GUI");
                else if (mc.gui.screen() instanceof OptionsScreen || mc.gui.screen() instanceof SkinCustomizationScreen || mc.gui.screen() instanceof SoundOptionsScreen || mc.gui.screen() instanceof VideoSettingsScreen || mc.gui.screen() instanceof ControlsScreen || mc.gui.screen() instanceof LanguageSelectScreen || mc.gui.screen() instanceof ChatOptionsScreen || mc.gui.screen() instanceof PackSelectionScreen || mc.gui.screen() instanceof AccessibilityOptionsScreen)
                    rpc.setState("Changing options");
                else if (mc.gui.screen() instanceof WinScreen) rpc.setState("Reading credits");
                else if (mc.gui.screen() instanceof RealmsScreen) rpc.setState("Browsing Realms");
                else {
                    boolean setState = false;
                    if (mc.gui.screen() != null) {
                        String className = mc.gui.screen().getClass().getName();
                        for (var entry : customStates.object2ObjectEntrySet()) {
                            if (className.startsWith(entry.getKey())) {
                                rpc.setState(entry.getValue());
                                setState = true;
                                break;
                            }
                        }
                    }
                    if (!setState) rpc.setState("In main menu");
                }

                update = true;
            }
        }

        // Update
        if (update) {
            updatePlayerHead();
            DiscordIPC.setActivity(rpc);
        }
        forceUpdate = false;
        lastWasInMainMenu = !Utils.canUpdate();
    }

    private void updatePlayerHead() {
        String playerName = mc.getUser().getName();
        if (mc.player != null) playerName = mc.player.getGameProfile().name();

        String imageUrl = customPlayerHeadUrl.get().trim();
        if (imageUrl.isEmpty()) {
            imageUrl = "https://mc-heads.net/avatar/" + URLEncoder.encode(playerName, StandardCharsets.UTF_8);
        }

        // Discord resolves image URLs externally. The tooltip follows the active account,
        // including offline/cracked launcher profiles.
        rpc.setSmallImage(imageUrl, playerName);
    }

    @EventHandler
    private void onOpenScreen(OpenScreenEvent event) {
        if (!Utils.canUpdate()) lastWasInMainMenu = false;
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WButton help = theme.button("Open documentation.");
        help.action = () -> Util.getPlatform().openUri("https://github.com/MeteorDevelopment/meteor-client/wiki/Starscript");

        return help;
    }

}
