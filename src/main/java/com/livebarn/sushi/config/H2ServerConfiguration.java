package com.livebarn.sushi.config;

import org.h2.tools.Server;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "h2.tcp.enabled", havingValue = "true", matchIfMissing = true)
public class H2ServerConfiguration {

    @Bean(destroyMethod = "stop")
    public Server h2TcpServer() throws Exception {
        return Server.createTcpServer(
                "-tcp",
                "-tcpAllowOthers",
                "-tcpPort", "9092",
                "-baseDir", "./data"
        ).start();
    }
}
