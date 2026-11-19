package com.xingji.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "xingji.oauth")
public class OAuthProperties {

    private boolean mock;

    private ProviderConfig qq;
    private ProviderConfig wechat;

    @Data
    public static class ProviderConfig {
        private String clientId;
        private String clientSecret;
        private String redirectUri;
    }
}
