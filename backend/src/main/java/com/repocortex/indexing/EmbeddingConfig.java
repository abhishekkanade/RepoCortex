package com.repocortex.indexing;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class EmbeddingConfig {

    @Bean
    public OpenAiCompatibleEmbeddingModel embeddingModel(
            @Value("${app.embedding.base-url}") String baseUrl,
            @Value("${app.embedding.api-key}") String apiKey,
            @Value("${app.embedding.model}") String model,
            @Value("${app.embedding.dimensions}") int dimensions) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
        requestFactory.setReadTimeout(Duration.ofSeconds(60));
        return new OpenAiCompatibleEmbeddingModel(RestClient.builder().requestFactory(requestFactory),
                baseUrl, apiKey, model, dimensions);
    }
}
