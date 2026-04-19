package com.exam_bank.auth_service.config;

import com.exam_bank.auth_service.config.properties.NotificationRabbitProperties;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.support.converter.JacksonJavaTypeMapper;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class RabbitConfig {

    private final NotificationRabbitProperties notificationRabbitProperties;

    @Bean
    public JacksonJsonMessageConverter jacksonJsonMessageConverter() {
        JacksonJsonMessageConverter converter = new JacksonJsonMessageConverter();
        // Cross-service events can carry producer type headers; prefer listener
        // argument type.
        converter.setTypePrecedence(JacksonJavaTypeMapper.TypePrecedence.INFERRED);
        return converter;
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
            JacksonJsonMessageConverter messageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter);
        return template;
    }

    @Bean
    public TopicExchange notificationEventsExchange() {
        return new TopicExchange(notificationRabbitProperties.getExchange(), true, false);
    }

    @Bean
    public Queue inAppAdminAlertQueue() {
        return new Queue(notificationRabbitProperties.getInAppAdminAlertQueue(), true);
    }

    @Bean
    public Binding inAppAdminAlertBinding(
            @Qualifier("inAppAdminAlertQueue") Queue inAppAdminAlertQueue,
            @Qualifier("notificationEventsExchange") TopicExchange notificationEventsExchange) {
        return BindingBuilder.bind(inAppAdminAlertQueue)
                .to(notificationEventsExchange)
                .with(notificationRabbitProperties.getAdminAlertRoutingKey());
    }

    @Bean
    public TopicExchange authEventsExchange(@Value("${auth.events.exchange:auth.events}") String exchangeName) {
        return new TopicExchange(exchangeName, true, false);
    }
}
