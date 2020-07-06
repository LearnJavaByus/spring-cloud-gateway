package org.springframework.cloud.gateway.sample;

import reactor.core.publisher.Mono;

import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.cloud.gateway.config.GatewayAutoConfiguration;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * 限流配置
 * @author xuguocai 2020/7/6 9:21
 */
@Configuration
@AutoConfigureBefore(GatewayAutoConfiguration.class)
public class KeyResolverConfig {

	@Bean
	@Lazy(false)
	public KeyResolver userKeyResolver() {
		return exchange -> Mono.just(exchange.getRequest().getQueryParams().getFirst("user"));
	}

}
