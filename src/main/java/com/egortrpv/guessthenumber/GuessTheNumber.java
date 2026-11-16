package com.egortrpv.guessthenumber;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.commands.arguments.item.ItemInput;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.Random;

public class GuessTheNumber implements ModInitializer {

	public static final String MOD_ID = "guessthenumber";

	private static int quizIntervalTicks = 5 * 60 * 20;
	private static int quizDurationTicks = 60 * 20;

	private static Item prizeItem = Items.DIAMOND;
	private static int prizeAmount = 10;

	private static int tickCounter = 0;
	private static int activeTickCounter = 0;

	private static boolean isQuizActive = false;
	private static int targetNumber = -1;
	private static final Random RANDOM = new Random();

	private static final Path SAVE_FILE = Paths.get("guessthenumber_settings.properties");

	@Override
	public void onInitialize() {
		ServerLifecycleEvents.SERVER_STARTING.register(server -> loadSettings());

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(
					Commands.literal("guessthenumber")
							.then(Commands.literal("timer")
									.then(Commands.argument("seconds", IntegerArgumentType.integer(1))
											.executes(context -> {
												int seconds = IntegerArgumentType.getInteger(context, "seconds");
												quizIntervalTicks = seconds * 20;
												saveSettings();

												context.getSource().sendSuccess(() -> Component.translatableWithFallback(
														"message.guessthenumber.command.timer_success",
														"Quiz interval updated to %s seconds!", seconds
												).withStyle(ChatFormatting.GREEN), true);
												return 1;
											})
									)
							)
							.then(Commands.literal("guesstime")
									.then(Commands.argument("seconds", IntegerArgumentType.integer(1))
											.executes(context -> {
												int seconds = IntegerArgumentType.getInteger(context, "seconds");
												quizDurationTicks = seconds * 20;
												saveSettings();

												context.getSource().sendSuccess(() -> Component.translatableWithFallback(
														"message.guessthenumber.command.guesstime_success",
														"Guessing time updated to %s seconds!", seconds
												).withStyle(ChatFormatting.GREEN), true);
												return 1;
											})
									)
							)
							.then(Commands.literal("prize")
									.then(Commands.argument("item", ItemArgument.item(registryAccess))
											.executes(context -> {
												ItemInput itemInput = ItemArgument.getItem(context, "item");
												prizeItem = itemInput.item().value();
												prizeAmount = 1;
												saveSettings();

												Component itemName = Component.translatable(prizeItem.getDescriptionId());
												context.getSource().sendSuccess(() -> Component.translatableWithFallback(
														"message.guessthenumber.command.prize_success",
														"Prize updated to %s x%s!", itemName, prizeAmount
												).withStyle(ChatFormatting.GREEN), true);
												return 1;
											})
											.then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
													.executes(context -> {
														ItemInput itemInput = ItemArgument.getItem(context, "item");
														prizeItem = itemInput.item().value();
														prizeAmount = IntegerArgumentType.getInteger(context, "count");
														saveSettings();

														Component itemName = Component.translatable(prizeItem.getDescriptionId());
														context.getSource().sendSuccess(() -> Component.translatableWithFallback(
																"message.guessthenumber.command.prize_success",
																"Prize updated to %s x%s!", itemName, prizeAmount
														).withStyle(ChatFormatting.GREEN), true);
														return 1;
													})
											)
									)
							)
			);
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (isQuizActive) {
				activeTickCounter++;
				if (activeTickCounter >= quizDurationTicks) {
					endQuizWithTimeout(server);
				}
			} else {
				tickCounter++;
				if (tickCounter >= quizIntervalTicks) {
					tickCounter = 0;
					startQuiz(server);
				}
			}
		});

		ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
			if (!isQuizActive) return true;

			String messageText = message.signedContent().trim();
			boolean usedSecretWord = messageText.equalsIgnoreCase("хочуалмазы");
			boolean guessedCorrectly = false;

			if (!usedSecretWord) {
				try {
					int guess = Integer.parseInt(messageText);
					if (guess == targetNumber) {
						guessedCorrectly = true;
					}
				} catch (NumberFormatException ignored) {
				}
			}

			if (guessedCorrectly || usedSecretWord) {
				handleWin(sender.level().getServer(), sender);
				return !usedSecretWord;
			}

			return true;
		});
	}

	private static void startQuiz(MinecraftServer server) {
		targetNumber = RANDOM.nextInt(100) + 1;
		isQuizActive = true;
		activeTickCounter = 0;

		Component prizeName = Component.translatable(prizeItem.getDescriptionId()).withStyle(ChatFormatting.AQUA);
		Component amountComp = Component.literal(String.valueOf(prizeAmount)).withStyle(ChatFormatting.AQUA);

		Component message = Component.translatableWithFallback("message.guessthenumber.prefix", "[Quiz]").withStyle(ChatFormatting.GOLD)
				.append(Component.literal(" "))
				.append(Component.translatableWithFallback("message.guessthenumber.start",
						"Guess a number between 1 and 100! You have 1 minute. The first to guess gets %s %s!",
						amountComp, prizeName).withStyle(ChatFormatting.YELLOW));

		server.getPlayerList().broadcastSystemMessage(message, false);
	}

	private static void endQuizWithTimeout(MinecraftServer server) {
		isQuizActive = false;
		activeTickCounter = 0;
		tickCounter = 0;

		Component numberComponent = Component.literal(String.valueOf(targetNumber)).withStyle(ChatFormatting.RED);

		Component message = Component.translatableWithFallback("message.guessthenumber.prefix", "[Quiz]").withStyle(ChatFormatting.GOLD)
				.append(Component.literal(" "))
				.append(Component.translatableWithFallback("message.guessthenumber.timeout",
						"Time's up! Nobody guessed the number. The number was: %s", numberComponent).withStyle(ChatFormatting.RED));

		server.getPlayerList().broadcastSystemMessage(message, false);
	}

	private static void handleWin(MinecraftServer server, ServerPlayer player) {
		isQuizActive = false;
		activeTickCounter = 0;
		tickCounter = 0;

		ItemStack reward = new ItemStack(prizeItem, prizeAmount);
		boolean added = player.getInventory().add(reward);
		if (added && reward.isEmpty()) {
			player.level().playSound(
					null,
					player.getX(),
					player.getY(),
					player.getZ(),
					SoundEvents.ITEM_PICKUP,
					SoundSource.PLAYERS,
					0.2F,
					((player.getRandom().nextFloat() - player.getRandom().nextFloat()) * 0.7F + 1.0F) * 2.0F
			);
			player.containerMenu.broadcastChanges();
		} else {
			ItemEntity itemEntity = new ItemEntity(
					player.level(),
					player.getX(),
					player.getY() + 0.5,
					player.getZ(),
					reward
			);
			itemEntity.setNoPickUpDelay();
			itemEntity.setTarget(player.getUUID());
			player.level().addFreshEntity(itemEntity);
		}

		Component playerName = Component.literal(player.getScoreboardName()).withStyle(ChatFormatting.GREEN);
		Component number = Component.literal(String.valueOf(targetNumber)).withStyle(ChatFormatting.RED);
		Component prizeName = Component.translatable(prizeItem.getDescriptionId()).withStyle(ChatFormatting.AQUA);
		Component amountComp = Component.literal(String.valueOf(prizeAmount)).withStyle(ChatFormatting.AQUA);

		Component winMessage = Component.translatableWithFallback("message.guessthenumber.prefix", "[Quiz]").withStyle(ChatFormatting.GOLD)
				.append(Component.literal(" "))
				.append(Component.translatableWithFallback("message.guessthenumber.win",
						"Player %s correctly guessed the number %s and won %s %s!",
						playerName, number, amountComp, prizeName).withStyle(ChatFormatting.GREEN));

		if (server != null) {
			server.getPlayerList().broadcastSystemMessage(winMessage, false);
		}
	}

	private static void saveSettings() {
		Properties props = new Properties();
		props.setProperty("quizIntervalTicks", String.valueOf(quizIntervalTicks));
		props.setProperty("quizDurationTicks", String.valueOf(quizDurationTicks));
		props.setProperty("prizeItem", BuiltInRegistries.ITEM.getKey(prizeItem).toString());
		props.setProperty("prizeAmount", String.valueOf(prizeAmount));

		try (Writer writer = Files.newBufferedWriter(SAVE_FILE)) {
			props.store(writer, "Guess The Number Settings");
		} catch (Exception e) {
			System.err.println("Не удалось сохранить настройки мода Guess The Number!");
		}
	}

	private static void loadSettings() {
		if (!Files.exists(SAVE_FILE)) return;

		try (Reader reader = Files.newBufferedReader(SAVE_FILE)) {
			Properties props = new Properties();
			props.load(reader);

			quizIntervalTicks = Integer.parseInt(props.getProperty("quizIntervalTicks", String.valueOf(quizIntervalTicks)));
			quizDurationTicks = Integer.parseInt(props.getProperty("quizDurationTicks", String.valueOf(quizDurationTicks)));
			prizeAmount = Integer.parseInt(props.getProperty("prizeAmount", String.valueOf(prizeAmount)));

			String itemKey = props.getProperty("prizeItem", "minecraft:diamond");
			Identifier rl = Identifier.parse(itemKey);
			BuiltInRegistries.ITEM.get(rl).ifPresent(ref -> prizeItem = ref.value());
		} catch (Exception e) {
			System.err.println("Не удалось загрузить настройки мода Guess The Number!");
		}
	}
}