package me.echo;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import me.echo.constructs.ConstructToolbox;
import me.echo.engine.MethodLibrary;
import me.echo.registry.ModEntities;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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

	// Engine & State Variables
	private static Process serverProcess = null;
	public static volatile boolean isThinking = false;
	public static volatile boolean isBrainLoaded = false;
	public static List<JsonObject> chatHistory = new ArrayList<>();

	private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.build();

	public static final String SYSTEM_PROMPT = """
            You are Echo, an AI integrated natively into Minecraft. The player is a Green Lantern.
            CRITICAL RULES:
            1. NEVER chat, apologize, explain, or say "Here is the command". Output ONLY valid syntax.
            2. SIMPLE COMMANDS: For vanilla actions (give item, potion effect, time, weather, spawn mob), output ONLY `<<run:/command>>`.
            3. BLUEPRINTS: When requested to make a construct, output ONLY this exact JSON tag:
               <<blueprint:slot1|{"name":"Black Hole","shape":"sphere","size":5.0,"mode":"static","y_offset":2.0,"java_code":"...","save_as":"Black Hole","duration":0}>>
               Allowed "shape": "wall", "shield", "platform", "beam", "sphere", "box", "clear".
               Allowed "mode": "camera" (front of eyes), "feet" (under boots flat), "hover" (under boots tilting), "body" (surrounding player), "static" (stationary).
               SAVING & LOADING PERMANENT METHODS:
               - To permanently SAVE a new custom tool/method to disk: include `"save_as": "Method Name"` along with `"java_code"`.
               - To LOAD and summon a previously saved method: output ONLY `<<blueprint:slot1|{"load_method":"Method Name"}>>`.
               LIVE NATIVE JAVA BYTECODE INJECTION ("java_code"):
               - You can write LIVE Java statements executed every tick! Variables available: `construct`, `level`, `owner`.
               IMPORTANT: If the user asks to remove, clear, stop, or delete a construct, output "shape": "clear".
               NEVER add extra JSON keys like color, opacity, or followCamera.
            """;

	@Override
	public void onInitialize() {
		ModEntities.register();
		MethodLibrary.init();

		LOGGER.info("[Echo] Native Blueprint Engine Initializing...");

		File engineFolder = new File("echo_systems/engine");
		File modelFolder = new File("echo_systems/models");
		File aiMethodsFolder = new File("src/main/java/me/echo/ai_created/methods");

		if (!engineFolder.exists()) {
			boolean created = engineFolder.mkdirs();
			if (!created) LOGGER.warn("[Echo] Engine directory creation checked.");
		}
		if (!modelFolder.exists()) {
			boolean created = modelFolder.mkdirs();
			if (!created) LOGGER.warn("[Echo] Models directory creation checked.");
		}
		if (!aiMethodsFolder.exists()) {
			boolean created = aiMethodsFolder.mkdirs();
			if (!created) LOGGER.warn("[Echo] AI Created Methods directory creation checked.");
		}

		new Thread(this::startLlamaServer).start();

		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			if (serverProcess != null && serverProcess.isAlive()) {
				LOGGER.info("[Echo Engine] Shutting down llama-server process...");
				serverProcess.destroyForcibly();
			}
		}));

		registerCommands();
	}

	private void startLlamaServer() {
		try {
			File exeFile = new File("echo_systems/engine/llama-server.exe");
			File modelFolder = new File("echo_systems/models");

			File modelFile = new File(modelFolder, "Qwen2.5-Coder-7B-Instruct-Q4_K_M.gguf");
			if (!modelFile.exists()) {
				modelFile = new File(modelFolder, "echo.gguf");
			}

			if (!modelFile.exists() && modelFolder.exists() && modelFolder.isDirectory()) {
				File[] files = modelFolder.listFiles((dir, name) -> name.toLowerCase().endsWith(".gguf"));
				if (files != null && files.length > 0) {
					modelFile = files[0];
				}
			}

			if (!exeFile.exists()) {
				LOGGER.error("[Echo Engine] Missing executable: {}", exeFile.getAbsolutePath());
				return;
			}

			if (!modelFile.exists()) {
				LOGGER.error("[Echo Engine] Missing .gguf model in: {}", modelFolder.getAbsolutePath());
				return;
			}

			LOGGER.info("[Echo Engine] Spawning llama-server.exe with GPU offloading (-ngl 99)...");

			List<String> command = List.of(
					exeFile.getAbsolutePath(),
					"-m", modelFile.getAbsolutePath(),
					"-ngl", "99",
					"-c", "2048",
					"--port", "8080",
					"--host", "127.0.0.1"
			);

			ProcessBuilder pb = new ProcessBuilder(command);
			pb.directory(exeFile.getParentFile());
			pb.redirectErrorStream(true);

			serverProcess = pb.start();
			isBrainLoaded = true;
			LOGGER.info("[Echo Engine] GPU Engine initialized cleanly on port 8080!");

		} catch (Exception e) {
			LOGGER.error("[Echo Engine] Failed to launch llama-server.exe", e);
		}
	}

	private void registerCommands() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {

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
						chatHistory.clear();
						slot1Name = "Empty";
						slot2Name = "Empty";
						slot3Name = "Empty";
						activeSlot = 1;
						context.getSource().sendSystemMessage(Component.literal("§c[System]: Echo memory and slots wiped."));
						return 1;
					})
			);

			dispatcher.register(Commands.literal("echo")
					.then(Commands.argument("message", StringArgumentType.greedyString())
							.executes(context -> {
								if (!isBrainLoaded) {
									context.getSource().sendSystemMessage(Component.literal("§c[Echo]: Engine offline. Ensure llama-server.exe and .gguf exist in echo_systems/"));
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
										List<String> savedNames = MethodLibrary.getSavedMethodNames();
										String dynamicPrompt = SYSTEM_PROMPT;
										if (!savedNames.isEmpty()) {
											dynamicPrompt += "\nUNLOCKED PERMANENT METHODS AVAILABLE ON DISK: " + savedNames;
										}

										JsonObject sysMsg = new JsonObject();
										sysMsg.addProperty("role", "system");
										sysMsg.addProperty("content", dynamicPrompt);

										JsonObject usrMsg = new JsonObject();
										usrMsg.addProperty("role", "user");
										usrMsg.addProperty("content", userMessage);

										JsonArray messages = new JsonArray();
										messages.add(sysMsg);
										for (JsonObject oldMsg : chatHistory) {
											messages.add(oldMsg);
										}
										messages.add(usrMsg);

										JsonObject payload = new JsonObject();
										payload.add("messages", messages);
										payload.addProperty("temperature", 0.2);
										payload.addProperty("max_tokens", 250); // Increased slightly for longer Java code payloads
										payload.addProperty("frequency_penalty", 0.5);
										payload.addProperty("stream", false);

										HttpRequest request = HttpRequest.newBuilder()
												.uri(URI.create("http://127.0.0.1:8080/v1/chat/completions"))
												.header("Content-Type", "application/json")
												.POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
												.build();

										HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

										if (response.statusCode() == 200) {
											JsonObject respJson = JsonParser.parseString(response.body()).getAsJsonObject();
											String responseText = respJson.getAsJsonArray("choices")
													.get(0).getAsJsonObject()
													.getAsJsonObject("message")
													.get("content").getAsString().trim();

											LOGGER.info("[Echo Raw AI Output]: {}", responseText);

											chatHistory.add(usrMsg);
											JsonObject assistantMsg = new JsonObject();
											assistantMsg.addProperty("role", "assistant");
											assistantMsg.addProperty("content", responseText);
											chatHistory.add(assistantMsg);
											if (chatHistory.size() > 6) {
												chatHistory.remove(0); chatHistory.remove(0);
											}

											List<String> commandsToRun = new ArrayList<>();
											Pattern cmdPattern = Pattern.compile("<<run:\\s*(.*?)(?:>>|\\}\\})");
											Matcher cmdMatcher = cmdPattern.matcher(responseText);
											while (cmdMatcher.find()) {
												commandsToRun.add(cmdMatcher.group(1).trim());
											}

											// --- ROBUST BRACE-BALANCING JSON EXTRACTOR ---
											// Replaces the fragile regex to ensure Java brackets inside strings are perfectly ignored
											List<String> blueprintsToRun = new ArrayList<>();
											int idx = 0;
											while ((idx = responseText.indexOf("<<blueprint:", idx)) != -1) {
												int jsonStart = responseText.indexOf('{', idx);
												if (jsonStart != -1) {
													int braceCount = 0;
													boolean inQuotes = false;
													boolean escape = false;
													int jsonEnd = -1;

													for (int i = jsonStart; i < responseText.length(); i++) {
														char c = responseText.charAt(i);
														if (escape) {
															escape = false;
															continue;
														}
														if (c == '\\') {
															escape = true;
															continue;
														}
														if (c == '"') {
															inQuotes = !inQuotes;
															continue;
														}
														if (!inQuotes) {
															if (c == '{') braceCount++;
															else if (c == '}') braceCount--;

															if (braceCount == 0) {
																jsonEnd = i;
																break;
															}
														}
													}

													if (jsonEnd != -1) {
														blueprintsToRun.add(responseText.substring(jsonStart, jsonEnd + 1));
														idx = jsonEnd + 1;
													} else {
														idx += 12; // skip past tag if broken
													}
												} else {
													idx += 12;
												}
											}

											// Clean chat by entirely wiping any blueprint blocks from text output
											String cleanChat = cmdPattern.matcher(responseText).replaceAll("");
											int bpIndex = cleanChat.indexOf("<<blueprint:");
											if (bpIndex != -1) {
												cleanChat = cleanChat.substring(0, bpIndex);
											}
											cleanChat = cleanChat.trim();

											if (!cleanChat.isEmpty()) {
												context.getSource().sendSystemMessage(Component.literal("§b[Echo]: " + cleanChat));
											}

											context.getSource().getServer().execute(() -> {
												ServerPlayer player = context.getSource().getPlayer();
												for (String cmd : commandsToRun) {
													context.getSource().getServer().getCommands().performPrefixedCommand(context.getSource(), cmd);
												}
												if (player != null && !blueprintsToRun.isEmpty()) {
													ConstructToolbox.executeBlueprintSequence(player, blueprintsToRun);
												}
											});

										} else {
											LOGGER.error("[Echo Engine] HTTP Error Code: {}", response.statusCode());
											context.getSource().sendSystemMessage(Component.literal("§c[Echo]: AI Engine communication failed."));
										}

									} catch (Exception e) {
										LOGGER.error("[Echo Engine] Generation Error", e);
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