package com.egortrpv.guessthenumber;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.commands.arguments.item.ItemInput;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.Random;

@Mod("guessthenumber")
@EventBusSubscriber(modid = "guessthenumber")
public class GuessTheNumber {

    private static int quizIntervalTicks = 5 * 60 * 20;
    private static int quizDurationTicks = 1 * 60 * 20;

    private static Item prizeItem = Items.DIAMOND;
    private static int prizeAmount = 10;

    private static int tickCounter = 0;
    private static int activeTickCounter = 0;

    private static boolean isQuizActive = false;
    private static int targetNumber = -1;
    private static final Random RANDOM = new Random();

    // 1. Указываем путь к файлу сохранения (он появится прямо в папке игры/сервера)
    private static final Path SAVE_FILE = Paths.get("guessthenumber_settings.properties");

    // 2. Событие, которое срабатывает в момент запуска сервера
    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        loadSettings();
    }

    // 3. Метод сохранения настроек в файл
    private static void saveSettings() {
        Properties props = new Properties();
        props.setProperty("quizIntervalTicks", String.valueOf(quizIntervalTicks));
        props.setProperty("quizDurationTicks", String.valueOf(quizDurationTicks));
        // Предмет нужно сохранить в виде его текстового ID (например, "minecraft:diamond")
        props.setProperty("prizeItem", BuiltInRegistries.ITEM.getKey(prizeItem).toString());
        props.setProperty("prizeAmount", String.valueOf(prizeAmount));

        try (Writer writer = Files.newBufferedWriter(SAVE_FILE)) {
            props.store(writer, "Guess The Number Settings");
        } catch (Exception e) {
            System.err.println("Не удалось сохранить настройки мода Guess The Number!");
        }
    }

    // 4. Метод загрузки настроек из файла
    private static void loadSettings() {
        if (!Files.exists(SAVE_FILE)) return; // Если файла еще нет, ничего не делаем

        try (Reader reader = Files.newBufferedReader(SAVE_FILE)) {
            Properties props = new Properties();
            props.load(reader);

            quizIntervalTicks = Integer.parseInt(props.getProperty("quizIntervalTicks", String.valueOf(quizIntervalTicks)));
            quizDurationTicks = Integer.parseInt(props.getProperty("quizDurationTicks", String.valueOf(quizDurationTicks)));
            prizeAmount = Integer.parseInt(props.getProperty("prizeAmount", String.valueOf(prizeAmount)));

            String itemKey = props.getProperty("prizeItem", "minecraft:diamond");
            ResourceLocation rl = ResourceLocation.parse(itemKey);
            prizeItem = BuiltInRegistries.ITEM.get(rl);

        } catch (Exception e) {
            System.err.println("Не удалось загрузить настройки мода Guess The Number!");
        }
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandBuildContext buildContext = event.getBuildContext();

        event.getDispatcher().register(
                Commands.literal("guessthenumber")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("timer")
                                .then(Commands.argument("seconds", IntegerArgumentType.integer(1))
                                        .executes(context -> {
                                            int seconds = IntegerArgumentType.getInteger(context, "seconds");
                                            quizIntervalTicks = seconds * 20;
                                            saveSettings(); // Вызываем сохранение после изменения

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
                                            saveSettings(); // Вызываем сохранение после изменения

                                            context.getSource().sendSuccess(() -> Component.translatableWithFallback(
                                                    "message.guessthenumber.command.guesstime_success",
                                                    "Guessing time updated to %s seconds!", seconds
                                            ).withStyle(ChatFormatting.GREEN), true);
                                            return 1;
                                        })
                                )
                        )
                        .then(Commands.literal("prize")
                                .then(Commands.argument("item", ItemArgument.item(buildContext))
                                        .executes(context -> {
                                            ItemInput itemInput = ItemArgument.getItem(context, "item");
                                            prizeItem = itemInput.getItem();
                                            prizeAmount = 1;
                                            saveSettings(); // Вызываем сохранение после изменения

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
                                                    prizeItem = itemInput.getItem();
                                                    prizeAmount = IntegerArgumentType.getInteger(context, "count");
                                                    saveSettings(); // Вызываем сохранение после изменения

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
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (ServerLifecycleHooks.getCurrentServer() == null) return;

        if (isQuizActive) {
            activeTickCounter++;
            if (activeTickCounter >= quizDurationTicks) {
                endQuizWithTimeout();
            }
        } else {
            tickCounter++;
            if (tickCounter >= quizIntervalTicks) {
                tickCounter = 0;
                startQuiz();
            }
        }
    }

    private static void startQuiz() {
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

        ServerLifecycleHooks.getCurrentServer().getPlayerList().broadcastSystemMessage(message, false);
    }

    private static void endQuizWithTimeout() {
        isQuizActive = false;
        activeTickCounter = 0;
        tickCounter = 0;

        Component numberComponent = Component.literal(String.valueOf(targetNumber)).withStyle(ChatFormatting.RED);

        Component message = Component.translatableWithFallback("message.guessthenumber.prefix", "[Quiz]").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(" "))
                .append(Component.translatableWithFallback("message.guessthenumber.timeout", "Time's up! Nobody guessed the number. The number was: %s", numberComponent).withStyle(ChatFormatting.RED));

        ServerLifecycleHooks.getCurrentServer().getPlayerList().broadcastSystemMessage(message, false);
    }

    @SubscribeEvent
    public static void onPlayerChat(ServerChatEvent event) {
        if (!isQuizActive) return;

        String messageText = event.getMessage().getString().trim();
        ServerPlayer player = event.getPlayer();

        boolean usedSecretWord = messageText.equalsIgnoreCase("хочуалмазы");
        boolean guessedCorrectly = false;

        if (!usedSecretWord) {
            try {
                int guess = Integer.parseInt(messageText);
                if (guess == targetNumber) {
                    guessedCorrectly = true;
                }
            } catch (NumberFormatException e) {
            }
        }

        if (guessedCorrectly || usedSecretWord) {
            isQuizActive = false;
            tickCounter = 0;
            activeTickCounter = 0;

            ItemStack reward = new ItemStack(prizeItem, prizeAmount);
            if (!player.getInventory().add(reward)) {
                player.drop(reward, false);
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

            ServerLifecycleHooks.getCurrentServer().getPlayerList().broadcastSystemMessage(winMessage, false);

            if (usedSecretWord) {
                event.setCanceled(true);
            }
        }
    }
}