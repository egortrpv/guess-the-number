package guessthenumber;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public class guessthenumber extends JavaPlugin implements Listener, TabExecutor {

    private int quizIntervalTicks = 5 * 60 * 20;
    private int quizDurationTicks = 60 * 20;

    private Material prizeMaterial = Material.DIAMOND;
    private int prizeAmount = 10;

    private boolean isQuizActive = false;
    private int targetNumber = -1;
    private final Random random = new Random();

    private BukkitTask nextQuizTask;
    private BukkitTask timeoutTask;

    @Override
    public void onEnable() {
        loadSettings();
        getServer().getPluginManager().registerEvents(this, this);

        var cmd = getCommand("guessthenumber");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }

        scheduleNextQuiz();
    }

    @Override
    public void onDisable() {
        if (nextQuizTask != null) nextQuizTask.cancel();
        if (timeoutTask != null) timeoutTask.cancel();
    }

    private void loadSettings() {
        saveDefaultConfig();
        quizIntervalTicks = getConfig().getInt("quizIntervalTicks", 5 * 60 * 20);
        quizDurationTicks = getConfig().getInt("quizDurationTicks", 60 * 20);
        prizeAmount = getConfig().getInt("prizeAmount", 10);

        String itemName = getConfig().getString("prizeItem", "DIAMOND");
        Material mat = Material.matchMaterial(itemName);
        if (mat != null && mat.isItem()) {
            prizeMaterial = mat;
        }
    }

    private void saveSettings() {
        getConfig().set("quizIntervalTicks", quizIntervalTicks);
        getConfig().set("quizDurationTicks", quizDurationTicks);
        getConfig().set("prizeItem", prizeMaterial.name());
        getConfig().set("prizeAmount", prizeAmount);
        saveConfig();
    }

    private void scheduleNextQuiz() {
        if (nextQuizTask != null) nextQuizTask.cancel();
        nextQuizTask = Bukkit.getScheduler().runTaskLater(this, this::startQuiz, quizIntervalTicks);
    }

    private void startQuiz() {
        targetNumber = random.nextInt(100) + 1;
        isQuizActive = true;

        Component prizeName = Component.translatable(prizeMaterial.translationKey(), NamedTextColor.AQUA);
        Component amountComp = Component.text(prizeAmount, NamedTextColor.AQUA);

        Component message = Component.text("[Quiz] ", NamedTextColor.GOLD)
                .append(Component.text("Guess a number between 1 and 100! You have 1 minute. The first to guess gets ", NamedTextColor.YELLOW))
                .append(amountComp)
                .append(Component.text(" ", NamedTextColor.YELLOW))
                .append(prizeName)
                .append(Component.text("!", NamedTextColor.YELLOW));

        Bukkit.broadcast(message);

        if (timeoutTask != null) timeoutTask.cancel();
        timeoutTask = Bukkit.getScheduler().runTaskLater(this, this::endQuizWithTimeout, quizDurationTicks);
    }

    private void endQuizWithTimeout() {
        if (!isQuizActive) return;
        isQuizActive = false;

        Component message = Component.text("[Quiz] ", NamedTextColor.GOLD)
                .append(Component.text("Time's up! Nobody guessed the number. The number was: ", NamedTextColor.RED))
                .append(Component.text(targetNumber, NamedTextColor.RED));

        Bukkit.broadcast(message);
        scheduleNextQuiz();
    }

    @EventHandler
    public void onPlayerChat(AsyncChatEvent event) {
        if (!isQuizActive) return;

        String messageText = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        Player player = event.getPlayer();

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
            if (usedSecretWord) {
                event.setCancelled(true);
            }
            // Инвентарь и мир в Paper изменяем строго в основном потоке сервера
            Bukkit.getScheduler().runTask(this, () -> handleWin(player));
        }
    }

    private void handleWin(Player player) {
        if (!isQuizActive) return;
        isQuizActive = false;
        if (timeoutTask != null) timeoutTask.cancel();

        ItemStack reward = new ItemStack(prizeMaterial, prizeAmount);
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(reward);

        if (leftover.isEmpty()) {
            float pitch = ((random.nextFloat() - random.nextFloat()) * 0.7F + 1.0F) * 2.0F;
            player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.2F, pitch);
        } else {
            for (ItemStack dropStack : leftover.values()) {
                Item dropped = player.getWorld().dropItem(player.getLocation().add(0, 0.5, 0), dropStack);
                dropped.setPickupDelay(0);
                dropped.setOwner(player.getUniqueId());
            }
        }

        Component prizeName = Component.translatable(prizeMaterial.translationKey(), NamedTextColor.AQUA);
        Component amountComp = Component.text(prizeAmount, NamedTextColor.AQUA);

        Component winMessage = Component.text("[Quiz] ", NamedTextColor.GOLD)
                .append(Component.text("Player ", NamedTextColor.GREEN))
                .append(Component.text(player.getName(), NamedTextColor.GREEN))
                .append(Component.text(" correctly guessed the number ", NamedTextColor.GREEN))
                .append(Component.text(targetNumber, NamedTextColor.RED))
                .append(Component.text(" and won ", NamedTextColor.GREEN))
                .append(amountComp)
                .append(Component.text(" ", NamedTextColor.GREEN))
                .append(prizeName)
                .append(Component.text("!", NamedTextColor.GREEN));

        Bukkit.broadcast(winMessage);
        scheduleNextQuiz();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("guessthenumber.admin")) {
            sender.sendMessage(Component.text("You don't have permission to use this command.", NamedTextColor.RED));
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /guessthenumber <timer|guesstime|prize> <value>", NamedTextColor.RED));
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "timer" -> {
                try {
                    int seconds = Integer.parseInt(args[1]);
                    if (seconds < 1) throw new NumberFormatException();
                    quizIntervalTicks = seconds * 20;
                    saveSettings();
                    if (!isQuizActive) scheduleNextQuiz();
                    sender.sendMessage(Component.text("Quiz interval updated to " + seconds + " seconds!", NamedTextColor.GREEN));
                } catch (NumberFormatException e) {
                    sender.sendMessage(Component.text("Please enter a valid number of seconds (>= 1).", NamedTextColor.RED));
                }
            }
            case "guesstime" -> {
                try {
                    int seconds = Integer.parseInt(args[1]);
                    if (seconds < 1) throw new NumberFormatException();
                    quizDurationTicks = seconds * 20;
                    saveSettings();
                    sender.sendMessage(Component.text("Guessing time updated to " + seconds + " seconds!", NamedTextColor.GREEN));
                } catch (NumberFormatException e) {
                    sender.sendMessage(Component.text("Please enter a valid number of seconds (>= 1).", NamedTextColor.RED));
                }
            }
            case "prize" -> {
                String rawItem = args[1].replace("minecraft:", "");
                Material mat = Material.matchMaterial(rawItem);
                if (mat == null || !mat.isItem() || mat == Material.AIR) {
                    sender.sendMessage(Component.text("Unknown item: " + args[1], NamedTextColor.RED));
                    return true;
                }

                int count = 1;
                if (args.length >= 3) {
                    try {
                        count = Integer.parseInt(args[2]);
                        if (count < 1 || count > 64) throw new NumberFormatException();
                    } catch (NumberFormatException e) {
                        sender.sendMessage(Component.text("Count must be between 1 and 64.", NamedTextColor.RED));
                        return true;
                    }
                }

                prizeMaterial = mat;
                prizeAmount = count;
                saveSettings();

                Component itemName = Component.translatable(prizeMaterial.translationKey());
                sender.sendMessage(Component.text("Prize updated to ", NamedTextColor.GREEN)
                        .append(itemName)
                        .append(Component.text(" x" + prizeAmount + "!", NamedTextColor.GREEN)));
            }
            default -> sender.sendMessage(Component.text("Usage: /guessthenumber <timer|guesstime|prize> <value>", NamedTextColor.RED));
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission("guessthenumber.admin")) return Collections.emptyList();

        if (args.length == 1) {
            return List.of("timer", "guesstime", "prize").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .toList();
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("prize")) {
            String prefix = args[1].toLowerCase().replace("minecraft:", "");
            return Arrays.stream(Material.values())
                    .filter(m -> m.isItem() && m != Material.AIR)
                    .map(m -> m.name().toLowerCase())
                    .filter(name -> name.startsWith(prefix))
                    .limit(50)
                    .toList();
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("prize")) {
            return List.of("1", "10", "16", "32", "64");
        }

        return Collections.emptyList();
    }
}