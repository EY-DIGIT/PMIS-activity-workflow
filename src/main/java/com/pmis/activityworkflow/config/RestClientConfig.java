package com.pmis.activityworkflow.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * One {@link RestClient} per upstream API, so each can have its own
 * connect/read timeouts.
 */
@Configuration
public class RestClientConfig {

    @Bean
    public RestClient notificationRestClient(NotificationProperties props) {
        return buildClient(props.getConnectTimeoutMs(), props.getReadTimeoutMs());
    }

    @Bean
    public RestClient milestoneCommentsRestClient(MilestoneCommentsProperties props) {
        // Uploads need longer read timeout than notify.
        return buildClient(props.getConnectTimeoutMs(), props.getReadTimeoutMs());
    }

    @Bean
    public RestClient assignmentsRestClient(AssignmentsProperties props) {
        return buildClient(props.getConnectTimeoutMs(), props.getReadTimeoutMs());
    }

    private RestClient buildClient(int connectTimeoutMs, int readTimeoutMs) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        return RestClient.builder().requestFactory(factory).build();
    }
}
