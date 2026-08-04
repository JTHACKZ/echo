package me.echo;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.kherud.llama.LlamaModel;
import de.kherud.llama.LlamaOutput;
import de.kherud.llama.ModelParameters;
import de.kherud.llama.InferenceParameters;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Echo implements ModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("echo");

	// UI State Variables
	public static int activeSlot = 1;
	public static String slot1Name = "Empty";
	public static String slot2Name = "Empty";
	public static String slot3Name = "Empty";

	// Embedded AI Variables
	private static LlamaModel aiModel = null;
	public static volatile boolean isThinking = false;
	public static volatile boolean isBrainLoaded = false;
	public static List<String> shortTermMemory = new ArrayList<>();

	public static final String SYSTEM_PROMPT = """
            You are Echo, an AI integrated natively into Minecraft. The player is a Green Lantern.
            CRITICAL RULES:
            1. NEVER roleplay actions. Asterisks (*) do not execute code.
            2. SIMPLE ACTIONS: If the user asks for a simple command (give item, potion effect, time, weather, spawn mob), output ONLY a command using `<<run:/command>>`.
            3. BLUEPRINTS: For constructs (shields, beams, walls, spheres), output a JSON blueprint using `<<blueprint:slotX|{...}>>` so the native Java toolbox can assemble it instantly.
            """;

	@Override
	public void onInitialize() {
		LOGGER.info("[Echo] Native Blueprint Mod initializing...");

		File modelFolder = new File("echo_systems/models");
		if (!modelFolder.exists()) {
			boolean created = modelFolder.mkdirs();
			if (!created) {
				LOGGER.warn("[Echo] Could not create models directory at " + modelFolder.getAbsolutePath());
			}
		}

		// Load the AI model in a background thread
		new Thread(() -> {
			try {
				File modelPath = new File(modelFolder, "Qwen2.5-Coder-7B-Instruct-Q4_K_M.gguf");
				if (!modelPath.exists()) {
					modelPath = new File(modelFolder, "echo.gguf");
				}

				// Fallback: search for ANY .gguf file in the model directory
				if (!modelPath.exists() && modelFolder.exists() && modelFolder.isDirectory()) {
					File[] files = modelFolder.listFiles((dir, name) -> name.toLowerCase().endsWith(".gguf"));
					if (files != null && files.length > 0) {
						modelPath = files[0];
					}
				}

				if (modelPath.exists()) {
					LOGGER.info("[Echo Brain] Loading internal GGUF model: " + modelPath.getName());

					ModelParameters params = new ModelParameters()
							.setModel(modelPath.getAbsolutePath())
							.setGpuLayers(99);

					aiModel = new LlamaModel(params);
					isBrainLoaded = true;
					LOGGER.info("[Echo Brain] Neural Network Online and Embedded!");
				} else {
					LOGGER.error("[Echo Brain] No GGUF model found!");
					LOGGER.error("[Echo Brain] Target Directory: " + modelFolder.getAbsolutePath());
					LOGGER.error("[Echo Brain] Please place your .gguf file into the folder path shown above.");
				}
			} catch (Exception e) {
				LOGGER.error("[Echo Brain] Failed to load embedded model.", e);
			}
		}).start();

		registerCommands();
	}

	private void registerCommands() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {

			// HUD Slots commands
			dispatcher.register(Commands.literal("slot")
					.then(Commands.literal("1").executes(ctx -> { activeSlot = 1; updatePlayerSlotTag(ctx.getSource().getPlayer(), 1); return 1; }))
					.then(Commands.literal("2").executes(ctx -> { activeSlot = 2; updatePlayerSlotTag(ctx.getSource().getPlayer(), 2); return 1; }))
					.then(Commands.literal("3").executes(ctx -> { activeSlot = 3; updatePlayerSlotTag(ctx.getSource().getPlayer(), 3); return 1; }))
			);

			dispatcher.register(Commands.literal("set_slot_name")
					.then(Commands.argument("slot", IntegerArgumentType.integer(1, 3))
							.then(Commands.argument("name", StringArgumentType.greedyString())
									.executes(context -> {
										int slot = IntegerArgumentType.getInteger(context, "slot");
										String name = StringArgumentType.getString(context, "name");
										if (slot == 1) slot1Name = name;
										if (slot == 2) slot2Name = name;
										if (slot == 3) slot3Name = name;
										return 1;
									})))
			);

			dispatcher.register(Commands.literal("echoreset")
					.executes(context -> {
						shortTermMemory.clear();
						slot1Name = "Empty";
						slot2Name = "Empty";
						slot3Name = "Empty";
						activeSlot = 1;
						context.getSource().sendSystemMessage(Component.literal("§c[System]: Echo memory and slots wiped."));
						return 1;
					})
			);

			// THE MAIN /echo COMMAND
			dispatcher.register(Commands.literal("echo")
					.then(Commands.argument("message", StringArgumentType.greedyString())
							.executes(context -> {
								if (!isBrainLoaded || aiModel == null) {
									context.getSource().sendSystemMessage(Component.literal("§c[Echo]: AI Engine offline. Check console for target model folder."));
									return 1;
								}
								if (isThinking) {
									context.getSource().sendSystemMessage(Component.literal("§c[Echo]: System busy compiling previous request..."));
									return 1;
								}

								String userMessage = StringArgumentType.getString(context, "message");
								context.getSource().sendSystemMessage(Component.literal("§a[You]: " + userMessage));
								isThinking = true;

								new Thread(() -> {
									try {
										StringBuilder promptBuilder = new StringBuilder();
										promptBuilder.append("<|im_start|>system\n").append(SYSTEM_PROMPT).append("<|im_end|>\n");

										for (String memory : shortTermMemory) {
											promptBuilder.append(memory);
										}

										promptBuilder.append("<|im_start|>user\n").append(userMessage).append("<|im_end|>\n<|im_start|>assistant\n");

										InferenceParameters inferParams = new InferenceParameters(promptBuilder.toString())
												.setTemperature(0.2f)
												.setStopStrings("<|im_end|>");

										StringBuilder fullResponse = new StringBuilder();

										for (LlamaOutput output : aiModel.generate(inferParams)) {
											fullResponse.append(output.toString());
										}

										String responseText = fullResponse.toString().trim();

										shortTermMemory.add("<|im_start|>user\n" + userMessage + "<|im_end|>\n");
										shortTermMemory.add("<|im_start|>assistant\n" + responseText + "<|im_end|>\n");
										if (shortTermMemory.size() > 6) {
											shortTermMemory.remove(0); shortTermMemory.remove(0);
										}

										List<String> commandsToRun = new ArrayList<>();
										Pattern cmdPattern = Pattern.compile("<<run:\\s*(.*?)>>");
										Matcher cmdMatcher = cmdPattern.matcher(responseText);
										while (cmdMatcher.find()) {
											commandsToRun.add(cmdMatcher.group(1).trim());
										}

										String cleanChat = cmdPattern.matcher(responseText).replaceAll("").trim();

										if (!cleanChat.isEmpty()) {
											context.getSource().sendSystemMessage(Component.literal("§b[Echo]: " + cleanChat));
										}

										if (!commandsToRun.isEmpty()) {
											context.getSource().getServer().execute(() -> {
												for (String cmd : commandsToRun) {
													context.getSource().getServer().getCommands().performPrefixedCommand(context.getSource(), cmd);
												}
											});
										}

									} catch (Exception e) {
										LOGGER.error("AI Generation Error", e);
										context.getSource().sendSystemMessage(Component.literal("§c[Echo]: Brain misfire. Check console."));
									} finally {
										isThinking = false;
									}
								}).start();

								return 1;
							})));
		});
	}

	private static void updatePlayerSlotTag(ServerPlayer p, int slot) {
		if (p == null) return;
		p.removeTag("slot1");
		p.removeTag("slot2");
		p.removeTag("slot3");
		p.addTag("slot" + slot);
	}
}