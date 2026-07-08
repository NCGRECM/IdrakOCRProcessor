package com.dataserve.ocr.idrak;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

import com.dataserve.ocr.exception.IdrakException;
import com.dataserve.ocr.util.Config;
import com.dataserve.ocr.util.Log;

public class IdrakAuthClient {

	public IdrakTokenResponse login() throws IdrakException {
		Log.debug("Idrak login: starting");
		validateConfiguration();
		Log.debug("Idrak login: configuration validated");

		HttpURLConnection connection = null;
		try {
			URL url = new URL(Config.getIdrakTokenUrl());
			Log.debug("Idrak login: opening connection to " + url);
			connection = (HttpURLConnection) url.openConnection();
			connection.setRequestMethod("POST");
			connection.setConnectTimeout(Config.getIdrakConnectionTimeout());
			connection.setReadTimeout(Config.getIdrakReadTimeout());
			connection.setDoOutput(true);
			connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
			if (hasValue(Config.getIdrakCookie())) {
				connection.setRequestProperty("Cookie", Config.getIdrakCookie());
				Log.debug("Idrak login: cookie header enabled");
			}

			Log.debug("Idrak login: building request body for client_id=" + Config.getIdrakClientId()
					+ ", grant_type=" + Config.getIdrakGrantType()
					+ ", scope=" + Config.getIdrakScope()
					+ ", client_secret=<redacted>");
			byte[] requestBody = buildRequestBody().getBytes("UTF-8");
			Log.debug("Idrak login: request body prepared, size=" + requestBody.length + " bytes");
			connection.setRequestProperty("Content-Length", String.valueOf(requestBody.length));
			Log.debug("Idrak login: sending request");
			try (OutputStream os = connection.getOutputStream()) {
				os.write(requestBody);
			}
			Log.debug("Idrak login: request sent");

			int statusCode = connection.getResponseCode();
			Log.debug("Idrak login: response status=" + statusCode);
			String responseBody = readResponse(statusCode == HttpURLConnection.HTTP_OK
					? connection.getInputStream()
					: connection.getErrorStream());
			Log.debug("Idrak login: raw response received");

			if (statusCode != HttpURLConnection.HTTP_OK) {
				Log.error("Idrak login: failed response body=" + responseBody);
				throw new IdrakException("Idrak login failed with HTTP status " + statusCode + ": " + responseBody);
			}

			String accessToken = extractString(responseBody, "access_token");
			if (!hasValue(accessToken)) {
				Log.error("Idrak login: access_token missing from response");
				throw new IdrakException("Idrak login response does not contain access_token");
			}

			String tokenType = extractString(responseBody, "token_type");
			if (!hasValue(tokenType)) {
				tokenType = "Bearer";
				Log.debug("Idrak login: token_type missing, defaulting to Bearer");
			}

			int expiresIn = extractInt(responseBody, "expires_in");
			String scope = extractString(responseBody, "scope");
			Log.debug("Idrak login: parsed response fields token_type=" + tokenType
					+ ", expires_in=" + expiresIn
					+ ", scope=" + scope
					+ ", access_token_length=" + accessToken.length());

			Log.debug("Idrak login: completed successfully");
			return new IdrakTokenResponse(accessToken, tokenType, expiresIn, scope);
		} catch (IOException e) {
			Log.error("Idrak login: I/O failure while calling token endpoint", e);
			throw new IdrakException("Error calling Idrak login API", e);
		} finally {
			if (connection != null) {
				Log.debug("Idrak login: closing connection");
				connection.disconnect();
			}
		}
	}

	private String buildRequestBody() throws IOException {
		StringBuilder body = new StringBuilder();
		appendFormValue(body, "client_id", Config.getIdrakClientId());
		appendFormValue(body, "client_secret", Config.getIdrakClientSecret());
		appendFormValue(body, "grant_type", Config.getIdrakGrantType());
		appendFormValue(body, "scope", Config.getIdrakScope());
		return body.toString();
	}

	private void appendFormValue(StringBuilder body, String name, String value) throws IOException {
		if (body.length() > 0) {
			body.append("&");
		}
		body.append(URLEncoder.encode(name, "UTF-8"));
		body.append("=");
		body.append(URLEncoder.encode(value, "UTF-8"));
	}

	private String readResponse(InputStream inputStream) throws IOException {
		if (inputStream == null) {
			return "";
		}

		StringBuilder response = new StringBuilder();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, "UTF-8"))) {
			String line;
			while ((line = reader.readLine()) != null) {
				response.append(line);
			}
		}
		return response.toString();
	}

	private void validateConfiguration() throws IdrakException {
		Log.debug("Idrak login: validating configuration");
		if (!hasValue(Config.getIdrakTokenUrl())) {
			throw new IdrakException("Missing idrak-token-url in config.properties");
		}
		if (!hasValue(Config.getIdrakClientId())) {
			throw new IdrakException("Missing idrak-client-id in config.properties");
		}
		if (!hasValue(Config.getIdrakClientSecret())) {
			throw new IdrakException("Missing idrak-client-secret in config.properties");
		}
		if (!hasValue(Config.getIdrakGrantType())) {
			throw new IdrakException("Missing idrak-grant-type in config.properties");
		}
		if (!hasValue(Config.getIdrakScope())) {
			throw new IdrakException("Missing idrak-scope in config.properties");
		}
		Log.debug("Idrak login: configuration values are present");
	}

	private boolean hasValue(String value) {
		return value != null && value.trim().length() > 0;
	}

	private String extractString(String json, String key) {
		String marker = "\"" + key + "\"";
		int keyIndex = json.indexOf(marker);
		if (keyIndex < 0) {
			return null;
		}

		int colonIndex = json.indexOf(":", keyIndex + marker.length());
		if (colonIndex < 0) {
			return null;
		}

		int valueStart = json.indexOf("\"", colonIndex + 1);
		if (valueStart < 0) {
			return null;
		}

		StringBuilder value = new StringBuilder();
		boolean escaped = false;
		for (int i = valueStart + 1; i < json.length(); i++) {
			char current = json.charAt(i);
			if (escaped) {
				value.append(current);
				escaped = false;
			} else if (current == '\\') {
				escaped = true;
			} else if (current == '"') {
				return value.toString();
			} else {
				value.append(current);
			}
		}
		return null;
	}

	private int extractInt(String json, String key) {
		String marker = "\"" + key + "\"";
		int keyIndex = json.indexOf(marker);
		if (keyIndex < 0) {
			return 0;
		}

		int colonIndex = json.indexOf(":", keyIndex + marker.length());
		if (colonIndex < 0) {
			return 0;
		}

		int valueStart = colonIndex + 1;
		while (valueStart < json.length() && Character.isWhitespace(json.charAt(valueStart))) {
			valueStart++;
		}

		int valueEnd = valueStart;
		while (valueEnd < json.length() && Character.isDigit(json.charAt(valueEnd))) {
			valueEnd++;
		}

		if (valueEnd == valueStart) {
			return 0;
		}

		return Integer.parseInt(json.substring(valueStart, valueEnd));
	}
}
