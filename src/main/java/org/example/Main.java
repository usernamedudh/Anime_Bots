// java    String token = "8240609754:AAFkQvZOnVfx4zI-fnvR7UabRZ5iqIhUHRU";
//        String username = "SimpleBot";
package org.example;

import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

/**
 * Main — старт приложения.
 * Читает TELEGRAM_TOKEN и TELEGRAM_USERNAME из окружения.
 */
public class Main {
    public static void main(String[] args) throws Exception {
        String token = "8240609754:AAFkQvZOnVfx4zI-fnvR7UabRZ5iqIhUHRU";
        String username = "SimpleBot";

        if (token == null || username == null) {
            System.err.println("Установите переменные окружения TELEGRAM_TOKEN и TELEGRAM_USERNAME");
            System.exit(1);
        }

        TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
        botsApi.registerBot(new AnimeBot(username, token));

        System.out.println("AnimeBot запущен!");
    }
}