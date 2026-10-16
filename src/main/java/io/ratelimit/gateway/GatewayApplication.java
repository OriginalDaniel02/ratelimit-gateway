package io.ratelimit.gateway;

import io.ratelimit.limiter.Limiters;
import io.ratelimit.limiter.RedisScriptRunner;
import io.ratelimit.policy.PolicyRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableConfigurationProperties(GatewayProperties.class)
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }

    @Bean(destroyMethod = "close")
    RedisConnection redisConnection(GatewayProperties props) {
        return new RedisConnection(props.redisUri(), props.redis());
    }

    @Bean(destroyMethod = "close")
    PolicyRegistry policyRegistry(GatewayProperties props, RedisConnection redis) {
        RedisScriptRunner runner = new RedisScriptRunner(redis);
        return new PolicyRegistry(props.policyFile(), spec -> Limiters.create(runner, spec), props.policyPollInterval());
    }

    @Bean
    RedisGuard redisGuard(GatewayProperties props) {
        return new RedisGuard(props.breaker());
    }

    @Bean
    GatewayFilter gatewayFilter(GatewayProperties props, PolicyRegistry policies, RedisGuard guard) {
        return new GatewayFilter(props, policies, guard);
    }
}
