package com.example.BotTravelUnsta;




// --- Imports de Spring Boot ---
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.stereotype.Component;

// --- Imports de TelegramBots ---
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.ActionType;
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

// --- Imports de HTTP y JSON (OkHttp & Gson) ---
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import okhttp3.*;

// --- Imports estándar de Java ---
import java.io.IOException;

// --- ✅ IMPORTS AÑADIDOS PARA EL REGISTRO ---
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
import jakarta.annotation.PostConstruct; // Importante para el ciclo de vida de Spring

@SpringBootApplication
@Component
public class GuiaTuristicoBot extends TelegramLongPollingBot {

	// --- Campos Inyectados ---
	private final String botUsername;
	private final String groqApiKey;

	// --- Constantes y Clientes ---
	private static final String GROQ_API_URL = "https://api.groq.com/openai/v1/chat/completions";
	private final OkHttpClient httpClient = new OkHttpClient();
	private final Gson gson = new Gson();

	/**
	 * Punto de entrada principal para la aplicación Spring Boot.
	 */
	public static void main(String[] args) {
		SpringApplication.run(GuiaTuristicoBot.class, args);
		// El System.out.println de aquí se movió al método de registro
	}

	/**
	 * Constructor usado por Spring para inyectar los valores
	 */
	public GuiaTuristicoBot(
			@Value("${telegram.bot.token}") String botToken,
			@Value("${telegram.bot.username}") String botUsername,
			@Value("${groq.api.key}") String groqApiKey) {

		super(botToken); // Pasa el token a la clase padre
		this.botUsername = botUsername;
		this.groqApiKey = groqApiKey;
	}

	// --- ✅ MÉTODO NUEVO PARA MANTENER VIVA LA APP ---
	/**
	 * Este método se ejecuta DESPUÉS de que Spring crea el bot.
	 * Registra manualmente el bot con la API de Telegram.
	 * Esto inicia los hilos de sondeo (polling) y mantiene la aplicación viva.
	 */
	@PostConstruct
	public void registrarBot() {
		try {
			TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
			botsApi.registerBot(this); // 'this' es la instancia actual de GuiaTuristicoBot
			System.out.println("✅ Bot registrado y escuchando exitosamente!");
		} catch (TelegramApiException e) {
			System.err.println("❌ Error al registrar el bot: " + e.getMessage());
			e.printStackTrace();
			// Si el registro falla (ej. mal token), la app podría detenerse
		}
	}
	// --- FIN DEL MÉTODO NUEVO ---


	@Override
	public String getBotUsername() {
		return this.botUsername;
	}

	/**
	 * Método principal que procesa todas las actualizaciones (mensajes)
	 */
	@Override
	public void onUpdateReceived(Update update) {
		if (!update.hasMessage() || !update.getMessage().hasText()) {
			return;
		}

		String mensajeUsuario = update.getMessage().getText();
		long chatId = update.getMessage().getChatId();

		// Manejar comando /start
		if (mensajeUsuario.equals("/start")) {
			String bienvenida = "¡Hola! 👋 Soy tu guía turístico virtual.\n\n" +
					"Pregúntame lo que quieras sobre cualquier destino del mundo:\n" +
					"🗺️ ¿Qué ver en París?\n" +
					"🍽️ ¿Dónde comer la mejor pasta en Roma?\n";
			enviarTexto(chatId, bienvenida);
			return;
		}

		// Mostrar "Escribiendo..."
		enviarAccion(chatId, ActionType.TYPING);

		// Procesar en hilo separado para no bloquear
		new Thread(() -> {
			try {
				String respuestaIA = obtenerRespuestaGuia(mensajeUsuario);
				enviarTexto(chatId, respuestaIA);
			} catch (Exception e) {
				e.printStackTrace();
				enviarTexto(chatId, "❌ Lo siento, ocurrió un error al procesar tu consulta.");
			}
		}).start();
	}

	/**
	 * Llama a la API de Groq para obtener una respuesta de IA.
	 */
	private String obtenerRespuestaGuia(String preguntaUsuario) throws IOException {

		// El "cerebro" del bot: define su personalidad
		String systemPrompt = "Eres 'TravelBot', un guía turístico experto, amigable y entusiasta. " +
				"Tu objetivo es dar recomendaciones de viaje, describir atracciones, sugerir itinerarios " +
				"y responder preguntas sobre cultura, comida y geografía de forma concisa y útil. " +
				"Usa emojis para hacer la conversación más amigable (ej: 🗺️, ✈️, 🍽️, 🏛️).";

		JsonObject requestBody = new JsonObject();
		requestBody.addProperty("model", "llama-3.3-70b-versatile");

		JsonArray messages = new JsonArray();
		JsonObject systemMessage = new JsonObject();
		systemMessage.addProperty("role", "system");
		systemMessage.addProperty("content", systemPrompt);
		messages.add(systemMessage);

		JsonObject userMessage = new JsonObject();
		userMessage.addProperty("role", "user");
		userMessage.addProperty("content", preguntaUsuario);
		messages.add(userMessage);

		requestBody.add("messages", messages);
		requestBody.addProperty("temperature", 0.7);
		requestBody.addProperty("max_tokens", 1024);

		Request request = new Request.Builder()
				.url(GROQ_API_URL)
				.addHeader("Authorization", "Bearer " + groqApiKey)
				.addHeader("Content-Type", "application/json")
				.post(RequestBody.create(requestBody.toString(), MediaType.parse("application/json")))
				.build();

		try (Response response = httpClient.newCall(request).execute()) {
			if (response.isSuccessful() && response.body() != null) {
				JsonObject json = gson.fromJson(response.body().string(), JsonObject.class);
				return json.getAsJsonArray("choices")
						.get(0).getAsJsonObject()
						.getAsJsonObject("message")
						.get("content").getAsString();
			} else {
				System.err.println("Error en Groq API: " + response.code());
				return "❌ Error al contactar al servicio de IA.";
			}
		}
	}

	// --- Métodos de Ayuda de Telegram ---

	private void enviarTexto(long chatId, String texto) {
		SendMessage message = new SendMessage();
		message.setChatId(String.valueOf(chatId));
		message.setText(texto);
		try {
			execute(message);
		} catch (TelegramApiException e) {
			e.printStackTrace();
		}
	}

	private void enviarAccion(long chatId, ActionType accion) {
		SendChatAction chatAction = new SendChatAction();
		chatAction.setChatId(String.valueOf(chatId));
		chatAction.setAction(accion);
		try {
			execute(chatAction);
		} catch (TelegramApiException e) {
			e.printStackTrace();
		}
	}
}