package es.bluesolution.dlq_streaming.functional_framework;

import es.bluesolution.dlq_streaming.functional_framework.RailwayErrorResponseBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

/**
 * Configuration for Railway-Oriented Programming error handling components.
 * Sets up the error message formatter and wires it into the error response builder.
 */
@Configuration
public class RailwayErrorHandlingConfig {

    @Bean
    @DependsOn("errorMessageFormatter")
    public RailwayErrorResponseBuilderConfigurer railwayErrorResponseBuilderConfigurer(
            ErrorMessageFormatter errorMessageFormatter) {
        
        // Wire the formatter into the static builder
        RailwayErrorResponseBuilder.setErrorMessageFormatter(errorMessageFormatter);
        
        return new RailwayErrorResponseBuilderConfigurer();
    }

    /**
     * Simple marker class to ensure configuration is complete.
     */
    public static class RailwayErrorResponseBuilderConfigurer {
        // Marker class - no implementation needed
    }
}