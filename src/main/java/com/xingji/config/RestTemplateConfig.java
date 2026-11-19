package com.xingji.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate() {
        // RestTemplate 是 Spring 提供的 HTTP 客户端，用来发 GET/POST 请求，调用第三方接口
        return new RestTemplate();
    }

}
