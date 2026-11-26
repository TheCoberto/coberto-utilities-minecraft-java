package com.thecoberto.utilities.discord;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Posts and edits messages through a Discord webhook. Call off the main thread: every call waits for Discord. */
public final class Webhook {
	/** What Discord said: the HTTP status, and the message id when there is one. */
	public record Result(int status, String messageId, String body) {
		public boolean ok() { return status >= 200 && status < 300; }
	}

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private final String url;

	public Webhook(String url) {
		this.url = url.trim().replaceAll("/+$", "").replaceAll("\\?.*$", "");
	}

	public static boolean looksValid(String url) {
		return url != null && url.matches("https://(?:\\w+\\.)?discord(?:app)?\\.com/api/webhooks/\\d+/[\\w-]+/?");
	}

	/** A new message; threadId (may be blank) puts it in a thread of the webhook's channel. */
	public Result post(String json, String threadId) throws Exception {
		String target = url + "?wait=true" + (threadId == null || threadId.isBlank() ? "" : "&thread_id=" + threadId.trim());
		return send(HttpRequest.newBuilder(URI.create(target)).POST(HttpRequest.BodyPublishers.ofString(json)));
	}

	/** Replaces a message this webhook posted. 404 means it was deleted. */
	public Result edit(String messageId, String json) throws Exception {
		return send(HttpRequest.newBuilder(URI.create(url + "/messages/" + messageId))
				.method("PATCH", HttpRequest.BodyPublishers.ofString(json)));
	}

	private Result send(HttpRequest.Builder request) throws Exception {
		HttpResponse<String> response = http.send(request
				.header("Content-Type", "application/json")
				.header("User-Agent", "CobertoUtilities-KingdomBoard")
				.timeout(Duration.ofSeconds(20))
				.build(), HttpResponse.BodyHandlers.ofString());
		String body = response.body();
		return new Result(response.statusCode(), response.statusCode() < 300 ? Json.topLevelId(body) : null, body);
	}
}
