package com.exam_bank.auth_service.config;

import com.exam_bank.auth_service.config.properties.NotificationRabbitProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class RabbitConfig {

    private final NotificationRabbitProperties notificationRabbitProperties;

    @Bean
    public JacksonJsonMessageConverter jacksonJsonMessageConverter() {
        return new JacksonJsonMessageConverter();
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
    public Queue notificationEmailQueue() {
        return new Queue(notificationRabbitProperties.getEmailQueue(), true);
    }

    @Bean
    public Binding notificationEmailOtpBinding(TopicExchange notificationEventsExchange, Queue notificationEmailQueue) {
        return BindingBuilder
                .bind(notificationEmailQueue)
                .to(notificationEventsExchange)
                .with(notificationRabbitProperties.getEmailOtpRoutingKey());
    }
}
