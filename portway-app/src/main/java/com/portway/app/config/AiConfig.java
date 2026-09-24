package com.portway.app.config;

import com.portway.app.ai.AiProperties;
import com.portway.app.ai.AiTranslatorFactory;
import com.portway.app.ai.AnthropicLlmClient;
import com.portway.app.ai.JpaAiCache;
import com.portway.app.ai.TranslationSchema;
import com.portway.app.repo.AiCacheRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The LLM layer exists only when switched on. Without it, every method the rules cannot handle is
 * stubbed for a human, which is a complete and honest outcome in its own right.
 */
@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AiConfig {

  @Bean
  @ConditionalOnProperty(name = "portway.ai.enabled", havingValue = "true")
  AiTranslatorFactory aiTranslatorFactory(AiProperties properties, AiCacheRepository cache) {
    TranslationSchema schema = new TranslationSchema();
    return new AiTranslatorFactory(
        properties, new AnthropicLlmClient(properties, schema.apiSchema()), new JpaAiCache(cache), schema);
  }
}
