package ru.spbstu.cryptoadvisor.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableRabbit
public class RabbitConfig {

    public static final String QUEUE_ALERTS_CHECK = "q.alerts.check";
    public static final String QUEUE_NOTIFICATIONS = "q.notifications";
    public static final String QUEUE_LOGS = "crypto.logs";
    public static final String DELAY_QUEUE = "q.alerts.delay.20s";
    public static final String DELAY_QUEUE_24H = "q.alerts.delay.24h";

    @Bean
    public ConnectionFactory connectionFactory() {
        CachingConnectionFactory connectionFactory = new CachingConnectionFactory();
        connectionFactory.setHost(System.getenv().getOrDefault("RABBITMQ_HOST", "localhost"));
        connectionFactory.setPort(Integer.parseInt(System.getenv().getOrDefault("RABBITMQ_PORT", "5672")));
        connectionFactory.setUsername(System.getenv().getOrDefault("RABBITMQ_USER", "guest"));
        connectionFactory.setPassword(System.getenv().getOrDefault("RABBITMQ_PASSWORD", "guest"));

        // Add connection listeners for debugging
        connectionFactory.addConnectionListener(new org.springframework.amqp.rabbit.connection.ConnectionListener() {
            @Override
            public void onCreate(org.springframework.amqp.rabbit.connection.Connection connection) {
                System.out.println("RabbitMQ Connection Created: " + connection.toString());
            }

            @Override
            public void onClose(org.springframework.amqp.rabbit.connection.Connection connection) {
                System.out.println("RabbitMQ Connection Closed");
            }
        });

        return connectionFactory;
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter jsonMessageConverter) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(jsonMessageConverter);
        return rabbitTemplate;
    }

    @Bean
    public RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        admin.setAutoStartup(true);
        return admin;
    }

    @Bean(name = "rabbitListenerContainerFactory")
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(ConnectionFactory connectionFactory, MessageConverter jsonMessageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jsonMessageConverter);
        factory.setAutoStartup(true);
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(5);
        // Avoid consumers from dying on exception, just reject and dead-letter or requeue
        factory.setDefaultRequeueRejected(false);
        return factory;
    }

    @Bean
    public Queue logQueue() {
        return QueueBuilder.durable(QUEUE_LOGS).build();
    }

    @Bean
    public Queue alertsCheckQueue() {
        return QueueBuilder.durable(QUEUE_ALERTS_CHECK).build();
    }

    @Bean
    public Queue notificationsQueue() {
        return QueueBuilder.durable(QUEUE_NOTIFICATIONS).build();
    }

    @Bean
    public Queue delayQueue() {
        return QueueBuilder.durable(DELAY_QUEUE)
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", QUEUE_ALERTS_CHECK)
                .withArgument("x-message-ttl", 20000) // 20 seconds retry delay
                .build();
    }

    @Bean
    public Queue delayQueue24h() {
        return QueueBuilder.durable(DELAY_QUEUE_24H)
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", QUEUE_ALERTS_CHECK)
                .withArgument("x-message-ttl", 86400000) // 24 hours
                .build();
    }

    @Bean
    public MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}

