/*
 * Copyright 2013-2019 the original author or authors.
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

package org.springframework.cloud.gateway.filter.factory;

import java.net.URI;
import java.util.Arrays;
import java.util.List;

import reactor.core.publisher.Mono;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.support.HttpStatusHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.util.Assert;
import org.springframework.web.server.ServerWebExchange;

import static org.springframework.cloud.gateway.support.GatewayToStringStyler.filterToStringCreator;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.setResponseStatus;

/**
 * @author Spencer Gibb
 *
 * 将响应重定向到指定 URL ，并设置响应状态码为指定 Status 。注意，Status 必须为 3XX 重定向状态码。
 *
 */
public class RedirectToGatewayFilterFactory
		extends AbstractGatewayFilterFactory<RedirectToGatewayFilterFactory.Config> {

	/**
	 * Status key. 状态键.
	 */
	public static final String STATUS_KEY = "status";

	/**
	 * URL key. 重定向地址键.
	 */
	public static final String URL_KEY = "url";

	public RedirectToGatewayFilterFactory() {
		super(Config.class);
	}

	@Override
	public List<String> shortcutFieldOrder() {
		return Arrays.asList(STATUS_KEY, URL_KEY);
	}

	@Override
	public GatewayFilter apply(Config config) {
		return apply(config.status, config.url);
	}

	public GatewayFilter apply(String statusString, String urlString) {
		// 解析 status ，并判断是否是 3XX 重定向状态
		HttpStatusHolder httpStatus = HttpStatusHolder.parse(statusString);
		Assert.isTrue(httpStatus.is3xxRedirection(),
				"status must be a 3xx code, but was " + statusString);
		// 创建 URL
		final URI url = URI.create(urlString);
		return apply(httpStatus, url);
	}

	public GatewayFilter apply(HttpStatus httpStatus, URI uri) {
		return apply(new HttpStatusHolder(httpStatus, null), uri);
	}

	public GatewayFilter apply(HttpStatusHolder httpStatus, URI uri) {
		return new GatewayFilter() {
			@Override
			public Mono<Void> filter(ServerWebExchange exchange,
					GatewayFilterChain chain) {
				if (!exchange.getResponse().isCommitted()) {
					// 设置响应 Status
					setResponseStatus(exchange, httpStatus);
					// 设置响应 Header
					final ServerHttpResponse response = exchange.getResponse();
					response.getHeaders().set(HttpHeaders.LOCATION, uri.toString());
					return response.setComplete();
				}
				return Mono.empty();
			}

			@Override
			public String toString() {
				String status;
				if (httpStatus.getHttpStatus() != null) {
					status = String.valueOf(httpStatus.getHttpStatus().value());
				}
				else {
					status = httpStatus.getStatus().toString();
				}
				return filterToStringCreator(RedirectToGatewayFilterFactory.this)
						.append(status, uri).toString();
			}
		};
	}

	public static class Config {

		String status;

		String url;

		public String getStatus() {
			return status;
		}

		public void setStatus(String status) {
			this.status = status;
		}

		public String getUrl() {
			return url;
		}

		public void setUrl(String url) {
			this.url = url;
		}

	}

}
