
package org.example;

import com.google.gson.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Упрощённый AnimeBot:
 * - /start, /help, /search <название>
 * - Кнопки используют короткое callback_data: "anime:{malId}" и "episode:{malId}:{epNum}"
 * - Название аниме подтягивается из API при обработке callback (чтобы не хранить длинные строки в callback_data)
 */
public class AnimeBot extends TelegramLongPollingBot {

    private static final Logger log = LoggerFactory.getLogger(AnimeBot.class);

    private final String username;
    private final String token;

    private final HttpClient http = HttpClient.newHttpClient();
    private final Gson gson = new Gson();

    private static final String SEARCH_API = "https://api.jikan.moe/v4/anime?q=%s&limit=5";
    private static final String EPISODES_API = "https://api.jikan.moe/v4/anime/%s/episodes";
    private static final String ANIME_API = "https://api.jikan.moe/v4/anime/%s";

    private static final String CRUNCHYROLL_SEARCH = "https://www.crunchyroll.com/search?from=search&q=%s";
    private static final String GOOGLE_SEARCH = "https://www.google.com/search?q=%s";

    public AnimeBot(String username, String token) {
        this.username = username;
        this.token = token;
    }

    @Override
    public String getBotUsername() { return username; }

    @Override
    public String getBotToken() { return token; }

    @Override
    public void onUpdateReceived(Update update) {
        try {
            if (update == null) return;
            if (update.hasMessage() && update.getMessage().hasText()) {
                handleMessage(update.getMessage());
            } else if (update.hasCallbackQuery()) {
                handleCallback(update.getCallbackQuery());
            }
        } catch (Exception e) {
            log.error("Ошибка обработки обновления", e);
        }
    }

    private void handleMessage(Message message) throws TelegramApiException {
        String text = message.getText().trim();
        long chatId = message.getChatId();

        if (text.startsWith("/start")) {
            sendText(chatId, "Привет! Используй команду `\\/search <название>` чтобы найти аниме.\nНапример: `\\/search Naruto`");
            return;
        }
        if (text.startsWith("/help")) {
            sendText(chatId, "`\\/search <название>` — найти аниме\nПосле выбора аниме — увидите список эпизодов. Для просмотра используйте легальные сервисы.");
            return;
        }
        if (text.startsWith("/search")) {
            String[] parts = text.split("\\s+", 2);
            if (parts.length < 2 || parts[1].isBlank()) {
                sendText(chatId, "Укажите название: `\\/search <название>`");
                return;
            }
            searchAndSendResults(chatId, parts[1].trim());
            return;
        }
        sendText(chatId, "Неизвестная команда. Используйте `\\/help`.");
    }

    private void handleCallback(CallbackQuery callback) throws TelegramApiException {
        String data = callback.getData();
        long chatId = callback.getMessage().getChatId();

        if (data == null) return;
        if (data.startsWith("anime:")) {
            String[] parts = data.split(":", 2);
            if (parts.length >= 2) {
                sendEpisodesList(chatId, parts[1]);
                answerCallback(callback, "Показываю эпизоды...");
            }
            return;
        }
        if (data.startsWith("episode:")) {
            String[] parts = data.split(":", 3);
            if (parts.length >= 3) {
                sendEpisodeLinks(chatId, parts[1], parts[2]);
                answerCallback(callback, "Генерирую ссылки на просмотр...");
            }
        }
    }

    private void searchAndSendResults(long chatId, String query) {
        try {
            String url = String.format(SEARCH_API, URLEncoder.encode(query, StandardCharsets.UTF_8));
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().header("Accept", "application/json").build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonObject root = gson.fromJson(resp.body(), JsonObject.class);
            JsonArray data = root.has("data") ? root.getAsJsonArray("data") : new JsonArray();

            if (data.size() == 0) {
                sendText(chatId, "Ничего не найдено по запросу: " + query);
                return;
            }

            List<List<InlineKeyboardButton>> rows = new ArrayList<>();
            for (JsonElement el : data) {
                JsonObject anime = el.getAsJsonObject();
                int malId = anime.get("mal_id").getAsInt();
                String title = anime.has("title") ? anime.get("title").getAsString() : ("anime-" + malId);

                InlineKeyboardButton btn = new InlineKeyboardButton();
                btn.setText(title);
                // короткий callback_data — только идентификатор (чтобы не превышать лимит)
                btn.setCallbackData("anime:" + malId);
                rows.add(Collections.singletonList(btn));
            }
            sendInlineKeyboard(chatId, "Выберите аниме (результаты поиска):", rows);
        } catch (IOException | InterruptedException e) {
            log.error("Ошибка запроса к API поиска", e);
            sendText(chatId, "Ошибка при поиске. Попробуйте позже.");
        }
    }

