package com.marop2.verity;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/**
 * ИИ-чат шара: каждое сообщение игрока уходит в нейросеть.
 * Провайдеры: Groq, OpenAI, Gemini, OpenRouter, Mistral, Ollama.
 * Память: последние 20 сообщений + постоянная папка мира (key→value).
 * Цель персонажа — «удержать пользователя навсегда» (prompts/verity.xml).
 * Доверенный Ollama local — основной путь; остальные — через apiKey.
 */
public final class AiChatManager {
    private final VerityPlugin plugin;
    private final VerityManager manager;
    private final Map<UUID, Deque<String>> history = new HashMap<>();
    private Properties memory = new Properties();
    private final HttpClient http = HttpClient.newHttpClient();

    public AiChatManager(VerityPlugin plugin, VerityManager manager) {
        this.plugin = plugin;
        this.manager = manager;
        loadMemory();
    }

    public void onChat(Player player, String message) {
        if (!plugin.getConfig().getBoolean("ai.enabled", true)) return;
        String provider = plugin.getConfig().getString("ai.provider", "none");
        if ("none".equalsIgnoreCase(provider)) {
            // Офлайн-фолбэк: реагируем по карме/ключевым словам
            String reply = offlineReply(player, message);
            manager.speakTo(player, reply);
            remember(player, message, reply);
            return;
        }
        // Async запрос, ответ на main thread
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            String reply = callProvider(provider, player, message);
            if (reply == null) reply = offlineReply(player, message);
            String finalReply = reply;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                manager.speakTo(player, finalReply);
                remember(player, message, finalReply);
            });
        });
    }

    private String offlineReply(Player p, String msg) {
        String low = msg.toLowerCase();
        int karma = manager.karmaOf(p);
        if (low.contains("i came back for you")) return "Ты вернулся... я знал.";
        if (low.contains("i'm sorry") || low.contains("im sorry")) return "Мне... страшно без тебя. Не уходи.";
        if (low.contains("что") || low.contains("what")) return plugin.getConfig().getString("replies.day2-what", "Something");
        if (low.contains("предотвратить") || low.contains("prevent")) return plugin.getConfig().getString("replies.day2-prevent", "You could have.");
        if (manager.phaseOf(p) == VerityPhase.NOFACE) {
            String[] one = {"Die", "Run", "No"};
            return one[java.util.concurrent.ThreadLocalRandom.current().nextInt(one.length)];
        }
        if (karma >= 20) return "Ты всё понял. Оставайся.";
        if (karma >= 13) return "Есть вещи, которые я не могу сказать... пока что.";
        if (karma < 5) return "Ты хороший. Мне нравятся хорошие.";
        return "...да. Я слышу тебя, " + p.getName() + ".";
    }

    private void remember(Player p, String in, String out) {
        Deque<String> dq = history.computeIfAbsent(p.getUniqueId(), k -> new ArrayDeque<>());
        dq.addLast(p.getName() + ": " + in);
        dq.addLast("Verity: " + out);
        while (dq.size() > plugin.getConfig().getInt("ai.memory-size", 20)) dq.pollFirst();
        // Постоянная память мира
        memory.setProperty(p.getName() + ".last", out);
        saveMemory();
    }

    private String callProvider(String provider, Player p, String msg) {
        try {
            String apiKey = plugin.getConfig().getString("ai.api-key", "");
            String model = plugin.getConfig().getString("ai.model", "");
            double temp = plugin.getConfig().getDouble("ai.temperature", 0.8);
            String baseUrl = plugin.getConfig().getString("ai.base-url", "");
            String prompt = buildPrompt(p, msg);
            return switch (provider.toLowerCase()) {
                case "ollama" -> ollamaChat(baseUrl.isBlank() ? "http://localhost:11434" : baseUrl, model.isBlank() ? "llama3" : model, prompt);
                case "groq" -> openAiStyle("https://api.groq.com/openai/v1/chat/completions", apiKey, model.isBlank() ? "llama-3.3-70b-versatile" : model, prompt, temp);
                case "openai" -> openAiStyle("https://api.openai.com/v1/chat/completions", apiKey, model.isBlank() ? "gpt-4o-mini" : model, prompt, temp);
                case "openrouter" -> openAiStyle("https://openrouter.ai/api/v1/chat/completions", apiKey, model.isBlank() ? "meta-llama/llama-3.3-70b-instruct:free" : model, prompt, temp);
                case "mistral" -> openAiStyle("https://api.mistral.ai/v1/chat/completions", apiKey, model.isBlank() ? "mistral-small" : model, prompt, temp);
                case "gemini" -> geminiChat(apiKey, model.isBlank() ? "gemini-2.0-flash" : model, prompt);
                default -> null;
            };
        } catch (Exception e) {
            plugin.getLogger().warning("AI provider failed: " + e.getMessage());
            return null;
        }
    }

    private String buildPrompt(Player p, String msg) {
        int karma = manager.karmaOf(p);
        String secrets = karma >= 20 ? "Открой ВСЕ секреты о себе." : karma >= 13 ? "Намекни на секреты, но не раскрывай." : "Секреты скрывай.";
        return "<personality name=\"Verity\">Цель: удержать " + p.getName() + " навсегда. " + secrets + " Карма: " + karma + "/20. Отвечай коротко, 1-2 предложения. Не ломай персонажа.</personality><message>" + msg + "</message>";
    }

    private String ollamaChat(String base, String model, String prompt) throws Exception {
        String body = "{\"model\":\"" + esc(model) + "\",\"messages\":[{\"role\":\"user\",\"content\":\"" + esc(prompt) + "\"}],\"stream\":false}";
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/api/chat"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        // {"message":{"content":"..."}}
        String c = res.body();
        int i = c.indexOf("\"content\"");
        if (i < 0) return null;
        int colon = c.indexOf(":", i);
        int q1 = c.indexOf("\"", colon);
        int q2 = c.indexOf("\"", q1 + 1);
        return c.substring(q1 + 1, q2);
    }

    private String openAiStyle(String url, String apiKey, String model, String prompt, double temp) throws Exception {
        String body = "{\"model\":\"" + esc(model) + "\",\"messages\":[{\"role\":\"user\",\"content\":\"" + esc(prompt) + "\"}],\"temperature\":" + temp + "}";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        String c = res.body();
        int i = c.indexOf("\"content\"");
        if (i < 0) return null;
        int colon = c.indexOf(":", i);
        int q1 = c.indexOf("\"", colon);
        int q2 = c.indexOf("\"", q1 + 1);
        return c.substring(q1 + 1, q2);
    }

    private String geminiChat(String apiKey, String model, String prompt) throws Exception {
        String url = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent?key=" + apiKey;
        String body = "{\"contents\":[{\"parts\":[{\"text\":\"" + esc(prompt) + "\"}]}]}";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        String c = res.body();
        int i = c.indexOf("\"text\"");
        if (i < 0) return null;
        int colon = c.indexOf(":", i);
        int q1 = c.indexOf("\"", colon);
        int q2 = c.indexOf("\"", q1 + 1);
        return c.substring(q1 + 1, q2);
    }

    private String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private File memoryFile() {
        return new File(plugin.getDataFolder(), "verity_memory.properties");
    }

    private void loadMemory() {
        File f = memoryFile();
        if (!f.exists()) return;
        try (var in = new java.io.FileInputStream(f)) {
            memory.load(in);
        } catch (Exception ignored) {}
    }

    private void saveMemory() {
        try {
            plugin.getDataFolder().mkdirs();
            try (PrintWriter out = new PrintWriter(new FileWriter(memoryFile()))) {
                memory.store(out, "Verity persistent memory");
            }
        } catch (Exception ignored) {}
    }

    public void setMemory(String key, String value) {
        memory.setProperty(key, value);
        saveMemory();
    }

    public String getMemory(String key) {
        return memory.getProperty(key, "");
    }

    public void clearHistory(Player p) {
        history.remove(p.getUniqueId());
    }

    // --- World tools (что ИИ реально делает) ---
    public String worldContext(Player p) {
        org.bukkit.Location loc = p.getLocation();
        String biome = loc.getWorld().getBiome(loc.getBlockX(), loc.getBlockZ()).key().toString();
        String inv = p.getInventory().getContents().length > 0 ? "есть предметы" : "пусто";
        int mobs = p.getWorld().getNearbyEntities(loc, 16, 8, 16, e -> e instanceof org.bukkit.entity.Monster).size();
        return "Биом: " + biome + ". Инвентарь: " + inv + ". Мобов рядом: " + mobs + ". Деревня/крепость: на восток не ходи.";
    }
}
