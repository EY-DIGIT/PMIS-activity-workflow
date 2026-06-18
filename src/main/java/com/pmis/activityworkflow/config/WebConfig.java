package com.pmis.activityworkflow.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pmis.activityworkflow.security.AuthTokenFilter;
import com.pmis.activityworkflow.security.TokenIntrospectClient;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers servlet filters and any global MVC configuration.
 */
@Configuration
public class WebConfig {

    /**
     * Register the token-authentication filter before Spring's default filter chain.
     * Order 1 ensures it runs before any other custom filters.
     */
    @Bean
    public FilterRegistrationBean<AuthTokenFilter> authTokenFilter(
            TokenIntrospectClient introspectClient,
            IntrospectProperties props,
            ObjectMapper objectMapper) {

        FilterRegistrationBean<AuthTokenFilter> bean = new FilterRegistrationBean<>();
        bean.setFilter(new AuthTokenFilter(introspectClient, props, objectMapper));
        bean.addUrlPatterns("/*");
        bean.setOrder(1);
        bean.setName("authTokenFilter");
        return bean;
    }
}
