package com.ashish.stockresearch.trace;

import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the development trace.
 *
 * The tool-calling manager is decorated through a {@link BeanPostProcessor}
 * rather than by declaring a replacement bean. Spring AI's autoconfiguration
 * builds that manager with a resolver, an exception processor and an
 * observation registry; defining our own bean would make it back off and we
 * would have to reproduce that wiring and keep it in step with future
 * versions. Wrapping the finished bean keeps their construction and only
 * adds logging around it.
 */
@Configuration
public class TraceConfiguration {

    @Bean
    ConversationTraceAdvisor conversationTraceAdvisor(TraceProperties traceProperties) {
        return new ConversationTraceAdvisor(traceProperties);
    }

    /**
     * Static, and takes an {@link ObjectProvider} rather than the properties
     * themselves: a BeanPostProcessor is created before most of the context,
     * so injecting a bean directly here would drag it into early
     * initialisation and cost it any post-processing of its own. The
     * properties are resolved on first use instead, by which point the
     * context is built.
     */
    @Bean
    @ConditionalOnProperty(prefix = "app.trace", name = "enabled", havingValue = "true", matchIfMissing = true)
    static BeanPostProcessor toolCallTracingPostProcessor(ObjectProvider<TraceProperties> traceProperties) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof ToolCallingManager manager && !(bean instanceof ToolCallTracingManager)) {
                    return new ToolCallTracingManager(manager, traceProperties.getObject());
                }
                return bean;
            }
        };
    }
}
