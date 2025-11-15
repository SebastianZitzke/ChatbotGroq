package com.example.BotTravelUnsta;



// --- Imports de Spring Boot ---
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.stereotype.Component;

// --- Imports de TelegramBots ---
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.ActionType; // ✅ CORRECCIÓN: Import AÑADIDO
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

/**
 * Clase ÚNICA que actúa como:
 * 1. @SpringBootApplication: El punto de entrada de Spring.
 * 2. @Component: Un Bean de Spring.
 * 3. TelegramLongPollingBot: La lógica del bot.
 */
@SpringBootApplication
@Component // Le dice a Spring que esta es una clase que debe gestionar
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
		System.out.println("✅ Bot Guía Turístico iniciado!");
	}

	/**
	 * Constructor usado por Spring para inyectar los valores
	 * desde application.properties (que a su vez los toma de las variables de entorno).
	 */
	public GuiaTuristicoBot(
			@Value("${telegram.bot.token}") String botToken,
			@Value("${telegram.bot.username}") String botUsername,
			@Value("${groq.api.key}") String groqApiKey) {

		super(botToken); // Pasa el token a la clase padre
		this.botUsername = botUsername;
		this.groqApiKey = groqApiKey;
	}

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
		enviarAccion(chatId, ActionType.TYPING); // ✅ CORRECCIÓN: Llamada usando el enum

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
		requestBody.addProperty("model", "llama-3.3-70b-versatile"); // O el modelo que prefieras

		JsonArray messages = new JsonArray();

		// Mensaje de Sistema (Personalidad)
		JsonObject systemMessage = new JsonObject();
		systemMessage.addProperty("role", "system");
		systemMessage.addProperty("content", systemPrompt);
		messages.add(systemMessage);

		// Mensaje del Usuario (Pregunta)
		JsonObject userMessage = new JsonObject();
		userMessage.addProperty("role", "user");
		userMessage.addProperty("content", preguntaUsuario);
		messages.add(userMessage);

		requestBody.add("messages", messages);
		requestBody.addProperty("temperature", 0.7);
		requestBody.addProperty("max_tokens", 1024);

		// Construir la petición HTTP
		Request request = new Request.Builder()
				.url(GROQ_API_URL)
				.addHeader("Authorization", "Bearer " + groqApiKey)
				.addHeader("Content-Type", "application/json")
				.post(RequestBody.create(requestBody.toString(), MediaType.parse("application/json")))
				.build();

		// Ejecutar la petición y parsear la respuesta
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

	/**
	 * Envía una acción de chat (como "typing...").
	 */
	private void enviarAccion(long chatId, ActionType accion) { // ✅ CORRECCIÓN: El parámetro es ActionType
		SendChatAction chatAction = new SendChatAction();
		chatAction.setChatId(String.valueOf(chatId));
		chatAction.setAction(accion); // La librería maneja el enum
		try {
			execute(chatAction);
		} catch (TelegramApiException e) {
			e.printStackTrace();
		}
	}
}