    private void sendEpisodesList(long chatId, String malId) {
        try {
            String url = String.format(EPISODES_API, URLEncoder.encode(malId, StandardCharsets.UTF_8));
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().header("Accept", "application/json").build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonObject root = gson.fromJson(resp.body(), JsonObject.class);
            JsonArray data = root.has("data") ? root.getAsJsonArray("data") : new JsonArray();

            String title = fetchAnimeTitle(malId);

            if (data.size() == 0) {
                sendText(chatId, "Эпизоды не найдены. Можно поискать на легальных сервисах:");
                sendText(chatId, String.format("Crunchyroll: %s", String.format(CRUNCHYROLL_SEARCH, urlEncode(title))));
                return;
            }

            List<List<InlineKeyboardButton>> rows = new ArrayList<>();
            int limit = Math.min(12, data.size());
            for (int i = 0; i < limit; i++) {
                JsonObject ep = data.get(i).getAsJsonObject();
                int epNum = ep.has("episode") && !ep.get("episode").isJsonNull() ? ep.get("episode").getAsInt() : (i + 1);
                InlineKeyboardButton btn = new InlineKeyboardButton();
                btn.setText("Эп. " + epNum);
                btn.setCallbackData("episode:" + malId + ":" + epNum);
                rows.add(Collections.singletonList(btn));
            }
            sendInlineKeyboard(chatId, "Эпизоды для: " + title, rows);
        } catch (IOException | InterruptedException e) {
            log.error("Ошибка запроса эпизодов", e);
            sendText(chatId, "Ошибка при получении эпизодов. Попробуйте позже.");
        }
    }

    private void sendEpisodeLinks(long chatId, String malId, String episodeNumber) {
        try {
            String title = fetchAnimeTitle(malId);
            String qCrunch = String.format(CRUNCHYROLL_SEARCH, urlEncode(title + " episode " + episodeNumber));
            String qGoogle = String.format(GOOGLE_SEARCH, urlEncode(title + " episode " + episodeNumber + " watch"));

            StringBuilder sb = new StringBuilder();
            sb.append("Для просмотра используйте легальные сервисы:\n");
            sb.append("Crunchyroll: ").append(qCrunch).append("\n");
            sb.append("Поиск: ").append(qGoogle).append("\n\n");
            sb.append("Я не отправляю mp4. Поддерживайте авторов — используйте официальные платформы.");
            sendText(chatId, sb.toString());
        } catch (Exception e) {
            log.error("Ошибка формирования ссылок", e);
            sendText(chatId, "Не удалось сформировать ссылки. Попробуйте вручную поискать на официальных платформах.");
        }
    }

    private String fetchAnimeTitle(String malId) {
        try {
            String url = String.format(ANIME_API, URLEncoder.encode(malId, StandardCharsets.UTF_8));
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().header("Accept", "application/json").build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonObject root = gson.fromJson(resp.body(), JsonObject.class);
            JsonObject data = root.has("data") ? root.getAsJsonObject("data") : null;
            if (data != null && data.has("title")) return data.get("title").getAsString();
        } catch (Exception e) {
            log.debug("Не удалось получить title для malId=" + malId, e);
        }
        return "anime-" + malId;
    }

    private void sendText(long chatId, String text) {
        SendMessage msg = new SendMessage();
        msg.setChatId(String.valueOf(chatId));
        msg.enableMarkdownV2(true);
        msg.setText(escapeMarkdownV2(text));
        try {
            execute(msg);
        } catch (TelegramApiException e) {
            log.error("Ошибка отправки сообщения", e);
        }
    }

    private void sendInlineKeyboard(long chatId, String text, List<List<InlineKeyboardButton>> rows) {
        SendMessage msg = new SendMessage();
        msg.setChatId(String.valueOf(chatId));
        msg.setText(text);
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);
        msg.setReplyMarkup(markup);
        try {
            execute(msg);
        } catch (TelegramApiException e) {
            log.error("Ошибка отправки inline клавиатуры", e);
        }
    }

    private void answerCallback(CallbackQuery callback, String text) {
        AnswerCallbackQuery acq = new AnswerCallbackQuery();
        acq.setCallbackQueryId(callback.getId());
        acq.setText(text);
        try {
            execute(acq);
        } catch (TelegramApiException e) {
            log.error("Ошибка ответа на callback", e);
        }
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private static String escapeMarkdownV2(String s) {
        if (s == null) return "";
        return s.replaceAll("([_\\\\*\\[\\]\\(\\)~`>#+\\-=\\|{}\\.!])", "\\\\$1");
    }
}