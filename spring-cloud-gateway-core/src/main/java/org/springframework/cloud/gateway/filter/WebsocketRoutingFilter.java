/*
 * Copyright 2017-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.gateway.filter;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import reactor.core.publisher.Mono;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.filter.headers.HttpHeadersFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import org.springframework.web.reactive.socket.server.WebSocketService;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriComponentsBuilder;

import static org.springframework.cloud.gateway.filter.headers.HttpHeadersFilter.filterRequest;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.isAlreadyRouted;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.setAlreadyRouted;
import static org.springframework.util.StringUtils.commaDelimitedListToStringArray;

/**
 * @author Spencer Gibb
 *
 * Websocket 路由网关过滤器。其根据 ws:// / wss:// 前缀( Scheme )过滤处理，
 * 代理后端 Websocket 服务，提供给客户端连接。
 *
 */
public class WebsocketRoutingFilter implements GlobalFilter, Ordered {

	/**
	 * Sec-Websocket protocol.
	 */
	public static final String SEC_WEBSOCKET_PROTOCOL = "Sec-WebSocket-Protocol";

	private static final Log log = LogFactory.getLog(WebsocketRoutingFilter.class);

	private final WebSocketClient webSocketClient;

	private final WebSocketService webSocketService;

	private final ObjectProvider<List<HttpHeadersFilter>> headersFiltersProvider;

	// do not use this headersFilters directly, use getHeadersFilters() instead.
	private volatile List<HttpHeadersFilter> headersFilters;

	public WebsocketRoutingFilter(WebSocketClient webSocketClient,
			WebSocketService webSocketService,
			ObjectProvider<List<HttpHeadersFilter>> headersFiltersProvider) {
		this.webSocketClient = webSocketClient;
		this.webSocketService = webSocketService;
		this.headersFiltersProvider = headersFiltersProvider;
	}

	/* for testing */
	static String convertHttpToWs(String scheme) {
		scheme = scheme.toLowerCase();
		return "http".equals(scheme) ? "ws" : "https".equals(scheme) ? "wss" : scheme;
	}

	@Override
	public int getOrder() {
		// Before NettyRoutingFilter since this routes certain http requests
		return Ordered.LOWEST_PRECEDENCE - 1;
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		changeSchemeIfIsWebSocketUpgrade(exchange);
		// 获得 requestUrl
		URI requestUrl = exchange.getRequiredAttribute(GATEWAY_REQUEST_URL_ATTR);
		String scheme = requestUrl.getScheme();
		// 判断是否能够处理
		if (isAlreadyRouted(exchange)
				|| (!"ws".equals(scheme) && !"wss".equals(scheme))) {
			return chain.filter(exchange);
		}
		// 设置已经路由
		setAlreadyRouted(exchange);

		HttpHeaders headers = exchange.getRequest().getHeaders();
		HttpHeaders filtered = filterRequest(getHeadersFilters(), exchange);

		List<String> protocols = headers.get(SEC_WEBSOCKET_PROTOCOL);
		if (protocols != null) {
			protocols = headers.get(SEC_WEBSOCKET_PROTOCOL).stream().flatMap(
					header -> Arrays.stream(commaDelimitedListToStringArray(header)))
					.map(String::trim).collect(Collectors.toList());
		}
		// 处理连接请求
		return this.webSocketService.handleRequest(exchange, new ProxyWebSocketHandler(
				requestUrl, this.webSocketClient, filtered, protocols));
	}

	private List<HttpHeadersFilter> getHeadersFilters() {
		if (this.headersFilters == null) {
			this.headersFilters = this.headersFiltersProvider
					.getIfAvailable(ArrayList::new);

			headersFilters.add((headers, exchange) -> {
				HttpHeaders filtered = new HttpHeaders();
				headers.entrySet().stream()
						.filter(entry -> !entry.getKey().toLowerCase()
								.startsWith("sec-websocket"))
						.forEach(header -> filtered.addAll(header.getKey(),
								header.getValue()));
				return filtered;
			});
		}

		return this.headersFilters;
	}

	private void changeSchemeIfIsWebSocketUpgrade(ServerWebExchange exchange) {
		// Check the Upgrade
		URI requestUrl = exchange.getRequiredAttribute(GATEWAY_REQUEST_URL_ATTR);
		String scheme = requestUrl.getScheme().toLowerCase();
		String upgrade = exchange.getRequest().getHeaders().getUpgrade();
		// change the scheme if the socket client send a "http" or "https"
		if ("WebSocket".equalsIgnoreCase(upgrade)
				&& ("http".equals(scheme) || "https".equals(scheme))) {
			String wsScheme = convertHttpToWs(scheme);
			URI wsRequestUrl = UriComponentsBuilder.fromUri(requestUrl).scheme(wsScheme)
					.build().toUri();
			exchange.getAttributes().put(GATEWAY_REQUEST_URL_ATTR, wsRequestUrl);
			if (log.isTraceEnabled()) {
				log.trace("changeSchemeTo:[" + wsRequestUrl + "]");
			}
		}
	}

	/**
	 * 代理后端 WebSocket 服务处理器
	 */
	private static class ProxyWebSocketHandler implements WebSocketHandler {

		private final WebSocketClient client;

		private final URI url;

		private final HttpHeaders headers;

		private final List<String> subProtocols;

		ProxyWebSocketHandler(URI url, WebSocketClient client, HttpHeaders headers,
				List<String> protocols) {
			this.client = client;
			this.url = url;
			this.headers = headers;
			if (protocols != null) {
				this.subProtocols = protocols;
			}
			else {
				this.subProtocols = Collections.emptyList();
			}
		}

		@Override
		public List<String> getSubProtocols() {
			return this.subProtocols;
		}

		@Override
		public Mono<Void> handle(WebSocketSession session) {
			// pass headers along so custom headers can be sent through
			return client.execute(url, this.headers, new WebSocketHandler() {
				@Override
				public Mono<Void> handle(WebSocketSession proxySession) {
					// Use retain() for Reactor Netty
					// 转发消息 客户端 =》后端服务
					Mono<Void> proxySessionSend = proxySession
							.send(session.receive().doOnNext(WebSocketMessage::retain));
					// .log("proxySessionSend", Level.FINE);
					// 转发消息 后端服务=》客户端
					Mono<Void> serverSessionSend = session.send(
							proxySession.receive().doOnNext(WebSocketMessage::retain));
					// .log("sessionSend", Level.FINE);
					return Mono.zip(proxySessionSend, serverSessionSend).then();
				}

				/**
				 * Copy subProtocols so they are available downstream.
				 * @return
				 */
				@Override
				public List<String> getSubProtocols() {
					return ProxyWebSocketHandler.this.subProtocols;
				}
			});
		}

	}

}
