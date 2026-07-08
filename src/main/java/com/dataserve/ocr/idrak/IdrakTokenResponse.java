package com.dataserve.ocr.idrak;

public class IdrakTokenResponse {
	private final String accessToken;
	private final String tokenType;
	private final int expiresIn;
	private final String scope;

	public IdrakTokenResponse(String accessToken, String tokenType, int expiresIn, String scope) {
		this.accessToken = accessToken;
		this.tokenType = tokenType;
		this.expiresIn = expiresIn;
		this.scope = scope;
	}

	public String getAccessToken() {
		return accessToken;
	}

	public String getTokenType() {
		return tokenType;
	}

	public int getExpiresIn() {
		return expiresIn;
	}

	public String getScope() {
		return scope;
	}

	public String getAuthorizationHeader() {
		return tokenType + " " + accessToken;
	}
}
