package com.livebarn.sushi.config;

import org.h2.tools.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
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
