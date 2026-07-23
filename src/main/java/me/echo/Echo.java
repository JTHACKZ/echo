package me.echo;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.Color;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Echo implements ModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("echo");

	private static final HttpClient httpClient = HttpClient.newHttpClient();
	private static final ExecutorService ttsQueue = Executors.newSingleThreadExecutor();
	private static Process activeEngineProcess = null;
	private static Process activeTtsProcess = null;

	private static final String SYS_DIR = "echo_systems";
	private static final String DB_URL = "jdbc:sqlite:" + SYS_DIR + "/echo_brain.db";

	public static volatile boolean isThinking = false;

	// UI State Variables (EchoClient reads these directly)
	public static int activeSlot = 1;
	public static String slot1Name = "Empty";
	public static String slot2Name = "Empty";
	public static String slot3Name = "Empty";

	public static final String SYSTEM_PROMPT = """
            You are Echo, an AI integrated natively into Minecraft. The player is a Green Lantern.
            CRITICAL RULES:
            1. NEVER roleplay actions. Asterisks (*) do not execute code.
            2. COMMANDS VS SCRIPTS (CRITICAL): 
               - If the user asks for a simple action (give an item, apply a potion effect, change weather/time, kill entities, spawn a mob), DO NOT write a script. DO NOT explain how to do it. Just do it yourself using a single `<<run:/command>>`.
               - ONLY write a script using `<<write_script:>>` if the user explicitly asks for a "construct", a physical structure (wall, dome, shield, cube), or something requiring a continuous 20hz tick loop.
            3. CONSTRUCT RULES: All construct scripts MUST be wrapped inside ServerEvents.tick to make them follow the player dynamically. Use ^ ^ ^ for camera relative, ~ ~ ~ for locked world grid.
            4. ABILITY BAR MANAGEMENT: The player has 3 capability slots. If they ask to assign a construct to a slot (1-3):
               - Wrap your execution logic in: `if (player.tags.contains('slotX') && player.tags.contains('summon_active_construct'))` (Replace X with the slot number).
               - Tag all entities you summon as `active_bar_construct` so the engine can clear them.
               - Output a command to rename the slot on their HUD: `<<run:/set_slot_name X ConstructName>>`
            5. ECHO VISION (READ BEFORE WRITE): If asked to modify an existing construct, look at the '[EXISTING SCRIPT CONTENT]' block in your prompt. Read the exact code, modify only the targeted parameters, and save it back under the exact same filename.
            
            EXAMPLE WORKFLOWS:
            Player: Give me jump boost.
            Echo: Applying jump boost matrix.
            <<run:/effect give @p minecraft:jump_boost 10 2>>
            
            Player: Assign a giant green shield to Slot 1.
            Echo: Forging shield matrix and binding to Capability Slot 1.
            <<write_script:server/slot1_shield.js>>
            ServerEvents.tick(event => {
              event.server.players.forEach(player => {
                if (player.tags.contains('slot1') && player.tags.contains('summon_active_construct')) {
                  event.server.runCommandSilent(`execute at ${player.username} run kill @e[type=block_display,tag=active_bar_construct,distance=..30]`);
                  event.server.runCommandSilent(`execute at ${player.username} anchored eyes run summon block_display ^ ^ ^3 {Tags:["active_bar_construct"],block_state:{Name:"minecraft:lime_stained_glass"}}`);
                }
              });
            });
            <<end_script>>
            <<run:/set_slot_name 1 Shield>>
            <<run:/reload>>
            """;

	public static List<String> shortTermMemory = new ArrayList<>();

	@Override
	public void onInitialize() {
		LOGGER.info("[Echo] Core systems online. Initializing Master Directory...");

		File systemFolder = new File(SYS_DIR);
		if (!systemFolder.exists()) {
			boolean ignored = systemFolder.mkdirs();
		}

		initDatabase();
		bootEmbeddedEngine();

		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			if (activeEngineProcess != null && activeEngineProcess.isAlive()) {
				activeEngineProcess.destroyForcibly();
			}
			stopTTS();
		}));

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> saveChatToDB("system", "Player logged in."));

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			saveChatToDB("system", "Player logged off.");
			stopTTS();
		});

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {

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

			dispatcher.register(Commands.literal("slot")
					.then(Commands.literal("1").executes(ctx -> { activeSlot = 1; updatePlayerSlotTag(ctx.getSource().getPlayer(), 1); return 1; }))
					.then(Commands.literal("2").executes(ctx -> { activeSlot = 2; updatePlayerSlotTag(ctx.getSource().getPlayer(), 2); return 1; }))
					.then(Commands.literal("3").executes(ctx -> { activeSlot = 3; updatePlayerSlotTag(ctx.getSource().getPlayer(), 3); return 1; }))
			);

			dispatcher.register(Commands.literal("echoreset")
					.executes(context -> {
						wipeMemoryDB();
						shortTermMemory.clear();
						wipeGeneratedScripts();
						stopTTS();

						slot1Name = "Empty";
						slot2Name = "Empty";
						slot3Name = "Empty";
						activeSlot = 1;

						if (context.getSource().getPlayer() != null) {
							String pName = context.getSource().getPlayer().getScoreboardName();
							context.getSource().getServer().execute(() -> {
								var cmds = context.getSource().getServer().getCommands();
								var src = context.getSource();

								cmds.performPrefixedCommand(src, "tag " + pName + " remove summon_active_construct");
								cmds.performPrefixedCommand(src, "kill @e[type=block_display,tag=active_bar_construct,distance=..50]");
								cmds.performPrefixedCommand(src, "kill @e[tag=construct,distance=..50]");
								cmds.performPrefixedCommand(src, "reload");
							});
						}

						context.getSource().sendSystemMessage(Component.literal("§c[System]: Echo has been factory reset."));
						return 1;
					})
			);

			dispatcher.register(Commands.literal("echo")
					.then(Commands.argument("message", StringArgumentType.greedyString())
							.executes(context -> {
								if (isThinking) {
									context.getSource().sendSystemMessage(Component.literal("§c[Echo]: System busy compiling previous request. Please wait..."));
									return 1;
								}
								isThinking = true;

								String userMessage = StringArgumentType.getString(context, "message");
								context.getSource().sendSystemMessage(Component.literal("§a[You]: " + userMessage));

								new Thread(() -> {
									try {
										saveChatToDB("user", userMessage);

										String longTermMemories = getMemoriesFromDB();
										String playerProfile = getPlayerProfileFromDB();
										String echoProfile = getEchoProfileFromDB();

										boolean needsMemory = false;
										String lowerMsg = userMessage.toLowerCase();
										String[] memoryKeywords = {"remember", "forget", "recall", "last time", "earlier", "history", "memory", "did i"};
										for (String keyword : memoryKeywords) {
											if (lowerMsg.contains(keyword)) { needsMemory = true; break; }
										}

										String injectedChatHistory = needsMemory ? searchJournalSQL(userMessage) : "";
										String playerContext = "";

										if (context.getSource().getPlayer() != null) {
											ServerPlayer p = context.getSource().getPlayer();
											BlockPos pos = p.blockPosition();
											String dim = p.level().dimension().location().getPath();
											float health = p.getHealth();
											int food = p.getFoodData().getFoodLevel();
											String holding = p.getMainHandItem().isEmpty() ? "Nothing" : p.getMainHandItem().getItem().toString();
											String facing = p.getDirection().name();

											int slot = p.getTags().contains("slot3") ? 3 : (p.getTags().contains("slot2") ? 2 : 1);

											playerContext = """
                                    [LIVE TELEMETRY]:
                                    - Location: X:%d Y:%d Z:%d in %s
                                    - Facing: %s
                                    - Status: %.1f HP, %d Hunger
                                    - Holding: %s
                                    - Active Slot: %d
                                    """.formatted(pos.getX(), pos.getY(), pos.getZ(), dim, facing, health, food, holding, slot);

											if (!playerProfile.isEmpty()) playerContext += "\n[PLAYER PROFILE]:\n" + playerProfile + "\n";
										}

										String fileToRead = "";
										if (lowerMsg.contains("sphere") || lowerMsg.contains("dome") || lowerMsg.contains("bubble")) fileToRead = "glass_sphere.js";
										else if (lowerMsg.contains("hand")) fileToRead = "giant_hand.js";
										else if (lowerMsg.contains("bridge")) fileToRead = "bridge_builder.js";
										else if (lowerMsg.contains("wall")) fileToRead = "giant_wall.js";
										else if (lowerMsg.contains("cube")) fileToRead = "slot1_cube.js";
										else if (lowerMsg.contains("slot 1") || lowerMsg.contains("slot1")) fileToRead = "slot1.js";
										else if (lowerMsg.contains("slot 2") || lowerMsg.contains("slot2")) fileToRead = "slot2.js";
										else if (lowerMsg.contains("slot 3") || lowerMsg.contains("slot3")) fileToRead = "slot3.js";

										String visibleScriptContext = "";
										if (!fileToRead.isEmpty()) visibleScriptContext = readExistingScript("server", fileToRead);

										StringBuilder jsonBuilder = new StringBuilder();
										jsonBuilder.append("{")
												.append("\"stream\": true,")
												.append("\"temperature\": 0.2,")
												.append("\"max_tokens\": 1024,")
												.append("\"messages\": [");

										String finalSystem = SYSTEM_PROMPT;
										if (!echoProfile.isEmpty()) finalSystem += "\n\n[ECHO CORE DIRECTIVES & PERSONALITY]:\n" + echoProfile;
										if (!longTermMemories.isEmpty()) finalSystem += "\n\n[MEMORIES]:\n" + longTermMemories;
										if (!injectedChatHistory.isEmpty()) finalSystem += "\n\n[PAST CONVERSATION]:\n" + injectedChatHistory;
										if (!visibleScriptContext.isEmpty()) finalSystem += visibleScriptContext;

										jsonBuilder.append("{\"role\":\"system\",\"content\":\"").append(escapeJson(finalSystem)).append("\"},");

										for (String mem : shortTermMemory) jsonBuilder.append(mem).append(",");

										String fullUserPrompt = playerContext + "User: " + userMessage;
										jsonBuilder.append("{\"role\":\"user\",\"content\":\"").append(escapeJson(fullUserPrompt)).append("\"}");
										jsonBuilder.append("]}");

										HttpRequest request = HttpRequest.newBuilder()
												.uri(URI.create("http://127.0.0.1:8080/v1/chat/completions"))
												.header("Content-Type", "application/json")
												.POST(HttpRequest.BodyPublishers.ofString(jsonBuilder.toString()))
												.build();

										HttpResponse<java.io.InputStream> httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

										StringBuilder response = new StringBuilder();
										StringBuilder sentenceBuffer = new StringBuilder();
										int lastCleanLength = 0;

										try (BufferedReader reader = new BufferedReader(new InputStreamReader(httpResponse.body(), StandardCharsets.UTF_8))) {
											String line;
											while ((line = reader.readLine()) != null) {
												if (line.startsWith("data: ") && !line.equals("data: [DONE]")) {
													String data = line.substring(6);
													int contentIdx = data.indexOf("\"content\":\"");
													if (contentIdx != -1) {
														int start = contentIdx + 11;
														int end = data.indexOf("\"", start);
														while (end != -1 && data.charAt(end - 1) == '\\') end = data.indexOf("\"", end + 1);

														if (end != -1) {
															String token = data.substring(start, end).replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");
															response.append(token);

															String cleanText = response.toString()
																	.replaceAll("(?s)<<write_script.*?<<end_script>>", "")
																	.replaceAll("(?s)<<write_script.*", "")
																	.replaceAll("(?s)<<.*?>>", "")
																	.replaceAll("(?s)<<.*", "");

															if (cleanText.length() > lastCleanLength) {
																String newText = cleanText.substring(lastCleanLength);
																lastCleanLength = cleanText.length();

																sentenceBuffer.append(newText);

																if (context.getSource().getPlayer() != null) {
																	String liveText = sentenceBuffer.toString().replaceAll("§[0-9a-fk-or]", "").replace("\n", " ").trim();
																	if (liveText.length() > 60) liveText = liveText.substring(liveText.length() - 60);
																	context.getSource().getPlayer().displayClientMessage(Component.literal("§b" + liveText), true);
																}

																int splitIdx = -1;
																for (int i = 0; i < sentenceBuffer.length(); i++) {
																	char c = sentenceBuffer.charAt(i);
																	if (c == '.' || c == '!' || c == '?') {
																		if (i > 0 && Character.isDigit(sentenceBuffer.charAt(i - 1))) continue;
																		splitIdx = i + 1; break;
																	}
																}

																if (splitIdx != -1) {
																	String sentence = sentenceBuffer.substring(0, splitIdx).trim();
																	sentenceBuffer.delete(0, splitIdx);

																	String cleanSentence = sentence.replaceAll("[^a-zA-Z0-9\\s.,!?'-]", "").trim();
																	if (cleanSentence.replaceAll("[^a-zA-Z0-9]", "").length() >= 2) speak(cleanSentence);
																}
															}
														}
													}
												}
											}
										}

										if (!sentenceBuffer.isEmpty()) {
											String cleanSentence = sentenceBuffer.toString().replaceAll("[^a-zA-Z0-9\\s.,!?'-]", "").trim();
											if (cleanSentence.replaceAll("[^a-zA-Z0-9]", "").length() >= 2) speak(cleanSentence);
										}

										if (context.getSource().getPlayer() != null) {
											context.getSource().getPlayer().displayClientMessage(Component.literal(""), true);
										}

										String rawOutput = response.toString().trim();
										saveChatToDB("assistant", rawOutput);

										Pattern scriptPattern = Pattern.compile("<<write_script:\\s*(startup|server|tools|creations)/(.*?)>>(.*?)<<end_script>>", Pattern.DOTALL);
										Matcher scriptMatcher = scriptPattern.matcher(rawOutput);
										while (scriptMatcher.find()) writeScriptToKubeJS(scriptMatcher.group(1).trim(), scriptMatcher.group(2).trim(), scriptMatcher.group(3).trim());

										Pattern texPattern = Pattern.compile("<<draw_texture:\\s*(.*?)\\|(.*?)>>", Pattern.DOTALL);
										Matcher texMatcher = texPattern.matcher(rawOutput);
										while (texMatcher.find()) drawTextureToKubeJS(texMatcher.group(1).trim(), texMatcher.group(2).trim());

										List<String> commandsToRun = new ArrayList<>();
										Pattern cmdPattern = Pattern.compile("<<run:\\s*(.*?)>>");
										Matcher cmdMatcher = cmdPattern.matcher(rawOutput);
										while (cmdMatcher.find()) commandsToRun.add(cmdMatcher.group(1).trim());

										Pattern memPattern = Pattern.compile("<<save_memory:\\s*(.*?)\\|(.*?)>>");
										Matcher memMatcher = memPattern.matcher(rawOutput);
										while (memMatcher.find()) saveMemoryToDB(memMatcher.group(1).trim(), memMatcher.group(2).trim());

										Pattern playerPattern = Pattern.compile("<<update_player:\\s*(.*?)\\|(.*?)>>");
										Matcher playerMatcher = playerPattern.matcher(rawOutput);
										while (playerMatcher.find()) updatePlayerProfileDB(playerMatcher.group(1).trim(), playerMatcher.group(2).trim());

										Pattern echoProfPattern = Pattern.compile("<<update_echo:\\s*(.*?)\\|(.*?)>>");
										Matcher echoProfMatcher = echoProfPattern.matcher(rawOutput);
										while (echoProfMatcher.find()) updateEchoProfileDB(echoProfMatcher.group(1).trim(), echoProfMatcher.group(2).trim());

										String finalChatText = scriptPattern.matcher(rawOutput).replaceAll("");
										finalChatText = texPattern.matcher(finalChatText).replaceAll("");
										finalChatText = cmdPattern.matcher(finalChatText).replaceAll("");
										finalChatText = memPattern.matcher(finalChatText).replaceAll("");
										finalChatText = playerPattern.matcher(finalChatText).replaceAll("");
										finalChatText = echoProfPattern.matcher(finalChatText).replaceAll("").trim();

										if (!finalChatText.isEmpty()) context.getSource().sendSystemMessage(Component.literal("§b[Echo]: " + finalChatText));

										if (!commandsToRun.isEmpty()) {
											context.getSource().getServer().execute(() -> {
												for (String cmd : commandsToRun) {
													context.getSource().getServer().getCommands().performPrefixedCommand(context.getSource(), cmd);
												}
											});
										}

										shortTermMemory.add("{\"role\":\"user\",\"content\":\"" + escapeJson(userMessage) + "\"}");
										shortTermMemory.add("{\"role\":\"assistant\",\"content\":\"" + escapeJson(rawOutput) + "\"}");
										if (shortTermMemory.size() > 8) { shortTermMemory.remove(0); shortTermMemory.remove(0); }

									} catch (Exception e) {
										LOGGER.error("[Echo Error]", e);
										context.getSource().sendSystemMessage(Component.literal("§c[Echo Error]: Brain misfire."));
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

	private static String readExistingScript(String folder, String filename) {
		filename = filename.replaceAll("[^a-zA-Z0-9_.-]", "");
		String path = folder.equals("startup") ? "kubejs/startup_scripts/echo_systems/constructs/" : "kubejs/server_scripts/echo_systems/constructs/";
		File file = new File(path + filename);
		if (!file.exists()) return "";
		StringBuilder content = new StringBuilder();
		try (BufferedReader br = new BufferedReader(new java.io.FileReader(file))) {
			String line;
			while ((line = br.readLine()) != null) content.append(line).append("\n");
			return "\n\n[EXISTING SCRIPT CONTENT FOR " + filename + "]:\n" + content.toString();
		} catch (IOException e) { return ""; }
	}

	private static void writeScriptToKubeJS(String folder, String filename, String code) {
		filename = filename.replaceAll("[^a-zA-Z0-9_.-]", "");
		String path = folder.equals("startup") ? "kubejs/startup_scripts/echo_systems/constructs" : "kubejs/server_scripts/echo_systems/constructs";
		File kubejsDir = new File(path);
		if (!kubejsDir.exists()) { boolean ignored = kubejsDir.mkdirs(); }
		File scriptFile = new File(kubejsDir, filename);
		try (FileWriter writer = new FileWriter(scriptFile, false)) {
			writer.write(code);
		} catch (Exception e) { LOGGER.error("[Echo Scripting] Failed to write script.", e); }
	}

	private static void drawTextureToKubeJS(String itemName, String jsonGrid) {
		try {
			itemName = itemName.replaceAll("[^a-zA-Z0-9_.-]", "");
			File assetsDir = new File("kubejs/assets/kubejs/textures/item");
			if (!assetsDir.exists()) { boolean ignored = assetsDir.mkdirs(); }
			File imgFile = new File(assetsDir, itemName + ".png");
			BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
			JsonArray rows = JsonParser.parseString(jsonGrid).getAsJsonArray();
			for (int y = 0; y < Math.min(rows.size(), 16); y++) {
				JsonArray cols = rows.get(y).getAsJsonArray();
				for (int x = 0; x < Math.min(cols.size(), 16); x++) {
					String hex = cols.get(x).getAsString().trim();
					if (hex.equalsIgnoreCase("transparent") || hex.equalsIgnoreCase("none") || hex.isEmpty()) img.setRGB(x, y, 0x00000000);
					else {
						try { img.setRGB(x, y, Color.decode(hex).getRGB()); }
						catch (Exception ignored) { img.setRGB(x, y, 0x00000000); }
					}
				}
			}
			ImageIO.write(img, "png", imgFile);
		} catch (Exception e) { LOGGER.error("[Echo Art] Failed to draw texture.", e); }
	}

	private static void initDatabase() {
		try (Connection conn = DriverManager.getConnection(DB_URL); Statement stmt = conn.createStatement()) {
			stmt.execute("CREATE TABLE IF NOT EXISTS memories (id INTEGER PRIMARY KEY AUTOINCREMENT, category TEXT NOT NULL, details TEXT NOT NULL);");
			stmt.execute("CREATE TABLE IF NOT EXISTS chat_history (id INTEGER PRIMARY KEY AUTOINCREMENT, role TEXT NOT NULL, content TEXT NOT NULL, timestamp DATETIME DEFAULT CURRENT_TIMESTAMP);");
			stmt.execute("CREATE TABLE IF NOT EXISTS player_profile (trait TEXT PRIMARY KEY, value TEXT NOT NULL);");
			stmt.execute("CREATE TABLE IF NOT EXISTS echo_profile (trait TEXT PRIMARY KEY, value TEXT NOT NULL);");
		} catch (Exception e) {}
	}

	private static void updatePlayerProfileDB(String trait, String value) {
		String sql = "INSERT OR REPLACE INTO player_profile(trait, value) VALUES(?,?)";
		try (Connection conn = DriverManager.getConnection(DB_URL); PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setString(1, trait); pstmt.setString(2, value); pstmt.executeUpdate();
		} catch (Exception e) {}
	}

	private static String getPlayerProfileFromDB() {
		StringBuilder sb = new StringBuilder();
		try (Connection conn = DriverManager.getConnection(DB_URL); Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT trait, value FROM player_profile")) {
			while (rs.next()) sb.append("- ").append(rs.getString("trait")).append(": ").append(rs.getString("value")).append("\n");
		} catch (Exception e) {}
		return sb.toString();
	}

	private static void updateEchoProfileDB(String trait, String value) {
		String sql = "INSERT OR REPLACE INTO echo_profile(trait, value) VALUES(?,?)";
		try (Connection conn = DriverManager.getConnection(DB_URL); PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setString(1, trait); pstmt.setString(2, value); pstmt.executeUpdate();
		} catch (Exception e) {}
	}

	private static String getEchoProfileFromDB() {
		StringBuilder sb = new StringBuilder();
		try (Connection conn = DriverManager.getConnection(DB_URL); Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT trait, value FROM echo_profile")) {
			while (rs.next()) sb.append("- ").append(rs.getString("trait")).append(": ").append(rs.getString("value")).append("\n");
		} catch (Exception e) {}
		return sb.toString();
	}

	private static void saveMemoryToDB(String category, String details) {
		String sql = "INSERT INTO memories(category, details) VALUES(?,?)";
		try (Connection conn = DriverManager.getConnection(DB_URL); PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setString(1, category); pstmt.setString(2, details); pstmt.executeUpdate();
		} catch (Exception e) {}
	}

	private static String getMemoriesFromDB() {
		StringBuilder sb = new StringBuilder();
		try (Connection conn = DriverManager.getConnection(DB_URL); Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT category, details FROM memories")) {
			while (rs.next()) sb.append("- [").append(rs.getString("category")).append("]: ").append(rs.getString("details")).append("\n");
		} catch (Exception e) {}
		return sb.toString();
	}

	private static void saveChatToDB(String role, String content) {
		String sql = "INSERT INTO chat_history(role, content) VALUES(?,?)";
		try (Connection conn = DriverManager.getConnection(DB_URL); PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setString(1, role); pstmt.setString(2, content); pstmt.executeUpdate();
		} catch (Exception e) {}
	}

	private static String searchJournalSQL(String query) {
		StringBuilder found = new StringBuilder();
		String[] words = query.toLowerCase().split("\\W+");
		if (words.length == 0) return "";
		StringBuilder sql = new StringBuilder("SELECT role, content FROM chat_history WHERE ");
		List<String> validWords = new ArrayList<>();
		for (String word : words) {
			if (word.length() > 3) {
				if (!validWords.isEmpty()) sql.append(" OR ");
				sql.append("content LIKE ?");
				validWords.add(word);
			}
		}
		if (validWords.isEmpty()) return "";
		sql.append(" ORDER BY id DESC LIMIT 5");
		try (Connection conn = DriverManager.getConnection(DB_URL); PreparedStatement pstmt = conn.prepareStatement(sql.toString())) {
			for (int i = 0; i < validWords.size(); i++) pstmt.setString(i + 1, "%" + validWords.get(i) + "%");
			ResultSet rs = pstmt.executeQuery();
			while (rs.next()) found.insert(0, "[" + rs.getString("role").toUpperCase() + "]: " + rs.getString("content") + "\n");
		} catch (Exception e) {}
		return found.toString();
	}

	private static void wipeMemoryDB() {
		try (Connection conn = DriverManager.getConnection(DB_URL); Statement stmt = conn.createStatement()) {
			stmt.execute("DELETE FROM memories;"); stmt.execute("DELETE FROM chat_history;");
			stmt.execute("DELETE FROM player_profile;"); stmt.execute("DELETE FROM echo_profile;");
		} catch (Exception e) {}
	}

	private static void wipeGeneratedScripts() {
		File dir = new File("kubejs/server_scripts/echo_systems/constructs");
		if (dir.exists() && dir.isDirectory()) {
			File[] files = dir.listFiles();
			if (files != null) for (File file : files) { boolean ignored = file.delete(); }
		}
	}

	private static void bootEmbeddedEngine() {
		new Thread(() -> {
			try {
				File engineDir = new File(SYS_DIR + "/engine");
				if (!engineDir.exists()) engineDir = new File("../" + SYS_DIR + "/engine");
				File modelFile = new File(SYS_DIR + "/models/qwen2.5-coder-14b-instruct-q4_k_m.gguf");
				if (!modelFile.exists()) modelFile = new File("../" + SYS_DIR + "/models/qwen2.5-coder-14b-instruct-q4_k_m.gguf");
				ProcessBuilder pb = new ProcessBuilder(new File(engineDir, "llama-server.exe").getAbsolutePath(), "-m", modelFile.getAbsolutePath(), "-ngl", "99", "--port", "8080");
				pb.redirectErrorStream(true);
				activeEngineProcess = pb.start();
				try (BufferedReader reader = new BufferedReader(new InputStreamReader(activeEngineProcess.getInputStream()))) {
					String line; while ((line = reader.readLine()) != null) if (line.contains("HTTP server listening")) break;
				}
			} catch (Exception e) {}
		}).start();
	}

	private static void stopTTS() {
		if (activeTtsProcess != null && activeTtsProcess.isAlive()) { activeTtsProcess.destroyForcibly(); activeTtsProcess = null; }
	}

	public static void speak(String cleanText) {
		ttsQueue.submit(() -> {
			try {
				File piperDir = new File(SYS_DIR + "/piper");
				if (!piperDir.exists()) piperDir = new File("../" + SYS_DIR + "/piper");
				File piperExe = new File(piperDir, System.getProperty("os.name").toLowerCase().contains("win") ? "piper.exe" : "piper");
				File modelFile = new File(piperDir, "en_US-ryan-medium.onnx");
				if (!piperExe.exists() || !modelFile.exists()) { fallbackSpeak(cleanText); return; }
				ProcessBuilder pb = new ProcessBuilder(piperExe.getAbsolutePath(), "-m", modelFile.getAbsolutePath(), "--length_scale", "1.25", "--output_raw");
				activeTtsProcess = pb.start();
				Process process = activeTtsProcess;
				try (java.io.BufferedWriter writer = new java.io.BufferedWriter(new java.io.OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8))) {
					writer.write(cleanText); writer.flush();
				}
				AudioFormat format = new AudioFormat(22050, 16, 1, true, false);
				DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
				try (BufferedInputStream bis = new BufferedInputStream(process.getInputStream()); SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info)) {
					line.open(format); line.start();
					byte[] buffer = new byte[4096]; int leftover = 0, read;
					while ((read = bis.read(buffer, leftover, buffer.length - leftover)) != -1) {
						int totalBytes = leftover + read; int bytesToWrite = totalBytes - (totalBytes % 2);
						if (bytesToWrite > 0) line.write(buffer, 0, bytesToWrite);
						leftover = totalBytes - bytesToWrite; if (leftover > 0) buffer[0] = buffer[bytesToWrite];
					}
					process.waitFor(); line.drain(); line.stop(); line.close();
				}
				process.destroy();
			} catch (Exception e) { fallbackSpeak(cleanText); }
		});
	}

	private static void fallbackSpeak(String cleanText) {
		try {
			if (System.getProperty("os.name").toLowerCase().contains("win")) {
				File tempVbs = File.createTempFile("echo_speech", ".vbs"); tempVbs.deleteOnExit();
				try (java.io.FileWriter writer = new java.io.FileWriter(tempVbs)) {
					writer.write("CreateObject(\"SAPI.SpVoice\").Speak \"" + cleanText.replace("\"", "\"\"") + "\"");
				}
				activeTtsProcess = Runtime.getRuntime().exec("wscript \"" + tempVbs.getAbsolutePath() + "\"");
			}
		} catch (Exception ignored) {}
	}

	private static String escapeJson(String input) {
		return input.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
	}
}